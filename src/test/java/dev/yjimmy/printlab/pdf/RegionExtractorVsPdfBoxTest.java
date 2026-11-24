package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.junit.Test;

/** The built-in region text against PDFBox's {@code PDFTextStripperByArea}, region by region. */
public class RegionExtractorVsPdfBoxTest {

    /** The regions {@code workflows/statements.yaml} reads, so this test follows the workflow. */
    private static Map<String, Rectangle2D> statementRegions() throws IOException {
        WorkflowDefinition workflow = WorkflowDefinition.load(Path.of("workflows", "statements.yaml"));
        StepDefinition split = workflow.steps().stream().filter(s -> s.type().equals("SplitPdf")).findFirst().orElseThrow();
        Map<String, Rectangle2D> regions = new LinkedHashMap<>();
        ((Map<?, ?>) split.params().get("regions")).forEach((name, value) -> {
            double[] n = ((List<?>) value).stream().mapToDouble(o -> Double.parseDouble(o.toString())).toArray();
            regions.put(name.toString(), new Rectangle2D.Double(n[0], n[1], n[2], n[3]));
        });
        return regions;
    }

    private static byte[] statements(long seed, int count) throws IOException {
        try (PDDocument doc = new StatementPdfGenerator(seed).document(count)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** White space normalized: the extractors may break lines and end text differently. */
    private static String normalized(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    @Test
    public void builtinAndPdfBoxRegionTextIdenticalFor100Statements() throws IOException {
        Map<String, Rectangle2D> regions = statementRegions();
        byte[] bytes = statements(42, 100);
        PdfDocumentReader pdf = new PdfDocumentReader(bytes);
        TextPositionExtractor extractor = new TextPositionExtractor(pdf);

        int compared = 0;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            regions.forEach(stripper::addRegion);
            assertEquals("pages", doc.getNumberOfPages(), pdf.pages().size());
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                stripper.extractRegions(doc.getPage(i));
                Map<String, String> ours = RegionTextExtractor.extract(extractor.glyphs(pdf.pages().get(i)), regions);
                for (String region : regions.keySet()) {
                    assertEquals("page " + (i + 1) + " " + region,
                            normalized(stripper.getTextForRegion(region)), normalized(ours.get(region)));
                    compared++;
                }
            }
        }
        assertThat(compared).as("regions compared").isGreaterThan(100 * regions.size());
    }

    /** Fake bold (each string drawn twice, 0.3 pt apart): both extractors read it once. */
    @Test
    public void fakeBoldReadOnceByBoth() throws IOException {
        byte[] bytes = new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R"
                        + " /Resources << /Font << /F1 5 0 R >> >> >>")
                .stream(4, "", ("BT /F1 10 Tf 400 752 Td (1234567890) Tj ET"
                        + " BT /F1 10 Tf 400.3 752 Td (1234567890) Tj ET").getBytes(StandardCharsets.US_ASCII))
                .object(5, TextPositionTest.HELVETICA)
                .build("/Root 1 0 R");
        Map<String, Rectangle2D> regions = Map.of("accountNumber", statementRegions().get("accountNumber"));
        PdfDocumentReader pdf = new PdfDocumentReader(bytes);

        String ours = RegionTextExtractor.extract(pdf, pdf.pages().get(0), regions).get("accountNumber");
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            regions.forEach(stripper::addRegion);
            stripper.extractRegions(doc.getPage(0));
            assertEquals("PDFBox", "1234567890", normalized(stripper.getTextForRegion("accountNumber")));
        }
        assertEquals("builtin", "1234567890", normalized(ours));
    }

    /**
     * Where regions overlap, PDFBox gives each glyph to only one of them (its suppression of
     * duplicated text is shared by all the regions); the built-in extractor gives it to both.
     */
    @Test
    public void overlappingRegionsAreWhereTheExtractorsDifferByDesign() throws IOException {
        Map<String, Rectangle2D> regions = new LinkedHashMap<>();
        regions.put("accountNumber", statementRegions().get("accountNumber"));
        regions.put("header", new Rectangle2D.Double(0, 0, 612, 60));
        byte[] bytes = statements(42, 1);
        PdfDocumentReader pdf = new PdfDocumentReader(bytes);
        Map<String, String> ours = RegionTextExtractor.extract(new TextPositionExtractor(pdf).glyphs(pdf.pages().get(0)), regions);

        String account = ours.get("accountNumber");
        assertThat(account).matches("\\d{10}");
        assertThat(ours.get("header")).contains(account);
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            regions.forEach(stripper::addRegion);
            stripper.extractRegions(doc.getPage(0));
            int found = (stripper.getTextForRegion("accountNumber").contains(account) ? 1 : 0)
                    + (stripper.getTextForRegion("header").contains(account) ? 1 : 0);
            assertEquals("PDFBox puts the account number in one region only", 1, found);
        }
    }
}
