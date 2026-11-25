package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.pdf.DPartTree;
import dev.yjimmy.printlab.pdf.DPartTree.DPart;
import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfPage;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * Splits a PDF print file into documents using its document-part tree (the {@code /DPartRoot}
 * that PDF/VT writers add, or {@code generate --dpart} writes), instead of reading text off the
 * page as {@link SplitPdfStep} does. The PDF counterpart of {@link SplitAfpStep}: the file carries
 * its own boundaries and index, so nothing depends on where text sits on the page.
 *
 * <pre>
 *   params:
 *     level: 1                              # optional; the tree depth that holds documents
 *     properties:                           # DPM key -> document property name
 *       AccountNumber: accountNumber
 *       Name: name
 *       Zip: zip
 *     requiredProperties: [accountNumber, name, zip]   # optional; property names
 * </pre>
 *
 * <p>Each document is the pages its node's {@code Start} and {@code End} give, written with PDFBox
 * as a standalone PDF. Its {@code DPM} entries become document properties, named as
 * {@code properties} maps them. A required property that's absent or blank marks the document
 * {@code indexError} instead of failing the job, so routing can send it to the exception queue.
 * The document also gets {@code pageCount} and {@code format=pdf}.
 *
 * <p>The job fails when the file has no DPart tree (the message suggests {@link SplitPdfStep}),
 * when two documents share a page, and when a page belongs to no document at {@code level}
 * (it would otherwise be dropped from the output without a trace).
 */
public final class SplitPdfByDPartStep implements Step {

    private final int level;
    /** DPM key to document property name, in the order written in the YAML. */
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final List<String> requiredProperties;

    public SplitPdfByDPartStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.level = params.integer("level", 1);
        if (level < 1) {
            throw new IllegalArgumentException("level must be 1 or more, not " + level);
        }
        params.map("properties").forEach((key, name) -> {
            if (!(name instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("properties: '" + key
                        + "' must name a document property");
            }
            properties.put(key, s);
        });
        this.requiredProperties = params.optionalStringList("requiredProperties");
    }

    @Override
    public void execute(Job job) throws Exception {
        Path docsDir = Files.createDirectories(job.workDir().resolve("documents"));
        PdfDocumentReader pdf = PdfDocumentReader.open(job.inputFile());
        if (pdf.isEncrypted()) {
            throw new IllegalStateException("SplitPdfByDPart can't read an encrypted PDF's DPart"
                    + " tree; decrypt the file first");
        }
        List<PdfPage> pages = pdf.pages();
        if (pages.isEmpty()) {
            throw new IllegalStateException("The PDF has no pages, so there are no documents"
                    + " to split out");
        }
        job.properties().put("totalPages", String.valueOf(pages.size()));
        DPartTree tree = DPartTree.read(pdf).orElseThrow(() -> new IllegalStateException(
                "The PDF has no DPart tree (/DPartRoot), so there are no documents to split out"
                + " by DPart. Use SplitPdf to find documents from text on the page instead."));
        List<DPart> parts = tree.atLevel(level);
        if (parts.isEmpty()) {
            throw new IllegalStateException("The DPart tree has no documents at level " + level);
        }
        checkCoverage(parts, pages.size());

        try (PDDocument source = Loader.loadPDF(job.inputFile().toFile())) {
            List<PDPage> sourcePages = new ArrayList<>();
            source.getPages().forEach(sourcePages::add);
            if (sourcePages.size() != pages.size()) {
                throw new IllegalStateException("The DPart reader found " + pages.size()
                        + " pages, but PDFBox found " + sourcePages.size());
            }
            for (DPart part : parts) {
                Map<String, String> props = new LinkedHashMap<>();
                properties.forEach((key, name) -> {
                    String value = part.dpm().get(key);
                    if (value != null) {
                        props.put(name, value);
                    }
                });
                IndexErrors.markMissing(props, requiredProperties);
                props.put("pageCount", String.valueOf(part.endPage() - part.startPage() + 1));
                props.put("format", "pdf");
                // Save while the source is still open: imported pages share its resources.
                write(job, sourcePages.subList(part.startPage() - 1, part.endPage()), props,
                        docsDir);
            }
        }
        job.properties().put("documentCount", String.valueOf(job.documents().size()));
    }

    /** Fails naming the first page that no document at this level covers. */
    private void checkCoverage(List<DPart> parts, int pageCount) {
        BitSet covered = new BitSet(pageCount + 1);
        for (DPart part : parts) {
            covered.set(part.startPage(), part.endPage() + 1);
        }
        int first = covered.nextClearBit(1);
        if (first <= pageCount) {
            throw new IllegalStateException("Page " + first + " is in no DPart at level " + level
                    + ", so it would be left out of every document");
        }
    }

    private static void write(Job job, List<PDPage> pages, Map<String, String> props,
            Path docsDir) throws Exception {
        int sequence = job.documents().size() + 1;
        Path out = docsDir.resolve(String.format("doc-%05d.pdf", sequence));
        try (PDDocument doc = new PDDocument()) {
            for (PDPage page : pages) {
                doc.importPage(page);
            }
            doc.save(out.toFile());
        }
        job.documents().add(new Document(sequence, out, props));
    }
}
