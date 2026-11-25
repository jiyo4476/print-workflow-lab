package dev.yjimmy.printlab.steps;

import org.junit.rules.TemporaryFolder;
import org.junit.Rule;
import org.apache.pdfbox.pdmodel.PDDocument;
import java.nio.file.Path;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.testsupport.TestJobs;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
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

    @Test
    public void aFileTheHeapCannotHoldFailsBeforeAnythingIsRead() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("small.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(input.toFile());
        }
        Job job = TestJobs.newJob(tmp, input);
        // Pretend the JVM was started with 1 MB of heap.
        SplitPdfStep step = new SplitPdfStep(Map.of("splitOn", "a", "regions", Map.of("a", RECT)),
                () -> 1024 * 1024);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> step.execute(job));

        // The 24 MB base plus a few KB of file, rounded up.
        assertThat(e.getMessage()).contains("SplitPdf needs at least about 25 MB")
                .contains("limit is 1 MB").contains("-Xmx");
        assertThat(job.properties()).doesNotContainKey("totalPages");
        assertThat(job.documents()).isEmpty();
    }

    @Test
    public void recordsTheTotalSizeOfTheDocuments() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(7).generate(3, input);
        Job job = TestJobs.newJob(tmp, input);
        SplitPdfStep step = new SplitPdfStep(Map.of("splitOn", "accountNumber",
                "regions", Map.of("accountNumber", List.of(395, 28, 130, 18))));

        step.execute(job);

        assertThat(job.documents()).hasSize(3);
        long total = 0;
        for (var document : job.documents()) {
            total += Files.size(document.file());
        }
        assertThat(job.properties()).containsEntry("documentBytes", String.valueOf(total));
        assertThat(total).isPositive();
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
