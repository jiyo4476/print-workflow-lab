package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.afp.AfpReprintMark;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Marks every document in the job as a reprint (PRD-15). PDF pages get the mark drawn on them;
 * AFP documents get a {@code Reprint} TLE and a presentation text object on each page (see
 * {@link AfpReprintMark}). Each marked document is written to a new file in the job's work folder,
 * so an original is never written over.
 *
 * <p>A document already marked (the {@value #MARKED} property) is left alone, so a rerun doesn't
 * mark it twice. The job must record the job it reprints ({@code parentJobId}), which the AFP
 * TLE holds.
 *
 * <p>Params: {@code text} (default {@code REPRINT}); {@code size} in points for the PDF mark
 * (default 12); {@code position} as {@code [x, y]} in inches from the top-left of the page. Left
 * out, the position is right-aligned, {@value AfpReprintMark#DEFAULT_TOP_INCHES} inch from the
 * top.
 */
public final class MarkReprintStep implements Step {

    /** The document property set once its pages carry the mark. */
    static final String MARKED = "reprintMarked";
    /** The job property a reprint records the job it reprints under. */
    static final String PARENT = "parentJobId";

    private static final double POINTS_PER_INCH = 72;

    private final String text;
    private final double size;
    private final AfpReprintMark.Position position;

    public MarkReprintStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.text = params.string("text", "REPRINT");
        this.size = params.number("size", 12);
        if (size <= 0) {
            throw new IllegalArgumentException("Step param 'size' must be positive");
        }
        List<String> items = params.optionalStringList("position");
        if (items.isEmpty()) {
            this.position = AfpReprintMark.Position.DEFAULT;
            return;
        }
        if (items.size() != 2) {
            throw new IllegalArgumentException(
                    "Step param 'position' must be [x, y] in inches from the top-left");
        }
        double x = inches(items.get(0));
        double y = inches(items.get(1));
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Step param 'position' must not be negative");
        }
        this.position = new AfpReprintMark.Position(x, y);
    }

    private static double inches(String item) {
        try {
            return Double.parseDouble(item);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Step param 'position' must be numbers in inches, not '" + item + "'");
        }
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
        String extension = extension(doc.file());
        if (!extension.equals(".pdf") && !extension.equals(".afp")) {
            throw new IllegalStateException("Document " + doc.sequence()
                    + " is neither PDF nor AFP, so it has no reprint mark");
        }
        Path dir = job.workDir().resolve("reprint");
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, String.format("%05d-", doc.sequence()), extension);
        if (extension.equals(".pdf")) {
            markPdf(doc, out);
        } else {
            markAfp(job, doc, out);
        }
        Document result = new Document(doc.sequence(), out, doc.properties(), doc.inputSequence());
        result.properties().put(MARKED, "yes");
        return result;
    }

    private void markPdf(Document doc, Path out) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(doc.file().toFile())) {
            for (PDPage page : pdf.getPages()) {
                stampPdf(pdf, page);
            }
            pdf.save(out.toFile());
        }
    }

    /** Draws the mark on one PDF page, at the configured position from its top-left corner. */
    private void stampPdf(PDDocument pdf, PDPage page) throws IOException {
        PDRectangle box = page.getCropBox();
        double xInches = position.x() != null ? position.x()
                : box.getWidth() / POINTS_PER_INCH - AfpReprintMark.RIGHT_MARGIN_INCHES;
        // PDF's origin is bottom-left; the position is measured from the top-left.
        double left = box.getLowerLeftX() + xInches * POINTS_PER_INCH;
        double baseline = box.getUpperRightY() - position.y() * POINTS_PER_INCH - size;
        try (PDPageContentStream content = new PDPageContentStream(pdf, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                    (float) size);
            content.setNonStrokingColor(0f);
            content.newLineAtOffset((float) left, (float) baseline);
            content.showText(text);
            content.endText();
        }
    }

    private void markAfp(Job job, Document doc, Path out) throws IOException {
        String parent = job.properties().get(PARENT);
        if (parent == null) {
            throw new IllegalStateException("job " + job.id() + " records no parent job, so its"
                    + " AFP reprint has no Reprint TLE to carry");
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(doc.file()));
             OutputStream out2 = new BufferedOutputStream(Files.newOutputStream(out))) {
            AfpReprintMark.mark(in, out2, parent, text, position);
        }
    }

    /** The lower-case extension with its dot, or an empty string if there is none. */
    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
