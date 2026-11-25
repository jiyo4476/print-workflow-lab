package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Config checks, failure modes and property mapping for {@link SplitPdfByDPartStep}. */
public class SplitPdfByDPartStepTest {

    private static final Map<String, String> MAPPING =
            Map.of("AccountNumber", "accountNumber", "Name", "name", "Zip", "zip");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void validConfigIsAccepted() {
        new SplitPdfByDPartStep(Map.of("properties", MAPPING));
    }

    @Test
    public void levelMustBeOneOrMore() {
        assertThatThrownBy(() -> new SplitPdfByDPartStep(Map.of("level", 0, "properties", MAPPING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level must be 1 or more");
    }

    @Test
    public void propertyNamesMustBeText() {
        assertThatThrownBy(() -> new SplitPdfByDPartStep(Map.of("properties", Map.of("Zip", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'Zip' must name a document property");
    }

    @Test
    public void propertiesIsRequired() {
        assertThatThrownBy(() -> new SplitPdfByDPartStep(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("properties");
    }

    @Test
    public void fileWithoutDpartTreeFailsAndSuggestsSplitPdf() throws Exception {
        Path input = write(new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .build("/Root 1 0 R"));

        assertThatThrownBy(() -> run(input, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no DPart tree")
                .hasMessageContaining("SplitPdf");
    }

    @Test
    public void overlappingDocumentsFailTheJob() throws Exception {
        Path input = write(twoPagesWithTree(
                "<< /Start 3 0 R /End 4 0 R >>",
                "<< /Start 4 0 R /End 4 0 R >>"));

        assertThatThrownBy(() -> run(input, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    public void pageInNoDocumentFailsRatherThanBeingDropped() throws Exception {
        Path input = write(twoPagesWithTree("<< /Start 3 0 R /End 3 0 R >>"));

        assertThatThrownBy(() -> run(input, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Page 2 is in no DPart at level 1");
    }

    @Test
    public void splitsIntoDocumentsWithMappedProperties() throws Exception {
        Path input = write(twoPagesWithTree(
                "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111) /Name (AVERY)"
                        + " /Zip (80202) /Extra (ignored) >> >>",
                "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >> >>"));

        Job job = run(input, null);

        assertThat(job.documents()).hasSize(2);
        assertThat(job.documents().get(0).properties())
                .containsEntry("accountNumber", "1111111111")
                .containsEntry("name", "AVERY")
                .containsEntry("zip", "80202")
                .containsEntry("pageCount", "1")
                .containsEntry("format", "pdf")
                .doesNotContainKey("indexError")
                .doesNotContainKey("Extra");
        assertThat(job.documents().get(1).property("accountNumber")).isEqualTo("2222222222");
        assertThat(job.properties())
                .containsEntry("totalPages", "2")
                .containsEntry("documentCount", "2");
        try (PDDocument first = Loader.loadPDF(job.documents().get(0).file().toFile())) {
            assertThat(first.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    public void missingRequiredDpmEntryMarksIndexError() throws Exception {
        Path input = write(twoPagesWithTree(
                "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111) /Name (AVERY) >> >>",
                "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >> >>"));

        Job job = run(input, List.of("accountNumber", "name", "zip"));

        assertThat(job.documents().get(0).property("indexError")).isEqualTo("missing: zip");
        assertThat(job.documents().get(0).property("zip")).isNull();
        assertThat(job.documents().get(1).property("indexError")).isNull();
    }

    @Test
    public void blankDpmEntryMarksIndexError() throws Exception {
        Path input = write(twoPagesWithTree(
                "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111) /Name () /Zip (80202)"
                        + " >> >>",
                "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >> >>"));

        Job job = run(input, List.of("accountNumber", "name", "zip"));

        assertThat(job.documents().get(0).property("indexError")).isEqualTo("missing: name");
    }

    /**
     * A two-page file (objects 3 and 4) whose DPart tree has the given nodes, each as its own
     * object from 11 on, under the root at object 10.
     */
    private static byte[] twoPagesWithTree(String... nodes) {
        PdfBytes pdf = new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R /DPartRoot 10 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .object(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < nodes.length; i++) {
            refs.append(11 + i).append(" 0 R ");
            pdf.object(11 + i, nodes[i]);
        }
        return pdf.object(10, "<< /DParts [" + refs + "] >>").build("/Root 1 0 R");
    }

    private Path write(byte[] bytes) throws Exception {
        return Files.write(folder.newFile().toPath(), bytes);
    }

    /** Runs the step on {@code input}; {@code requiredProperties} may be null. */
    private Job run(Path input, List<String> requiredProperties) throws Exception {
        Job job = TestJobs.newJob(folder.getRoot().toPath(), input);
        Map<String, Object> params = requiredProperties == null
                ? Map.of("properties", MAPPING)
                : Map.of("properties", MAPPING, "requiredProperties", requiredProperties);
        new SplitPdfByDPartStep(params).execute(job);
        return job;
    }
}
