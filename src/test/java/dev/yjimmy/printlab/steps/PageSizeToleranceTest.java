package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The page-size tolerance (PRD-11 FR-4): a MediaBox within tolerance of Letter passes. */
public class PageSizeToleranceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Job job;

    @Before
    public void setUp() throws Exception {
        Path tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    private String preflightOfLetterWidth(String mediaBox, Map<String, Object> params) throws IOException {
        byte[] bytes = PreflightPdfStepTest.onePage("1.4", mediaBox, "").build("/Root 1 0 R");
        Path file = folder.getRoot().toPath().resolve("doc-" + job.documents().size() + ".pdf");
        Files.write(file, bytes);
        job.documents().clear();
        job.documents().add(new Document(1, file, Map.of("format", "pdf")));
        PreflightPdfStep step = PreflightPdfStepTest.step(params);
        step.execute(job);
        return job.documents().get(0).property("preflight");
    }

    @Test
    public void halfAPointOffLetterPassesWithDefaultTolerance() throws Exception {
        assertThat(preflightOfLetterWidth("[0 0 612.5 792]", Map.of())).isEqualTo("pass");
    }

    @Test
    public void onePointOneOffLetterFailsWithDefaultTolerance() throws Exception {
        assertThat(preflightOfLetterWidth("[0 0 613.1 792]", Map.of())).isEqualTo("fail");
    }

    @Test
    public void toleranceIsAdjustable() throws Exception {
        assertThat(preflightOfLetterWidth("[0 0 612.5 792]", Map.of("tolerance", 0))).isEqualTo("fail");
    }

    @Test
    public void mediaBoxWithReversedCornersUsesItsAbsoluteSize() throws Exception {
        assertThat(preflightOfLetterWidth("[612 792 0 0]", Map.of())).isEqualTo("pass");
    }
}
