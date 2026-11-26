package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.pdf.DPartTree;
import dev.yjimmy.printlab.pdf.DPartTree.DPart;
import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfFormatException;
import dev.yjimmy.printlab.pdf.UnsupportedFilterException;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits a PDF print file into documents using its document-part tree (the {@code /DPartRoot}
 * that PDF 2.0 and PDF/VT writers add, or {@code generate --dpart} writes; see {@link DPartTree}
 * for the shapes read), instead of reading text off the page as {@link SplitPdfStep} does. The
 * PDF counterpart of {@link SplitAfpStep}: the file carries its own boundaries and index, so
 * nothing depends on where text sits on the page.
 *
 * <pre>
 *   params:
 *     level: 1                              # optional; the tree depth whose nodes are documents
 *     properties:                           # DPM key -> document property name
 *       AccountNumber: accountNumber
 *       Name: name
 *       Zip: zip
 *     requiredProperties: [accountNumber, name, zip]   # optional; names from properties
 *     uncoveredPages: fail                  # optional; fail (default) or ignore
 * </pre>
 *
 * <p>Without {@code level}, the file's {@code /RecordLevel} is used, or 1 if it has none; the job
 * log says which level was used and why. Each document is the pages its node's range gives,
 * written with PDFBox as a standalone PDF. Its {@code DPM} entries become document properties,
 * named as {@code properties} maps them. A required property that's absent or blank marks the
 * document {@code indexError} instead of failing the job, so routing can send it to the exception
 * queue. The document also gets {@code pageCount} and {@code format=pdf}; a property can't be
 * mapped to those two or to {@code indexError}, and two DPM keys can't map to one property.
 *
 * <p>The job fails when the file has no DPart tree (the message suggests {@link SplitPdfStep}),
 * when two documents share a page, and, unless {@code uncoveredPages: ignore}, when a page belongs
 * to no document at the chosen level (it would otherwise be dropped from the output without a
 * trace). {@code ignore} is for files whose cover or separator pages sit outside the records; the
 * number left out is the {@code uncoveredPages} job property.
 *
 * <p>A page of a PDF/VT file points back at its DPart node, and through that at the whole tree.
 * Copied as it is, each output document would carry every other document's index values (names,
 * account numbers, zips), so those back-pointers are removed from the copied pages (see
 * {@link PdfPages}).
 *
 * <p>The tree is read with this project's own reader, which is strict. If it can't read the file
 * (a damaged cross-reference table, say) or the file is encrypted, PDFBox opens it instead, which
 * repairs and decrypts it (an empty user password only; it also drops any permission restrictions
 * the owner set), and the step reads a clean copy it saves in the work folder and deletes when the
 * step ends, so no decrypted copy is left behind. A file that needs a password fails the job, and
 * so does one PDFBox can't open either; the message gives both reasons. Every output document is
 * unencrypted.
 *
 * <p><b>Memory:</b> the file is read into memory by this project's reader and again by PDFBox, so
 * the heap needed follows the file's object count, about 0.7 to 1.6 KB per object in the files
 * measured (see {@link HeapLimit}). The two readers don't hold it at once: this project's is finished with
 * before PDFBox loads the pages to copy, and for a repaired or decrypted file PDFBox's first copy
 * is closed before the clean one is read. The step warns when the JVM's heap is below the
 * recommendation, and running out of Java heap space part way through fails the job with the heap
 * to ask for (documents already written stay in the work folder). Each output document carries its
 * own copy of the fonts and images its pages use, so output can be much larger than the input when
 * the run shares large resources; the total is the {@code documentBytes} job property.
 */
public final class SplitPdfByDPartStep implements Step {

    private static final Logger log = LoggerFactory.getLogger(SplitPdfByDPartStep.class);

    /** Properties the step sets itself on every document. */
    private static final Set<String> RESERVED = Set.of("pageCount", "format", IndexErrors.PROPERTY);

    /** The level from the config, or null to use the file's {@code /RecordLevel}. */
    private final Integer level;
    /** DPM key to document property name, in the order written in the YAML. */
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final List<String> requiredProperties;
    /** Whether pages outside every document are left out instead of failing the job. */
    private final boolean ignoreUncovered;
    /** The JVM's heap limit; see {@link HeapLimit}. */
    private final LongSupplier maxHeap;

    public SplitPdfByDPartStep(Map<String, Object> rawParams) {
        this(rawParams, Runtime.getRuntime()::maxMemory);
    }

    /** As above, with the JVM's heap limit supplied, so a test can pretend it's small. */
    SplitPdfByDPartStep(Map<String, Object> rawParams, LongSupplier maxHeap) {
        this.maxHeap = maxHeap;
        Params params = new Params(rawParams);
        if (rawParams.containsKey("level")) {
            Object raw = rawParams.get("level");
            if (!(raw instanceof Number n) || n.longValue() != n.doubleValue()) {
                throw new IllegalArgumentException("level must be a whole number of 1 or more, not "
                        + raw);
            }
            if (n.longValue() < 1) {
                throw new IllegalArgumentException("level must be 1 or more, not " + raw);
            }
            this.level = n.intValue();
        } else {
            this.level = null;
        }
        String uncovered = params.string("uncoveredPages", "fail");
        if (!uncovered.equals("fail") && !uncovered.equals("ignore")) {
            throw new IllegalArgumentException("uncoveredPages must be fail or ignore, not '"
                    + uncovered + "'");
        }
        this.ignoreUncovered = uncovered.equals("ignore");
        Map<String, String> byProperty = new LinkedHashMap<>();
        params.map("properties").forEach((key, name) -> {
            if (!(name instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("properties: '" + key
                        + "' must name a document property");
            }
            if (RESERVED.contains(s)) {
                throw new IllegalArgumentException("properties: '" + key + "' maps to '" + s
                        + "', which the step sets itself");
            }
            String other = byProperty.put(s, key);
            if (other != null) {
                throw new IllegalArgumentException("properties: '" + other + "' and '" + key
                        + "' both map to '" + s + "'");
            }
            properties.put(key, s);
        });
        this.requiredProperties = params.optionalStringList("requiredProperties");
        for (String required : requiredProperties) {
            if (!byProperty.containsKey(required)) {
                throw new IllegalArgumentException("requiredProperties: '" + required
                        + "' isn't one of the properties this step sets: "
                        + String.join(", ", byProperty.keySet()));
            }
        }
    }

    @Override
    public void execute(Job job) throws Exception {
        Path docsDir = Files.createDirectories(job.workDir().resolve("documents"));
        Path clean = job.workDir().resolve("normalized.pdf");
        try {
            HeapLimit.run(job.inputFile(), "SplitPdfByDPart", maxHeap,
                    () -> split(job, docsDir, clean));
        } finally {
            // A repaired or decrypted copy of the input isn't kept, whether the split worked or not.
            Files.deleteIfExists(clean);
        }
        SplitOutput.record(job, "SplitPdfByDPart");
    }

    private void split(Job job, Path docsDir, Path clean) throws Exception {
        Path readable = job.inputFile();
        String repairedBecause = null;
        Parsed parsed;
        try {
            parsed = parse(readable);
        } catch (PdfFormatException | UnsupportedFilterException | NeedsPdfBox e) {
            repairedBecause = e.getMessage();
            log.info("{}: this project's reader can't read {} ({}); reading a PDFBox copy instead",
                    job.id(), readable.getFileName(), repairedBecause);
            readable = normalize(job.inputFile(), clean, repairedBecause);
            try {
                parsed = parse(readable);
            } catch (NeedsPdfBox again) {
                throw new IllegalStateException("The PDF is still encrypted after PDFBox"
                        + " decrypted it", again);
            } catch (IllegalStateException | PdfFormatException | UnsupportedFilterException after) {
                throw new IllegalStateException("The file had to be repaired or decrypted by PDFBox"
                        + " first (this project's reader said: " + repairedBecause + "), and the"
                        + " copy still can't be read as a DPart file: " + after.getMessage(),
                        after);
            }
        }
        job.properties().put("totalPages", String.valueOf(parsed.pageCount()));
        String lost = repairedBecause == null ? "" : " The file had to be repaired by PDFBox first ("
                + repairedBecause + "), which may have lost the tree.";
        DPartTree tree = parsed.tree().orElseThrow(() -> new IllegalStateException(
                "The PDF has no DPart tree (/DPartRoot), so there are no documents to split out"
                + " by DPart. Use SplitPdf to find documents from text on the page instead." + lost));
        int effective = level != null ? level : tree.recordLevel().orElse(1);
        String chosenBy = level != null ? "from the workflow" : tree.recordLevel().isPresent()
                ? "from the file's /RecordLevel" : "the default";
        List<DPart> parts = tree.atLevel(effective);
        if (parts.isEmpty()) {
            throw new IllegalStateException("The DPart tree has no documents at level "
                    + effective + " (" + chosenBy + ")");
        }
        log.info("{}: splitting {} pages into {} documents at DPart level {} ({})", job.id(),
                parsed.pageCount(), parts.size(), effective, chosenBy);
        int uncovered = checkCoverage(parts, parsed.pageCount(), effective);
        if (uncovered > 0) {
            log.info("{}: {} page(s) are in no document at level {} and are left out", job.id(),
                    uncovered, effective);
            job.properties().put("uncoveredPages", String.valueOf(uncovered));
        }

        try (PDDocument source = Loader.loadPDF(readable.toFile())) {
            List<PDPage> sourcePages = new ArrayList<>();
            source.getPages().forEach(sourcePages::add);
            if (sourcePages.size() != parsed.pageCount()) {
                throw new IllegalStateException("The DPart reader found " + parsed.pageCount()
                        + " pages, but PDFBox found " + sourcePages.size());
            }
            // The page tree's own count, which PDFBox's repair keeps as it was read. If it
            // disagrees with the pages that can be reached, pages are missing (or listed twice),
            // and the DPart ranges, which count pages, may not mean what the file's writer meant.
            if (source.getNumberOfPages() != sourcePages.size()) {
                throw new IllegalStateException("The file's page tree says it has "
                        + source.getNumberOfPages() + " pages, but " + sourcePages.size()
                        + " can be read" + (repairedBecause == null ? "" : " (after PDFBox"
                        + " repaired it; this project's reader had said: " + repairedBecause + ")")
                        + ", so the file is damaged and its DPart ranges can't be trusted.");
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

    /** What this project's reader found in a file. */
    private record Parsed(int pageCount, Optional<DPartTree> tree) {
    }

    /** The file is encrypted, so only PDFBox can read it. */
    private static final class NeedsPdfBox extends Exception {
        private static final long serialVersionUID = 1L;

        NeedsPdfBox() {
            super("the file is encrypted");
        }
    }

    private static Parsed parse(Path file) throws IOException, NeedsPdfBox {
        PdfDocumentReader pdf = PdfDocumentReader.open(file);
        if (pdf.isEncrypted()) {
            throw new NeedsPdfBox();
        }
        int pages = pdf.pages().size();
        if (pages == 0) {
            throw new IllegalStateException("The PDF has no pages, so there are no documents"
                    + " to split out");
        }
        return new Parsed(pages, DPartTree.read(pdf));
    }

    /**
     * Has PDFBox open {@code input} (repairing and decrypting it) and save a clean copy to
     * {@code clean}. {@code firstProblem} is why this project's reader gave up, kept in the message
     * if PDFBox fails too.
     */
    private static Path normalize(Path input, Path clean, String firstProblem) throws IOException {
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            document.setAllSecurityToBeRemoved(true);
            document.save(clean.toFile());
        } catch (InvalidPasswordException e) {
            throw new IllegalStateException("The PDF needs a password to open; SplitPdfByDPart"
                    + " can only read a file whose user password is empty", e);
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + input.getFileName() + " as a PDF: "
                    + firstProblem + "; PDFBox couldn't open it either: " + e.getMessage(), e);
        }
        return clean;
    }

    /**
     * Fails naming the first page that no document at this level covers, unless uncovered pages
     * are to be left out; returns how many there are.
     */
    private int checkCoverage(List<DPart> parts, int pageCount, int level) {
        BitSet covered = new BitSet(pageCount + 1);
        for (DPart part : parts) {
            covered.set(part.startPage(), part.endPage() + 1);
        }
        int first = covered.nextClearBit(1);
        if (first > pageCount) {
            return 0;
        }
        if (!ignoreUncovered) {
            String where = first == 1 ? " (the file's first page, so there may be a cover page)"
                    : "";
            throw new IllegalStateException("Page " + first + " is in no DPart at level " + level
                    + ", so it would be left out of every document" + where + ". Set"
                    + " uncoveredPages: ignore to leave such pages out on purpose.");
        }
        return pageCount - (covered.cardinality());
    }

    private static void write(Job job, List<PDPage> pages, Map<String, String> props,
            Path docsDir) throws Exception {
        int sequence = job.documents().size() + 1;
        Path out = docsDir.resolve(String.format("doc-%05d.pdf", sequence));
        try (PDDocument doc = new PDDocument()) {
            for (PDPage page : pages) {
                PdfPages.importPage(doc, page);
            }
            doc.save(out.toFile());
        }
        job.documents().add(new Document(sequence, out, props, sequence));
    }
}
