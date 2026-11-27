package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Draws a reprint mark (by default "REPRINT") on every page of every PDF document in the job
 * (PRD-15, AC-5). The mark sits at a position measured from the top-left of each page, in
 * points, so it stays on the page in the same place on every sheet.
 *
 * <p>A document that already carries the mark (the {@value #MARKED} property) is left alone, so
 * a rerun doesn't stamp it twice. AFP documents fail the job: their mark is added by a later step
 * (PRD-15 FR-9 to FR-13), and a reprint that would go out unmarked must not succeed silently.
 *
 * <p>Params: {@code text} (default {@code REPRINT}), {@code size} in points (default 12),
 * {@code position} as {@code [x, y]} from the top-left (default {@code [400, 8]}).
 */
public final class MarkReprintStep implements Step {

    /** The document property set once its pages carry the mark. */
    static final String MARKED = "reprintMarked";

    private final String text;
    private final double size;
    private final double x;
    private final double y;

    public MarkReprintStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.text = params.string("text", "REPRINT");
        this.size = params.number("size", 12);
        if (size <= 0) {
            throw new IllegalArgumentException("Step param 'size' must be positive");
        }
        List<String> position = params.optionalStringList("position");
        double[] at = {400, 8};
        if (!position.isEmpty()) {
            if (position.size() != 2) {
                throw new IllegalArgumentException(
                        "Step param 'position' must be [x, y] in points from the top-left");
            }
            at = new double[] {Double.parseDouble(position.get(0)),
                    Double.parseDouble(position.get(1))};
        }
        if (at[0] < 0 || at[1] < 0) {
            throw new IllegalArgumentException("Step param 'position' must not be negative");
        }
        this.x = at[0];
        this.y = at[1];
    }

    @Override
    public boolean resumable() {
        return true;
    }

    @Override
    public void execute(Job job) throws IOException {
        // Mark everything first, then swap the marked copies in, so a failure leaves the job's
        // documents as they were.
        List<Document> marked = new ArrayList<>();
        for (Document doc : job.documents()) {
            marked.add(mark(job, doc));
        }
        List<Document> documents = job.documents();
        for (int i = 0; i < documents.size(); i++) {
            documents.set(i, marked.get(i));
        }
    }

    private Document mark(Job job, Document doc) throws IOException {
        if (doc.property(MARKED) != null) {
            return doc;
        }
        if (!".pdf".equals(WriteOutputStep.extension(doc.file()))) {
            throw new IllegalStateException("Document " + doc.sequence() + " is not a PDF, and"
                    + " its reprint mark isn't supported yet (AFP marking comes later)");
        }
        // A new file, so the document's original is never written over.
        Path dir = job.workDir().resolve("reprint");
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, String.format("%05d-", doc.sequence()), ".pdf");
        try (PDDocument pdf = Loader.loadPDF(doc.file().toFile())) {
            for (PDPage page : pdf.getPages()) {
                stamp(pdf, page);
            }
            pdf.save(out.toFile());
        }
        Document result = new Document(doc.sequence(), out, doc.properties(), doc.inputSequence());
        result.properties().put(MARKED, "yes");
        return result;
    }

    /** Draws the mark on one page, at the configured position from its top-left corner. */
    private void stamp(PDDocument pdf, PDPage page) throws IOException {
        PDRectangle box = page.getCropBox();
        // PDF's origin is bottom-left; the position is measured from the top-left.
        double left = box.getLowerLeftX() + x;
        double baseline = box.getUpperRightY() - y - size;
        try (PDPageContentStream content = new PDPageContentStream(pdf, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), (float) size);
            content.setNonStrokingColor(0f);
            content.newLineAtOffset((float) left, (float) baseline);
            content.showText(text);
            content.endText();
        }
    }
}
