package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.steps.PreflightPdfStep;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator.Defects;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The generator's deliberate defects (PRD-11 FR-8) are the ones the preflight step reports. */
public class PreflightDefectsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Runs preflight over a 2-statement file written with {@code defects}. */
    private Document preflight(Defects defects, Map<String, Object> params) throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path file = tmp.resolve("defects.pdf");
        List<dev.yjimmy.printlab.testdata.Statement> statements =
                new StatementDataGenerator(42).generate(2, 0);
        StatementPdfGenerator.write(statements, file, false, defects);
        Job job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
        job.documents().add(new Document(1, file, Map.of("format", "pdf"), 1));
        new PreflightPdfStep(params).execute(job);
        return job.documents().get(0);
    }

    @Test
    public void noDefectsPassesTheStrictDefault() throws Exception {
        // The generator embeds Liberation Sans, so no Standard 14 allowance is needed.
        Document d = preflight(Defects.NONE, Map.of());
        assertThat(d.property("preflight")).isEqualTo("pass");
        assertThat(d.property("preflightIssues")).isEmpty();
    }

    @Test
    public void a4PagesFailTheLetterCheck() throws Exception {
        Document d = preflight(new Defects(true, false, false), Map.of());
        assertThat(d.property("preflight")).isEqualTo("fail");
        // PDFBox's A4 box is 595.3 x 841.9 pt, within tolerance of A4 but not of Letter.
        assertThat(d.property("preflightIssues"))
                .startsWith("page 1: page size 595.3 x 841.9 pt is not allowed (letter)");
    }

    @Test
    public void a4PagesPassWhenA4IsAllowed() throws Exception {
        Document d = preflight(new Defects(true, false, false), Map.of("pageSizes", List.of("a4")));
        assertThat(d.property("preflight")).isEqualTo("pass");
    }

    @Test
    public void encryptedFileFails() throws Exception {
        Document d = preflight(new Defects(false, true, false), Map.of());
        assertThat(d.property("preflight")).isEqualTo("fail");
        assertThat(d.property("preflightIssues"))
                .isEqualTo("file is encrypted, so it cannot be printed as supplied");
    }

    @Test
    public void textInAFormXObjectIsCheckedThroughTheForm() throws Exception {
        Document d = preflight(new Defects(false, false, true), Map.of());
        assertThat(d.property("preflight")).isEqualTo("fail");
        // The page itself has no fonts: both are only in the form's resources.
        assertThat(d.property("preflightIssues"))
                .contains("page 1: font LiberationSans not embedded")
                .contains("page 1: font LiberationSans-Bold not embedded");
    }

    @Test
    public void formFontDefectFailsEvenWhenStandard14IsAllowed() throws Exception {
        // Liberation Sans isn't a Standard 14 font, so the allowance doesn't excuse it.
        Document d = preflight(new Defects(false, false, true), Map.of("allowStandard14", true));
        assertThat(d.property("preflight")).isEqualTo("fail");
    }
}
