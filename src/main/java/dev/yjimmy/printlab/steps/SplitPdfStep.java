package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.awt.geom.Rectangle2D;
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
 * <p>This is the PDF counterpart of AFP indexing: PDF has no built-in document boundaries or index
 * tags, so we infer them from where text sits on the page.
 */
public final class SplitPdfStep implements Step {

    private final String splitOn;
    private final Map<String, Rectangle2D> regions = new LinkedHashMap<>();

    public SplitPdfStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.splitOn = params.string("splitOn");
        params.map("regions").forEach((name, value) -> regions.put(name, toRect(name, value)));
        if (!regions.containsKey(splitOn)) {
            throw new IllegalArgumentException("splitOn '" + splitOn + "' must also be a region");
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
        java.nio.file.Files.createDirectories(docsDir);

        try (PDDocument source = Loader.loadPDF(job.inputFile().toFile())) {
            job.properties().put("totalPages", String.valueOf(source.getNumberOfPages()));

            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            regions.forEach(stripper::addRegion);

            List<PDPage> currentPages = new ArrayList<>();
            Map<String, String> currentProps = null;
            String currentKey = null;

            for (PDPage page : source.getPages()) {
                stripper.extractRegions(page);
                String key = stripper.getTextForRegion(splitOn).strip();
                if (key.isEmpty()) {
                    throw new IllegalStateException("No '" + splitOn + "' found on page "
                            + (source.getPages().indexOf(page) + 1));
                }
                if (!key.equals(currentKey)) {
                    flush(job, source, currentPages, currentProps, docsDir);
                    currentPages = new ArrayList<>();
                    currentProps = new LinkedHashMap<>();
                    for (String region : regions.keySet()) {
                        currentProps.put(region, stripper.getTextForRegion(region).strip());
                    }
                    currentKey = key;
                }
                currentPages.add(page);
            }
            flush(job, source, currentPages, currentProps, docsDir);
        }
        job.properties().put("documentCount", String.valueOf(job.documents().size()));
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
        job.documents().add(new Document(sequence, out, props));
    }
}
