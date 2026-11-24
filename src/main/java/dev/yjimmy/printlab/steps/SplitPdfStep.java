package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfPage;
import dev.yjimmy.printlab.pdf.RegionTextExtractor;
import dev.yjimmy.printlab.pdf.TextPositionExtractor;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import dev.yjimmy.printlab.workflow.rules.Condition;
import dev.yjimmy.printlab.workflow.rules.RuleParser;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripperByArea;

/**
 * Splits one PDF print file into documents and reads document properties from fixed page regions.
 *
 * <p>A new document starts whenever the {@code splitOn} property's value changes from one page to
 * the next (e.g. the account number printed in every page header). Each region is
 * {@code [x, y, width, height]} in points, measured from the <b>top-left</b> of the page.
 *
 * <p>That alone merges two statements in a row with the same value (two statements for one
 * account). The optional {@code newDocumentWhen} condition, in the same syntax as routing rules,
 * fixes that: it's tested against each page's region values, and a page where it's true starts a
 * new document even if {@code splitOn} didn't change:
 * <pre>
 *   regions:
 *     pageNumber: [395, 44, 130, 14]       # "Page 1 of 3"
 *   newDocumentWhen: { property: pageNumber, operator: matches, value: 'Page 1 of \d+' }
 * </pre>
 *
 * <p>{@code extractor} picks what reads the region text: {@code pdfbox} (the default) uses
 * PDFBox's {@code PDFTextStripperByArea}; {@code builtin} uses this project's own reader
 * ({@link RegionTextExtractor}), which handles simple fonts only and fails the job on a page it
 * can't read (a CID font, say) instead of indexing it wrongly. The documents are written with
 * PDFBox either way, so {@code builtin} holds the file in memory twice: PDFBox's copy and the
 * reader's bytes.
 *
 * <p>This is the PDF counterpart of AFP indexing: PDF has no built-in document boundaries or index
 * tags, so we infer them from where text sits on the page. A PDF with no pages fails the job.
 */
public final class SplitPdfStep implements Step {

    /** The values {@code extractor} may have. */
    public static final List<String> EXTRACTORS = List.of("pdfbox", "builtin");

    private final String splitOn;
    private final String extractor;
    private final Map<String, Rectangle2D> regions = new LinkedHashMap<>();
    /** Starts a document on a page where it's true; {@code null} when not configured. */
    private final Condition newDocumentWhen;

    public SplitPdfStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.splitOn = params.string("splitOn");
        this.extractor = params.string("extractor", "pdfbox");
        if (!EXTRACTORS.contains(extractor)) {
            throw new IllegalArgumentException("extractor must be pdfbox or builtin, not '" + extractor + "'");
        }
        params.map("regions").forEach((name, value) -> regions.put(name, toRect(name, value)));
        if (!regions.containsKey(splitOn)) {
            throw new IllegalArgumentException("splitOn '" + splitOn + "' must also be a region");
        }
        Object when = rawParams.get("newDocumentWhen");
        try {
            this.newDocumentWhen = when == null ? null : RuleParser.parse(when);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid 'newDocumentWhen': " + e.getMessage(), e);
        }
    }

    private static Rectangle2D toRect(String name, Object value) {
        if (!(value instanceof List<?> l) || l.size() != 4) {
            throw new IllegalArgumentException("Region '" + name + "' must be [x, y, width, height]");
        }
        double[] n = l.stream().mapToDouble(o -> Double.parseDouble(o.toString())).toArray();
        return new Rectangle2D.Double(n[0], n[1], n[2], n[3]);
    }

    @Override
    public void execute(Job job) throws Exception {
        Path docsDir = job.workDir().resolve("documents");
        Files.createDirectories(docsDir);

        try (PDDocument source = Loader.loadPDF(job.inputFile().toFile())) {
            int totalPages = source.getNumberOfPages();
            if (totalPages == 0) {
                throw new IllegalStateException("The PDF has no pages, so there are no documents"
                        + " to split out");
            }
            job.properties().put("totalPages", String.valueOf(totalPages));

            PageRegions reader = extractor.equals("builtin")
                    ? builtin(job.inputFile(), totalPages) : pdfBox(source);

            List<PDPage> currentPages = new ArrayList<>();
            Map<String, String> currentProps = null;
            String currentKey = null;
            int pageNumber = 0;

            for (PDPage page : source.getPages()) {
                pageNumber++;
                Map<String, String> pageValues = new LinkedHashMap<>();
                reader.read(pageNumber, page).forEach((region, text) -> pageValues.put(region, text.strip()));
                String key = pageValues.get(splitOn);
                if (key.isEmpty()) {
                    throw new IllegalStateException("No '" + splitOn + "' found on page "
                            + pageNumber);
                }
                boolean starts = !key.equals(currentKey)
                        || (newDocumentWhen != null && newDocumentWhen.test(pageValues));
                if (starts) {
                    flush(job, source, currentPages, currentProps, docsDir);
                    currentPages = new ArrayList<>();
                    currentProps = pageValues;
                    currentKey = key;
                }
                currentPages.add(page);
            }
            flush(job, source, currentPages, currentProps, docsDir);
        }
        job.properties().put("documentCount", String.valueOf(job.documents().size()));
    }

    /** Reads one page's region text; {@code number} is 1 for the first page. */
    private interface PageRegions {
        Map<String, String> read(int number, PDPage page) throws Exception;
    }

    private PageRegions pdfBox(PDDocument source) throws Exception {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        regions.forEach(stripper::addRegion);
        return (number, page) -> {
            stripper.extractRegions(page);
            Map<String, String> values = new LinkedHashMap<>();
            for (String region : regions.keySet()) {
                values.put(region, stripper.getTextForRegion(region));
            }
            return values;
        };
    }

    private PageRegions builtin(Path input, int totalPages) throws Exception {
        PdfDocumentReader pdf = PdfDocumentReader.open(input);
        if (pdf.isEncrypted()) {
            throw new IllegalStateException("extractor: builtin can't read an encrypted PDF; use extractor: pdfbox");
        }
        List<PdfPage> pages = pdf.pages();
        if (pages.size() != totalPages) {
            throw new IllegalStateException("extractor: builtin found " + pages.size()
                    + " pages, but PDFBox found " + totalPages);
        }
        TextPositionExtractor glyphs = new TextPositionExtractor(pdf);
        return (number, page) -> {
            try {
                return RegionTextExtractor.extract(glyphs.glyphs(pages.get(number - 1)), regions);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Page " + number + ": "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
            }
        };
    }

    private static void flush(Job job, PDDocument source, List<PDPage> pages,
            Map<String, String> props, Path docsDir) throws Exception {
        if (pages.isEmpty()) {
            return;
        }
        int sequence = job.documents().size() + 1;
        Path out = docsDir.resolve(String.format("doc-%05d.pdf", sequence));
        // Save while the source is still open: imported pages share its resources.
        try (PDDocument doc = new PDDocument()) {
            for (PDPage page : pages) {
                doc.importPage(page);
            }
            doc.save(out.toFile());
        }
        props.put("pageCount", String.valueOf(pages.size()));
        props.put("format", "pdf");
        job.documents().add(new Document(sequence, out, props));
    }
}
