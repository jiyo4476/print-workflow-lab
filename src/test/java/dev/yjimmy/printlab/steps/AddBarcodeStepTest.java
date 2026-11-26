package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AddBarcodeStepTest {

    /** Where the symbol goes in the tests: top-left (72, 700), a 72 pt box, on Letter. */
    private static final double X = 72;
    private static final double Y = 700;
    private static final double SIZE = 72;
    private static final String TEMPLATE = "${jobId}|${sequence}|${page}|${pageCount}|${accountNumber}";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tmp;
    private Job job;

    @Before
    public void setUp() throws Exception {
        tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    @Test
    public void firstPageBarcodeDecodesToExpectedValues() throws Exception {
        job.documents().add(document(1, "1234", pdf("one.pdf", 2, null)));

        stamp(TEMPLATE, X, Y, SIZE);

        assertEquals("test0001|1|1|2|1234", decodeFirstPage(job.documents().get(0).file(), 0));
    }

    @Test
    public void lastPageBarcodeHasPageEqualsPageCount() throws Exception {
        job.documents().add(document(1, "1234", pdf("one.pdf", 3, null)));

        stamp(TEMPLATE, X, Y, SIZE);

        assertEquals("test0001|1|3|3|1234", decode(job.documents().get(0).file(), 2));
    }

    @Test
    public void everyPageOfEveryDocumentHasABarcode() throws Exception {
        job.documents().add(document(1, "1111", pdf("a.pdf", 3, null)));
        job.documents().add(document(2, "2222", pdf("b.pdf", 1, null)));

        stamp(TEMPLATE, X, Y, SIZE);

        assertEquals("test0001|1|1|3|1111", decode(job.documents().get(0).file(), 0));
        assertEquals("test0001|1|2|3|1111", decode(job.documents().get(0).file(), 1));
        assertEquals("test0001|1|3|3|1111", decode(job.documents().get(0).file(), 2));
        assertEquals("test0001|2|1|1|2222", decode(job.documents().get(1).file(), 0));
    }

    @Test
    public void barcodeDrawnAsVectorAtConfiguredPosition() throws Exception {
        job.documents().add(document(1, "1234", pdf("one.pdf", 1, null)));

        stamp(TEMPLATE, X, Y, SIZE);

        List<double[]> rects = new ArrayList<>();
        boolean usesImage = false;
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(
                job.documents().get(0).file().toFile())) {
            PDFStreamParser parser = new PDFStreamParser(pdf.getPage(0));
            List<Object> tokens = parser.parse();
            List<Object> operands = new ArrayList<>();
            for (Object token : tokens) {
                if (token instanceof Operator op) {
                    if (op.getName().equals("re")) {
                        double[] rect = new double[4];
                        for (int i = 0; i < 4; i++) {
                            rect[i] = ((COSNumber) operands.get(i)).floatValue();
                        }
                        rects.add(rect);
                    }
                    usesImage |= op.getName().equals("Do");
                    operands.clear();
                } else {
                    operands.add(token);
                }
            }
        }

        assertTrue("the barcode is vector shapes, not an image XObject", !usesImage);
        assertThat(rects).isNotEmpty();
        // Page is 792 pt tall; the box's top edge is 700 pt below the top, so PDF y = 92.
        double left = rects.stream().mapToDouble(r -> r[0]).min().orElseThrow();
        double top = rects.stream().mapToDouble(r -> r[1] + r[3]).max().orElseThrow();
        assertEquals("left edge at x", X, left, 0.01);
        assertEquals("top edge at y", 792 - Y, top, 0.01);
        for (double[] r : rects) {
            assertTrue("rectangle inside the box", r[0] >= X - 0.01 && r[0] + r[2] <= X + SIZE + 0.01);
            assertTrue("rectangle inside the box", r[1] >= 792 - Y - SIZE - 0.01 && r[1] + r[3] <= top + 0.01);
        }
    }

    @Test
    public void barcodeDoesNotOverlapConfiguredRegions() throws Exception {
        WorkflowDefinition workflow = WorkflowDefinition.load(Path.of("workflows", "statements.yaml"));
        WorkflowDefinition.StepDefinition split = workflow.steps().stream()
                .filter(s -> s.name().equals("split-and-index")).findFirst().orElseThrow();
        WorkflowDefinition.StepDefinition barcode = workflow.steps().stream()
                .filter(s -> s.name().equals("inserter-barcode")).findFirst().orElseThrow();
        List<Integer> position = numbers(barcode.params().get("position"));
        List<Integer> size = numbers(barcode.params().get("size"));
        @SuppressWarnings("unchecked")
        Map<String, Object> regions = (Map<String, Object>) split.params().get("regions");
        for (Map.Entry<String, Object> region : regions.entrySet()) {
            List<Integer> r = numbers(region.getValue());
            boolean overlaps = position.get(0) < r.get(0) + r.get(2)
                    && r.get(0) < position.get(0) + size.get(0)
                    && position.get(1) < r.get(1) + r.get(3)
                    && r.get(1) < position.get(1) + size.get(1);
            assertTrue("barcode overlaps region " + region.getKey(), !overlaps);
        }
    }

    @Test
    public void existingPageTextIsStillExtractable() throws Exception {
        job.documents().add(document(1, "1234", pdf("one.pdf", 1, "Account 1234 statement")));

        stamp(TEMPLATE, X, Y, SIZE);

        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(
                job.documents().get(0).file().toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Account 1234 statement");
        }
    }

    @Test
    public void preflightStillPassesAfterStamping() throws Exception {
        // Preflight checks only documents the splitter marked as PDF (format=pdf). The input is
        // a blank page: a page's own non-embedded font would fail preflight before stamping too.
        job.documents().add(new Document(1, pdf("one.pdf", 2, null),
                Map.of("accountNumber", "1234", "format", "pdf")));
        assertEquals("before stamping", "pass", preflight());

        stamp(TEMPLATE, X, Y, SIZE);

        assertEquals("after stamping", "pass", preflight());
    }

    /** Runs {@code PreflightPdf} on the job's documents and returns the first one's result. */
    private String preflight() {
        new PreflightPdfStep(Map.of()).execute(job);
        return job.documents().get(0).property("preflight");
    }

    @Test
    public void unknownPlaceholderFailsBeforeAnythingIsSaved() throws Exception {
        Path file = pdf("one.pdf", 1, null);
        job.documents().add(document(4, "1234", file));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stamp("${nope}", X, Y, SIZE));

        assertThat(e.getMessage()).contains("Document 4").contains("${nope}");
        assertEquals("the original document is left as it was", file, job.documents().get(0).file());
    }

    @Test
    public void valueTooLongForSymbologyFailsWithDocumentSequence() throws Exception {
        job.documents().add(document(7, "x".repeat(3000), pdf("one.pdf", 1, null)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stamp("${accountNumber}", X, Y, 300));

        assertThat(e.getMessage()).contains("Document 7").contains("too long");
    }

    @Test
    public void valueTooLongForSizeFailsWithDocumentSequence() throws Exception {
        job.documents().add(document(3, "1234567890123456789012345678901234567890",
                pdf("one.pdf", 1, null)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stamp("${accountNumber}", X, Y, 12));

        assertThat(e.getMessage()).contains("Document 3").contains("too long").contains("box");
    }

    @Test
    public void barcodeBoxOffThePageFails() throws Exception {
        job.documents().add(document(1, "1234", pdf("one.pdf", 1, null)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stamp("${accountNumber}", 600, Y, SIZE));

        assertThat(e.getMessage()).contains("falls off the page");
    }

    @Test
    public void afpDocumentIsRejected() throws Exception {
        Path afp = tmp.resolve("one.afp");
        Files.write(afp, new byte[] {1, 2, 3});
        job.documents().add(document(1, "1234", afp));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stamp(TEMPLATE, X, Y, SIZE));

        assertThat(e.getMessage()).contains("Document 1").contains("PDF");
    }

    @Test
    public void unsupportedSymbologyFailsAtLoad() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AddBarcodeStep(Map.of("template", "x", "symbology", "code128",
                        "position", List.of(1, 1), "size", List.of(10, 10))));

        assertThat(e.getMessage()).contains("datamatrix");
    }

    @Test
    public void positionAndSizeAreRequiredAndPositive() {
        assertThrows(IllegalArgumentException.class, () -> new AddBarcodeStep(Map.of(
                "template", "x", "position", List.of(1, 1), "size", List.of(0, 10))));
        assertThrows(IllegalArgumentException.class, () -> new AddBarcodeStep(Map.of(
                "template", "x", "position", List.of(-1, 1), "size", List.of(10, 10))));
        assertThrows(IllegalArgumentException.class, () -> new AddBarcodeStep(Map.of(
                "template", "x", "position", List.of(1), "size", List.of(10, 10))));
        assertThrows(IllegalArgumentException.class, () -> new AddBarcodeStep(Map.of(
                "template", "x", "position", List.of("a", 1), "size", List.of(10, 10))));
    }

    /** Runs the step on the job's documents in place. */
    private void stamp(String template, double x, double y, double size) throws IOException {
        new AddBarcodeStep(Map.of("template", template, "position", List.of(x, y),
                "size", List.of(size, size))).execute(job);
    }

    private static Document document(int sequence, String accountNumber, Path file) {
        return new Document(sequence, file, Map.of("accountNumber", accountNumber));
    }

    /** A Letter PDF with {@code pages} pages, the first carrying {@code text} if given. */
    private Path pdf(String name, int pages, String text) throws IOException {
        Path file = tmp.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                if (text != null && i == 0) {
                    try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(72, 600);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static String decodeFirstPage(Path file, int pageIndex) throws Exception {
        return decode(file, pageIndex);
    }

    /**
     * Renders the page at 300 dpi and decodes the symbol inside the box. The crop is the dark
     * pixels' bounds plus a two-module quiet zone: a symbol smaller than its box, cropped to the
     * whole box, left lopsided white space around it, and ZXing's detector missed it there.
     */
    private static String decode(Path file, int pageIndex) throws Exception {
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            BufferedImage page = new PDFRenderer(pdf).renderImageWithDPI(pageIndex, 300);
            double scale = 300.0 / 72;
            int boxLeft = (int) Math.round(X * scale);
            int boxTop = (int) Math.round(Y * scale);
            int boxSide = (int) Math.round(SIZE * scale);
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;
            for (int y = boxTop; y < boxTop + boxSide; y++) {
                for (int x = boxLeft; x < boxLeft + boxSide; x++) {
                    if ((page.getRGB(x, y) & 0xFF) < 128) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (maxX < 0) {
                throw new AssertionError("no dark pixels in the barcode box");
            }
            int quietZone = 24; // about two modules at this size and resolution
            int left = minX - quietZone;
            int top = minY - quietZone;
            int width = maxX - minX + 1 + 2 * quietZone;
            int height = maxY - minY + 1 + 2 * quietZone;
            int[] pixels = page.getRGB(left, top, width, height, null, 0, width);
            Result result = new DataMatrixReader().decode(new BinaryBitmap(
                    new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))));
            return result.getText();
        }
    }

    private static List<Integer> numbers(Object value) {
        return ((List<?>) value).stream().map(v -> Integer.parseInt(v.toString())).toList();
    }
}
