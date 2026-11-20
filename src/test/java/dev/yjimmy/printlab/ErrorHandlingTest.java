package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.ErrorFolder;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The real statements workflow on input that fails: a PDF with a blank page has no account
 * number, so {@code split-and-index} throws after PDFBox has opened the file. That also checks the
 * file was closed before the move (on Windows, an open file can't be moved).
 */
public class ErrorHandlingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tmp;
    private Path errorDir;
    private WorkflowEngine engine;

    @Before
    public void setUp() throws Exception {
        tmp = folder.getRoot().toPath();
        errorDir = tmp.resolve("error");
        engine = new WorkflowEngine(TestWorkflows.load("statements", tmp.resolve("output")),
                StepRegistry.withBuiltIns(), errorDir, TickingClock.oneSecondTicks());
    }

    /** A one-page PDF with nothing on it, in its own folder so names can repeat. */
    private Path blankPdf(String folderName, String fileName) throws Exception {
        return blankPdf(folderName, fileName, 1);
    }

    private Path blankPdf(String folderName, String fileName, int pages) throws Exception {
        Path file = Files.createDirectories(tmp.resolve(folderName)).resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private Job runFailingJob(String id, Path input) throws Exception {
        Job job = TestJobs.newJob(id, tmp, input);
        engine.run(job);
        assertEquals(JobState.ERROR, job.state());
        return job;
    }

    @Test
    public void failedJobMovesInputToErrorFolder() throws Exception {
        Path input = blankPdf("in", "run.pdf");

        runFailingJob("job00001", input);

        assertFalse("input should no longer be where it was", Files.exists(input));
        assertTrue(Files.isRegularFile(errorDir.resolve("job00001").resolve("run.pdf")));
    }

    @Test
    public void errorTxtContainsStepNameAndMessage() throws Exception {
        Job job = runFailingJob("job00002", blankPdf("in", "run.pdf"));

        String text = Files.readString(
                errorDir.resolve(job.id()).resolve(ErrorFolder.ERROR_FILE_NAME));

        assertThat(text)
                .contains("Job:    job00002")
                .contains("Input:  run.pdf")
                .contains("Phase:  prepare\nStep:   split-and-index")
                .contains("No 'accountNumber' found on page 1")
                // set-job-props runs first (t0, t0 + 1s); split-and-index starts at t0 + 2s and
                // fails at t0 + 3s.
                .contains("Time:   2026-01-01T00:00:03Z")
                .contains("Work:   " + job.workDir().toAbsolutePath())
                .doesNotContain("Note:");
    }

    @Test
    public void successfulJobLeavesNothingInErrorFolder() throws Exception {
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(7).generate(3, input);

        Job job = TestJobs.newJob(tmp, input);
        engine.run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        assertFalse("error folder should not even be created", Files.exists(errorDir));
    }

    @Test
    public void twoFailedJobsWithSameFileNameDoNotCollide() throws Exception {
        // Same name, different contents (1 page vs 2), so a mix-up would show in the bytes.
        Path first = blankPdf("a", "run.pdf", 1);
        Path second = blankPdf("b", "run.pdf", 2);
        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);

        runFailingJob("job0000a", first);
        runFailingJob("job0000b", second);

        assertThat(errorDir.resolve("job0000a").resolve("run.pdf")).hasBinaryContent(firstBytes);
        assertThat(errorDir.resolve("job0000b").resolve("run.pdf")).hasBinaryContent(secondBytes);
        assertThat(Files.readString(errorDir.resolve("job0000a").resolve("error.txt")))
                .contains("Job:    job0000a");
        assertThat(Files.readString(errorDir.resolve("job0000b").resolve("error.txt")))
                .contains("Job:    job0000b");
    }

    @Test
    public void errorTxtSaysWhereInputWasLeftWhenItCannotBeMoved() throws Exception {
        Path input = blankPdf("in", "run.pdf");
        // Pretend a failed job's error folder already holds a file of the same name.
        Files.createDirectories(errorDir.resolve("job00003"));
        Files.writeString(errorDir.resolve("job00003").resolve("run.pdf"), "already here");

        runFailingJob("job00003", input);

        assertTrue("input stays where it was", Files.exists(input));
        assertThat(Files.readString(errorDir.resolve("job00003").resolve("error.txt")))
                .contains("Note:   input file could not be moved here")
                .contains(input.toAbsolutePath().toString());
    }
}
