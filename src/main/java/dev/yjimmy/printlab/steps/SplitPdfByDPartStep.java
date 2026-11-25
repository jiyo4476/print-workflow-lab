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
 * </pre>
 *
 * <p>Without {@code level}, the file's {@code /RecordLevel} is used, or 1 if it has none. Each
 * document is the pages its node's range gives, written with PDFBox as a standalone PDF. Its
 * {@code DPM} entries become document properties, named as {@code properties} maps them. A
 * required property that's absent or blank marks the document {@code indexError} instead of
 * failing the job, so routing can send it to the exception queue. The document also gets
 * {@code pageCount} and {@code format=pdf}; a property can't be mapped to those two or to
 * {@code indexError}, and two DPM keys can't map to one property.
 *
 * <p>The job fails when the file has no DPart tree (the message suggests {@link SplitPdfStep}),
 * when two documents share a page, and when a page belongs to no document at the chosen level (it
 * would otherwise be dropped from the output without a trace).
 *
 * <p>The tree is read with this project's own reader, which is strict. If it can't read the file
 * (a damaged cross-reference table, say) or the file is encrypted, PDFBox opens it instead, which
 * repairs and decrypts it (an empty user password only), and the step reads a clean copy it saves
 * in the work folder. A file that needs a password fails the job. Every output document is
 * unencrypted.
 *
 * <p><b>Memory:</b> the file is read into memory by this project's reader and again by PDFBox, so
 * the heap needed is a good many times the file's size (see {@link HeapLimit}: about 10 to 16
 * times in the runs measured), and a repaired or decrypted file adds a third copy while it's being
 * saved. A file the heap can't hold fails its job at once with the heap to ask for, and running
 * out of memory part way through fails the job the same way. Each output document carries its own
 * copy of the fonts and images its pages use, so output can be much larger than the input when the
 * run shares large resources; the total is the {@code documentBytes} job property.
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
            this.level = params.integer("level", 1);
            if (level < 1) {
                throw new IllegalArgumentException("level must be 1 or more, not " + level);
            }
        } else {
            this.level = null;
        }
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
        HeapLimit.check(job.inputFile(), "SplitPdfByDPart", maxHeap);
        try {
            split(job);
        } catch (OutOfMemoryError e) {
            throw HeapLimit.outOfMemory(job.inputFile(), "SplitPdfByDPart", maxHeap.getAsLong(), e);
        }
        SplitOutput.record(job, "SplitPdfByDPart");
    }

    private void split(Job job) throws Exception {
        Path docsDir = Files.createDirectories(job.workDir().resolve("documents"));
        Path readable = job.inputFile();
        Parsed parsed;
        try {
            parsed = parse(readable);
        } catch (PdfFormatException | UnsupportedFilterException | NeedsPdfBox e) {
            log.info("{}: this project's reader can't read {} ({}); reading a PDFBox copy instead",
                    job.id(), readable.getFileName(), e.getMessage());
            readable = normalize(job);
            try {
                parsed = parse(readable);
            } catch (NeedsPdfBox again) {
                throw new IllegalStateException("The PDF is still encrypted after PDFBox"
                        + " decrypted it", again);
            }
        }
        job.properties().put("totalPages", String.valueOf(parsed.pageCount()));
        DPartTree tree = parsed.tree().orElseThrow(() -> new IllegalStateException(
                "The PDF has no DPart tree (/DPartRoot), so there are no documents to split out"
                + " by DPart. Use SplitPdf to find documents from text on the page instead."));
        int effective = level != null ? level : tree.recordLevel().orElse(1);
        List<DPart> parts = tree.atLevel(effective);
        if (parts.isEmpty()) {
            throw new IllegalStateException("The DPart tree has no documents at level "
                    + effective);
        }
        checkCoverage(parts, parsed.pageCount(), effective);

        try (PDDocument source = Loader.loadPDF(readable.toFile())) {
            List<PDPage> sourcePages = new ArrayList<>();
            source.getPages().forEach(sourcePages::add);
            if (sourcePages.size() != parsed.pageCount()) {
                throw new IllegalStateException("The DPart reader found " + parsed.pageCount()
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

    /** Has PDFBox open the file (repairing and decrypting it) and save a clean copy. */
    private static Path normalize(Job job) throws IOException {
        Path clean = job.workDir().resolve("normalized.pdf");
        try (PDDocument document = Loader.loadPDF(job.inputFile().toFile())) {
            document.setAllSecurityToBeRemoved(true);
            document.save(clean.toFile());
        } catch (InvalidPasswordException e) {
            throw new IllegalStateException("The PDF needs a password to open; SplitPdfByDPart"
                    + " can only read a file whose user password is empty", e);
        }
        return clean;
    }

    /** Fails naming the first page that no document at this level covers. */
    private static void checkCoverage(List<DPart> parts, int pageCount, int level) {
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
