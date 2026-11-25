package dev.yjimmy.printlab.steps;

import org.junit.rules.TemporaryFolder;
import org.junit.Rule;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import java.nio.file.Path;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.testsupport.TestJobs;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Config validation and edge cases. Splitting real PDFs is covered end to end by
 * {@code StatementWorkflowTest}.
 */
public class SplitPdfStepTest {

    private static final List<Integer> RECT = List.of(0, 0, 100, 20);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void validConfigIsAccepted() {
        new SplitPdfStep(Map.of("splitOn", "account",
                "regions", Map.of("account", RECT, "zip", List.of("1.5", "2", "3", "4"))));
    }

    @Test
    public void splitOnMustBeOneOfTheRegions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("splitOn", "account",
                        "regions", Map.of("zip", RECT))));

        assertThat(e.getMessage()).contains("splitOn 'account' must also be a region");
    }

    @Test
    public void regionMustBeFourNumbers() {
        for (Object bad : new Object[] {List.of(0, 0, 100), List.of(0, 0, 100, 20, 5), "0,0,1,1"}) {
            IllegalArgumentException e = assertThrows(String.valueOf(bad),
                    IllegalArgumentException.class, () -> new SplitPdfStep(Map.of(
                            "splitOn", "account", "regions", Map.of("account", bad))));

            assertThat(e.getMessage()).contains("Region 'account' must be [x, y, width, height]");
        }
    }

    @Test
    public void missingParamsAreNamed() {
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("regions", Map.of("a", RECT)))).getMessage())
                .contains("'splitOn'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("splitOn", "a"))).getMessage())
                .contains("'regions'");
    }

    @Test
    public void aPdfWithNoPagesFailsTheStep() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("empty.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.save(input.toFile());
        }
        Job job = TestJobs.newJob(tmp, input);
        SplitPdfStep step = new SplitPdfStep(Map.of("splitOn", "a", "regions", Map.of("a", RECT)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> step.execute(job));

        assertThat(e.getMessage()).contains("no pages");
        assertThat(job.documents()).isEmpty();
    }

    // Pages must not carry the rest of the source file into the output.

    private static SplitPdfStep byAccountNumber() {
        return new SplitPdfStep(Map.of("splitOn", "accountNumber",
                "regions", Map.of("accountNumber", List.of(395, 28, 130, 18))));
    }

    /** True if {@code document} (an output file) has no DPart entries or link annotations. */
    private static void assertSelfContained(Path file) throws Exception {
        try (PDDocument output = Loader.loadPDF(file.toFile())) {
            for (PDPage page : output.getPages()) {
                assertThat(page.getCOSObject().containsKey(COSName.getPDFName("DPart")))
                        .as("page /DPart in " + file.getFileName()).isFalse();
                assertThat(page.getAnnotations())
                        .as("annotations in " + file.getFileName()).isEmpty();
            }
            // A few pages are a few dozen objects; other pages reached through a back-pointer
            // would bring hundreds.
            assertThat(output.getDocument().getXrefTable().size())
                    .as("objects in " + file.getFileName()).isLessThan(60);
        }
    }

    @Test
    public void outputDocumentsDoNotCarryTheDocumentPartTree() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        StatementPdfGenerator.write(new StatementDataGenerator(9).generate(40, 0), input, true);
        Job job = TestJobs.newJob(tmp, input);

        byAccountNumber().execute(job);

        assertThat(job.documents()).hasSize(40);
        for (var document : job.documents()) {
            assertSelfContained(document.file());
        }
    }

    @Test
    public void linkAnnotationsDoNotDragOtherPagesIntoTheOutput() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path plain = tmp.resolve("plain.pdf");
        StatementPdfGenerator.write(new StatementDataGenerator(9).generate(40, 0), plain);
        Path input = tmp.resolve("linked.pdf");
        try (PDDocument document = Loader.loadPDF(plain.toFile())) {
            PDPage last = document.getPage(document.getNumberOfPages() - 1);
            for (PDPage page : document.getPages()) {
                // A link to the last page: through its /Parent it reaches every page of the file.
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(10, 10, 50, 20));
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(last);
                link.setDestination(destination);
                page.setAnnotations(List.of(link));
            }
            document.save(input.toFile());
        }
        Job job = TestJobs.newJob(tmp, input);

        byAccountNumber().execute(job);

        assertThat(job.documents()).hasSize(40);
        for (var document : job.documents()) {
            assertSelfContained(document.file());
        }
    }

    @Test
    public void invalidNewDocumentWhenFailsAtLoad() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("splitOn", "a", "regions", Map.of("a", RECT),
                        "newDocumentWhen", Map.of("property", "a", "operator", "bogus"))));

        assertThat(e.getMessage()).contains("'newDocumentWhen'").contains("bogus");
    }

    @Test
    public void extractorMustBePdfboxOrBuiltin() {
        new SplitPdfStep(Map.of("splitOn", "a", "regions", Map.of("a", RECT), "extractor", "builtin"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("splitOn", "a", "regions", Map.of("a", RECT), "extractor", "ocr")));

        assertThat(e.getMessage()).isEqualTo("extractor must be pdfbox or builtin, not 'ocr'");
    }

    /** A CID font fails the job with the page number, instead of indexing the page with wrong text. */
    @Test
    public void builtinExtractorFailsOnAPageItCantRead() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("cid.pdf");
        Files.write(input, new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R"
                        + " /Resources << /Font << /F1 5 0 R >> >> >>")
                .stream(4, "", "BT /F1 10 Tf 100 700 Td <0041> Tj ET".getBytes(StandardCharsets.US_ASCII))
                .object(5, "<< /Type /Font /Subtype /Type0 /BaseFont /MSGothic /Encoding /Identity-H >>")
                .build("/Root 1 0 R"));
        Job job = TestJobs.newJob(tmp, input);
        SplitPdfStep step = new SplitPdfStep(Map.of("splitOn", "a", "regions", Map.of("a", RECT),
                "extractor", "builtin"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> step.execute(job));

        assertThat(e.getMessage()).startsWith("Page 1: unsupported font MSGothic");
        assertThat(job.documents()).isEmpty();
    }
}
