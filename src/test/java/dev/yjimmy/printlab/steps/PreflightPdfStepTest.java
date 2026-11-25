package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PreflightPdfStepTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Job job;

    @Before
    public void setUp() throws Exception {
        Path tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    /** A catalog, a page tree and one page with {@code pageEntries}, all in one file. */
    static PdfBytes onePage(String version, String mediaBox, String pageEntries) {
        return new PdfBytes(version)
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox " + mediaBox + " " + pageEntries + " >>");
    }

    static byte[] letter(String pageEntries) {
        return onePage("1.4", "[0 0 612 792]", pageEntries).build("/Root 1 0 R");
    }

    /** Object 4 is an Arial font with no font file; the page's /Font resources name it /F1. */
    static final String NON_EMBEDDED_ARIAL = "/Resources << /Font << /F1 4 0 R >> >>";

    static PdfBytes withNonEmbeddedArial(PdfBytes file, String baseFont) {
        return file.object(4, "<< /Type /Font /Subtype /TrueType /BaseFont /" + baseFont
                + " /FontDescriptor 5 0 R >>")
                .object(5, "<< /Type /FontDescriptor /FontName /" + baseFont + " >>");
    }

    static PdfBytes withEmbeddedArial(PdfBytes file) {
        return file.object(4, "<< /Type /Font /Subtype /TrueType /BaseFont /Arial /FontDescriptor 5 0 R >>")
                .object(5, "<< /Type /FontDescriptor /FontName /Arial /FontFile2 6 0 R >>")
                .stream(6, "/Length1 0", new byte[0]);
    }

    private Document addPdf(byte[] bytes) throws IOException {
        int sequence = job.documents().size() + 1;
        Path file = folder.getRoot().toPath().resolve("doc-" + sequence + ".pdf");
        Files.write(file, bytes);
        Document document = new Document(sequence, file, Map.of("format", "pdf"));
        job.documents().add(document);
        return document;
    }

    static PreflightPdfStep step(Map<String, Object> params) {
        return new PreflightPdfStep(new HashMap<>(params));
    }

    private static PreflightPdfStep defaults() {
        return step(Map.of());
    }

    private void run(PreflightPdfStep step) throws Exception {
        step.execute(job);
    }

    private Document only() {
        assertThat(job.documents()).hasSize(1);
        return job.documents().get(0);
    }

    // --- Each check: a passing and a failing case -----------------------------------------

    @Test
    public void barePageWithNoFontsPasses() throws Exception {
        addPdf(letter(""));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("pass");
        assertThat(only().property("preflightIssues")).isEmpty();
    }

    @Test
    public void encryptedFileFails() throws Exception {
        addPdf(onePage("1.4", "[0 0 612 792]", "").build("/Root 1 0 R /Encrypt 9 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues"))
                .isEqualTo("file is encrypted, so it cannot be printed as supplied");
    }

    @Test
    public void a4PageFailsLetterOnlyCheck() throws Exception {
        addPdf(onePage("1.4", "[0 0 595 842]", "").build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues"))
                .isEqualTo("page 1: page size 595.0 x 842.0 pt is not allowed (letter)");
    }

    @Test
    public void a4PagePassesWhenA4IsAllowed() throws Exception {
        addPdf(onePage("1.4", "[0 0 595 842]", "").build("/Root 1 0 R"));
        run(step(Map.of("pageSizes", List.of("letter", "a4"))));
        assertThat(only().property("preflight")).isEqualTo("pass");
    }

    @Test
    public void nonEmbeddedFontFails() throws Exception {
        addPdf(withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]", NON_EMBEDDED_ARIAL), "Arial")
                .build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: font Arial not embedded");
    }

    @Test
    public void embeddedFontPasses() throws Exception {
        addPdf(withEmbeddedArial(onePage("1.4", "[0 0 612 792]", NON_EMBEDDED_ARIAL))
                .build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("pass");
    }

    @Test
    public void standard14AllowedWhenConfigured() throws Exception {
        addPdf(withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]", NON_EMBEDDED_ARIAL), "Helvetica")
                .build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: font Helvetica not embedded");

        // The same file passes once Standard 14 fonts are allowed.
        job.documents().clear();
        addPdf(withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]", NON_EMBEDDED_ARIAL), "Helvetica")
                .build("/Root 1 0 R"));
        run(step(Map.of("allowStandard14", true)));
        assertThat(only().property("preflight")).isEqualTo("pass");
    }

    @Test
    public void subsetPrefixDoesNotHideANonEmbeddedFont() throws Exception {
        addPdf(withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]", NON_EMBEDDED_ARIAL), "ABCDEF+Arial")
                .build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: font Arial not embedded");
    }

    @Test
    public void versionAboveMaximumFails() throws Exception {
        addPdf(onePage("2.0", "[0 0 612 792]", "").build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues"))
                .isEqualTo("PDF version 2.0 is above the allowed 1.7");
    }

    @Test
    public void versionAtMaximumPasses() throws Exception {
        addPdf(onePage("1.7", "[0 0 612 792]", "").build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("pass");
    }

    @Test
    public void rotatedPageFailsUnlessAllowed() throws Exception {
        addPdf(onePage("1.4", "[0 0 612 792]", "/Rotate 90").build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: page is rotated 90 degrees");

        job.documents().clear();
        addPdf(onePage("1.4", "[0 0 612 792]", "/Rotate 90").build("/Root 1 0 R"));
        run(step(Map.of("allowRotate", true)));
        assertThat(only().property("preflight")).isEqualTo("pass");
    }

    @Test
    public void rotationThatIsNotAMultipleOf90AlwaysFails() throws Exception {
        addPdf(onePage("1.4", "[0 0 612 792]", "/Rotate 45").build("/Root 1 0 R"));
        run(step(Map.of("allowRotate", true)));
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: /Rotate 45 is not a multiple of 90");
    }

    // --- Form XObjects ---------------------------------------------------------------------

    private static final String FORM_WITH_ARIAL = "/Type /XObject /Subtype /Form /BBox [0 0 1 1] "
            + "/Resources << /Font << /F2 4 0 R >> >>";

    static PdfBytes withArialFormOnPage(PdfBytes file, String formDictionary) {
        return file.stream(7, formDictionary, new byte[0]);
    }

    @Test
    public void nonEmbeddedFontInFormXObjectFails() throws Exception {
        PdfBytes file = withArialFormOnPage(
                withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]",
                        "/Resources << /XObject << /X1 7 0 R >> >>"), "Arial"),
                FORM_WITH_ARIAL);
        addPdf(file.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: font Arial not embedded");
    }

    @Test
    public void embeddedFontInFormXObjectPasses() throws Exception {
        PdfBytes file = withArialFormOnPage(
                withEmbeddedArial(onePage("1.4", "[0 0 612 792]",
                        "/Resources << /XObject << /X1 7 0 R >> >>")),
                FORM_WITH_ARIAL);
        addPdf(file.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("pass");
    }

    @Test
    public void fontInNestedFormXObjectIsChecked() throws Exception {
        // Page -> form 7 -> form 8 -> font: the font is two forms down.
        PdfBytes file = withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]",
                "/Resources << /XObject << /X1 7 0 R >> >>"), "Arial")
                .stream(7, "/Type /XObject /Subtype /Form /BBox [0 0 1 1] "
                        + "/Resources << /XObject << /X2 8 0 R >> >>", new byte[0])
                .stream(8, FORM_WITH_ARIAL, new byte[0]);
        addPdf(file.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: font Arial not embedded");
    }

    @Test
    public void formXObjectCycleDoesNotLoop() throws Exception {
        // Form 7 draws itself, and also draws a non-embedded font. The walk must finish.
        PdfBytes file = withNonEmbeddedArial(onePage("1.4", "[0 0 612 792]",
                "/Resources << /XObject << /X1 7 0 R >> >>"), "Arial")
                .stream(7, "/Type /XObject /Subtype /Form /BBox [0 0 1 1] "
                        + "/Resources << /XObject << /Self 7 0 R >> /Font << /F2 4 0 R >> >>", new byte[0]);
        addPdf(file.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues")).isEqualTo("page 1: font Arial not embedded");
    }

    @Test
    public void formNestingLimitFailsWithPlainMessage() throws Exception {
        // A chain of 20 forms, each drawing the next: deeper than the 16 levels allowed.
        PdfBytes chain = new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        + "/Resources << /XObject << /X1 10 0 R >> >> >>");
        for (int i = 0; i < 20; i++) {
            int next = 11 + i;
            chain.stream(10 + i, "/Type /XObject /Subtype /Form /BBox [0 0 1 1] "
                    + "/Resources << /XObject << /N " + next + " 0 R >> >>", new byte[0]);
        }
        addPdf(chain.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues"))
                .isEqualTo("page 1: forms are nested deeper than 16 levels");
    }

    // --- Output and failure handling --------------------------------------------------------

    @Test
    public void issuesListNamesPageAndFont() throws Exception {
        PdfBytes file = new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R 8 0 R] /Count 2 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " + NON_EMBEDDED_ARIAL + " >>")
                .object(8, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F3 9 0 R >> >> >>")
                .object(9, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>");
        file = withNonEmbeddedArial(file, "Arial");
        addPdf(file.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues")).isEqualTo(
                "page 1: font Arial not embedded; page 2: font Courier not embedded");
    }

    @Test
    public void tooManyIssuesAreSummarised() throws Exception {
        StringBuilder kids = new StringBuilder();
        StringBuilder objects = new StringBuilder();
        PdfBytes file = new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>");
        int count = 25;
        for (int i = 0; i < count; i++) {
            kids.append(10 + i).append(" 0 R ");
        }
        file.object(2, "<< /Type /Pages /Kids [" + kids + "] /Count " + count + " >>");
        for (int i = 0; i < count; i++) {
            file.object(10 + i, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>");
        }
        addPdf(file.build("/Root 1 0 R"));
        run(defaults());
        assertThat(only().property("preflightIssues")).endsWith("; and 5 more issues");
    }

    @Test
    public void internalErrorMarksDocumentWithoutCrashingJob() throws Exception {
        addPdf("not a pdf at all".getBytes(StandardCharsets.US_ASCII));
        run(defaults());
        assertThat(only().property("preflight")).isEqualTo("fail");
        assertThat(only().property("preflightIssues")).startsWith("could not check this file: ");
    }

    @Test
    public void failJobStopsAtTheFirstFailingDocument() throws Exception {
        addPdf(letter(""));
        addPdf(onePage("1.4", "[0 0 595 842]", "").build("/Root 1 0 R"));
        addPdf(letter(""));
        PreflightPdfStep failing = step(Map.of("failJob", true));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> run(failing));
        assertThat(e.getMessage()).isEqualTo(
                "document 2 failed preflight: page 1: page size 595.0 x 842.0 pt is not allowed (letter)");
        assertThat(job.documents().get(2).property("preflight")).isNull();
    }

    @Test
    public void afpDocumentsAreLeftAlone() throws Exception {
        Path afp = folder.newFile("doc.afp").toPath();
        Files.write(afp, new byte[] {1, 2, 3});
        job.documents().add(new Document(1, afp, Map.of("format", "afp")));
        run(defaults());
        assertThat(job.documents().get(0).property("preflight")).isNull();
    }
}
