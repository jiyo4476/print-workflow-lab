package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.yjimmy.printlab.testdata.Statement;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Config checks, failure modes, levels, repair and property mapping for the step. */
public class SplitPdfByDPartStepTest {

    private static final Map<String, String> MAPPING =
            Map.of("AccountNumber", "accountNumber", "Name", "name", "Zip", "zip");
    private static final List<String> REQUIRED = List.of("accountNumber", "name", "zip");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // Config.

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
    public void propertiesCannotUseNamesTheStepSetsItself() {
        for (String reserved : List.of("pageCount", "format", "indexError")) {
            assertThatThrownBy(() -> new SplitPdfByDPartStep(Map.of("properties",
                    Map.of("Zip", reserved))))
                    .as(reserved)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'Zip' maps to '" + reserved + "'")
                    .hasMessageContaining("sets itself");
        }
    }

    @Test
    public void twoDpmKeysCannotMapToOneProperty() {
        assertThatThrownBy(() -> new SplitPdfByDPartStep(Map.of("properties",
                Map.of("Zip", "zip", "PostalCode", "zip"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both map to 'zip'");
    }

    @Test
    public void requiredPropertiesMustBeOnesTheStepSets() {
        assertThatThrownBy(() -> new SplitPdfByDPartStep(Map.of("properties", MAPPING,
                "requiredProperties", List.of("accountNumber", "street"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'street' isn't one of the properties this step sets");
    }

    // Failure modes.

    @Test
    public void fileWithoutDpartTreeFailsAndSuggestsSplitPdf() throws Exception {
        Path input = write(new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .build("/Root 1 0 R"));

        assertThatThrownBy(() -> run(input, params(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no DPart tree")
                .hasMessageContaining("SplitPdf");
    }

    @Test
    public void fileWithNoPagesFails() throws Exception {
        Path input = write(new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .build("/Root 1 0 R"));

        assertThatThrownBy(() -> run(input, params(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pages");
    }

    @Test
    public void overlappingDocumentsFailTheJob() throws Exception {
        Path input = write(flat(2,
                "<< /Start 3 0 R /End 4 0 R >>",
                "<< /Start 4 0 R /End 4 0 R >>"));

        assertThatThrownBy(() -> run(input, params(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    public void pageInNoDocumentFailsRatherThanBeingDropped() throws Exception {
        Path input = write(flat(2, "<< /Start 3 0 R /End 3 0 R >>"));

        assertThatThrownBy(() -> run(input, params(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Page 2 is in no DPart at level 1");
    }

    @Test
    public void leafAboveTheLevelFailsTheJob() throws Exception {
        Path input = write(flat(2, "<< /Start 3 0 R /End 4 0 R >>"));

        assertThatThrownBy(() -> run(input, params(2, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no children")
                .hasMessageContaining("level 2");
    }

    // Splitting and properties.

    @Test
    public void splitsIntoDocumentsWithMappedProperties() throws Exception {
        Path input = write(flat(2,
                "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111) /Name (AVERY)"
                        + " /Zip (80202) /Extra (ignored) >> >>",
                "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >> >>"));

        Job job = run(input, params(null, null));

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
        assertThat(widths(job.documents().get(0))).containsExactly(101.0f);
    }

    @Test
    public void multiPageRangesGetExactlyTheirPages() throws Exception {
        Path input = write(flat(5,
                "<< /Start 3 0 R /End 5 0 R /DPM << /AccountNumber (1111111111) >> >>",
                "<< /Start 6 0 R /End 7 0 R /DPM << /AccountNumber (2222222222) >> >>"));

        Job job = run(input, params(null, null));

        // Page n is 100 + n points wide, so the widths say which pages each document holds.
        assertThat(widths(job.documents().get(0))).containsExactly(101.0f, 102.0f, 103.0f);
        assertThat(widths(job.documents().get(1))).containsExactly(104.0f, 105.0f);
        assertThat(job.documents().get(0).property("pageCount")).isEqualTo("3");
        assertThat(job.documents().get(1).property("pageCount")).isEqualTo("2");
        assertThat(job.documents().get(0).file().getFileName().toString()).isEqualTo("doc-00001.pdf");
        assertThat(job.documents().get(1).file().getFileName().toString()).isEqualTo("doc-00002.pdf");
    }

    @Test
    public void documentsComeOutInPageOrderWhateverTheNodeOrder() throws Exception {
        // The node for pages 3-5 is listed before the node for pages 1-2.
        Path input = write(flat(5,
                "<< /Start 5 0 R /End 7 0 R /DPM << /AccountNumber (2222222222) >> >>",
                "<< /Start 3 0 R /End 4 0 R /DPM << /AccountNumber (1111111111) >> >>"));

        Job job = run(input, params(null, null));

        assertThat(job.documents()).extracting(d -> d.property("accountNumber"))
                .containsExactly("1111111111", "2222222222");
        assertThat(widths(job.documents().get(0))).containsExactly(101.0f, 102.0f);
    }

    @Test
    public void levelTwoSplitsTheLeavesOfATwoLevelTree() throws Exception {
        Job job = run(write(twoLevel("")), params(2, null));

        assertThat(job.documents()).extracting(d -> d.property("accountNumber"))
                .containsExactly("1111111111", "2222222222", "3333333333");
        assertThat(job.documents()).extracting(d -> d.property("pageCount"))
                .containsExactly("2", "1", "2");
        assertThat(widths(job.documents().get(2))).containsExactly(104.0f, 105.0f);
    }

    @Test
    public void levelOneSplitsTheBatchesWithRangesDerivedFromTheirChildren() throws Exception {
        Job job = run(write(twoLevel("")), params(1, null));

        assertThat(job.documents()).extracting(d -> d.property("pageCount")).containsExactly("3", "2");
        assertThat(widths(job.documents().get(0))).containsExactly(101.0f, 102.0f, 103.0f);
        // A batch has no DPM entries of its own.
        assertThat(job.documents().get(0).properties()).doesNotContainKey("accountNumber");
    }

    @Test
    public void levelDefaultsToTheFilesRecordLevelAndTheConfigOverridesIt() throws Exception {
        Path input = write(twoLevel("/RecordLevel 2"));

        assertThat(run(input, params(null, null)).documents()).hasSize(3);
        assertThat(run(input, params(1, null)).documents()).hasSize(2);
    }

    @Test
    public void levelDefaultsToOneWhenTheFileHasNoRecordLevel() throws Exception {
        assertThat(run(write(twoLevel("")), params(null, null)).documents()).hasSize(2);
    }

    // Index errors.

    @Test
    public void missingRequiredDpmKeyMarksIndexError() throws Exception {
        Path input = write(flat(2,
                "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111) /Name (AVERY) >> >>",
                "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >> >>"));

        Job job = run(input, params(null, REQUIRED));

        assertThat(job.documents().get(0).property("indexError")).isEqualTo("missing: zip");
        assertThat(job.documents().get(0).property("zip")).isNull();
        assertThat(job.documents().get(1).property("indexError")).isNull();
    }

    @Test
    public void blankDpmEntryMarksIndexError() throws Exception {
        Path input = write(flat(2,
                "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111) /Name () /Zip (80202)"
                        + " >> >>",
                "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222) /Name (BLAKE)"
                        + " /Zip (10001) >> >>"));

        Job job = run(input, params(null, REQUIRED));

        assertThat(job.documents().get(0).property("indexError")).isEqualTo("missing: name");
    }

    // Files the project's own reader can't read.

    @Test
    public void fileEncryptedWithAnEmptyUserPasswordIsDecryptedByPdfBox() throws Exception {
        List<Statement> statements = new StatementDataGenerator(5).generate(6, 0);
        Path plain = folder.getRoot().toPath().resolve("plain.pdf");
        StatementPdfGenerator.write(statements, plain, true);
        Path input = protect(plain, "");

        Job job = run(input, params(null, REQUIRED));

        assertThat(job.documents()).extracting(d -> d.property("accountNumber"))
                .containsExactlyElementsOf(statements.stream().map(Statement::accountNumber).toList());
        try (PDDocument first = Loader.loadPDF(job.documents().get(0).file().toFile())) {
            assertThat(first.isEncrypted()).isFalse();
        }
        assertThat(job.workDir().resolve("normalized.pdf")).exists();
    }

    @Test
    public void fileThatNeedsAPasswordFailsWithAClearMessage() throws Exception {
        Path plain = folder.getRoot().toPath().resolve("plain.pdf");
        StatementPdfGenerator.write(new StatementDataGenerator(5).generate(3, 0), plain, true);
        Path input = protect(plain, "secret");

        assertThatThrownBy(() -> run(input, params(null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a password");
    }

    @Test
    public void fileWithItsCrossReferenceDataCutOffIsRepairedByPdfBox() throws Exception {
        List<Statement> statements = new StatementDataGenerator(5).generate(200, 0);
        Path plain = folder.getRoot().toPath().resolve("plain.pdf");
        StatementPdfGenerator.write(statements, plain, true);
        byte[] bytes = Files.readAllBytes(plain);
        Path input = folder.getRoot().toPath().resolve("damaged.pdf");
        Files.write(input, Arrays.copyOf(bytes, bytes.length - 600));

        Job job = run(input, params(null, REQUIRED));

        assertThat(job.documents()).hasSize(200);
        assertThat(job.documents().get(199).property("accountNumber"))
                .isEqualTo(statements.get(199).accountNumber());
        assertThat(job.workDir().resolve("normalized.pdf")).exists();
    }

    @Test
    public void fileTheReaderCanReadIsNotCopied() throws Exception {
        Job job = run(write(flat(2, "<< /Start 3 0 R /End 4 0 R >>")), params(null, null));

        assertThat(job.workDir().resolve("normalized.pdf")).doesNotExist();
    }

    // Helpers.

    /**
     * A file of {@code pages} pages (objects 3 and up), where page n is {@code 100 + n} points
     * wide so an output document's page widths say which pages it holds. The catalog points at a
     * DPart root at object 10, which the caller writes.
     */
    private static PdfBytes base(int pages) {
        StringBuilder kids = new StringBuilder();
        for (int page = 1; page <= pages; page++) {
            kids.append(2 + page).append(" 0 R ");
        }
        PdfBytes pdf = new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R /DPartRoot 10 0 R >>")
                .object(2, "<< /Type /Pages /Kids [" + kids + "] /Count " + pages + " >>");
        for (int page = 1; page <= pages; page++) {
            pdf.object(2 + page, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (100 + page)
                    + " 200] >>");
        }
        return pdf;
    }

    /** A flat tree on the root dictionary itself (the form earlier versions wrote), one node each. */
    private static byte[] flat(int pages, String... nodes) {
        PdfBytes pdf = base(pages);
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < nodes.length; i++) {
            refs.append(11 + i).append(" 0 R ");
            pdf.object(11 + i, nodes[i]);
        }
        return pdf.object(10, "<< /DParts [" + refs + "] >>").build("/Root 1 0 R");
    }

    /**
     * A PDF 2.0 shaped tree over five pages: batch A (pages 1-3: statements on pages 1-2 and 3)
     * and batch B (pages 4-5: one statement). The batches have no /Start or /End of their own.
     * {@code rootEntries} go in the DPartRoot dictionary.
     */
    private static byte[] twoLevel(String rootEntries) {
        return base(5)
                .object(10, "<< /Type /DPartRoot /DPartRootNode 11 0 R " + rootEntries + " >>")
                .object(11, "<< /Type /DPart /Parent 10 0 R /DParts [[12 0 R 15 0 R]] >>")
                .object(12, "<< /Type /DPart /Parent 11 0 R /DParts [[13 0 R 14 0 R]] >>")
                .object(13, "<< /Type /DPart /Parent 12 0 R /Start 3 0 R /End 4 0 R"
                        + " /DPM << /AccountNumber (1111111111) /Name (AVERY) /Zip (80202) >> >>")
                .object(14, "<< /Type /DPart /Parent 12 0 R /Start 5 0 R /End 5 0 R"
                        + " /DPM << /AccountNumber (2222222222) /Name (BLAKE) /Zip (10001) >> >>")
                .object(15, "<< /Type /DPart /Parent 11 0 R /DParts [[16 0 R]] >>")
                .object(16, "<< /Type /DPart /Parent 15 0 R /Start 6 0 R /End 7 0 R"
                        + " /DPM << /AccountNumber (3333333333) /Name (CASEY) /Zip (20001) >> >>")
                .build("/Root 1 0 R");
    }

    /** Saves {@code source} encrypted (AES-256) with the given user password. */
    private Path protect(Path source, String userPassword) throws Exception {
        Path out = folder.getRoot().toPath().resolve("protected.pdf");
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", userPassword,
                    new AccessPermission());
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            document.save(out.toFile());
        }
        return out;
    }

    /** The widths of the pages in an output document, in order. */
    private static List<Float> widths(Document document) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(document.file().toFile())) {
            List<Float> widths = new ArrayList<>();
            pdf.getPages().forEach(page -> widths.add(page.getMediaBox().getWidth()));
            return widths;
        }
    }

    private Path write(byte[] bytes) throws Exception {
        return Files.write(folder.newFile().toPath(), bytes);
    }

    /** Step params: {@code level} and {@code requiredProperties} are left out when null. */
    private static Map<String, Object> params(Integer level, List<String> requiredProperties) {
        Map<String, Object> params = new HashMap<>();
        params.put("properties", MAPPING);
        if (level != null) {
            params.put("level", level);
        }
        if (requiredProperties != null) {
            params.put("requiredProperties", requiredProperties);
        }
        return params;
    }

    private Job run(Path input, Map<String, Object> params) throws Exception {
        // A fresh job (and work folder) per run, so one test can run the step more than once.
        Job job = TestJobs.newJob("job" + System.nanoTime(), folder.getRoot().toPath(), input);
        new SplitPdfByDPartStep(params).execute(job);
        return job;
    }
}
