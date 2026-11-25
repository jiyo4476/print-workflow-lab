package dev.yjimmy.printlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** End to end: generated statements -> statements-preflight.yaml -> every statement checked and routed. */
public class PreflightWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void generatedStatementsPassPreflightAndAreRouted() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(42).generate(10, input);
        Path outputDir = tmp.resolve("output");

        Job job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.load("statements-preflight", outputDir), StepRegistry.withBuiltIns(),
                tmp.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        List<Document> docs = job.documents();
        assertEquals(10, docs.size());
        for (Document d : docs) {
            assertEquals(d.properties().toString(), "pass", d.property("preflight"));
            assertEquals(d.properties().toString(), "", d.property("preflightIssues"));
        }

        List<String> csv = Files.readAllLines(outputDir.resolve(job.id()).resolve("documents.csv"));
        assertTrue(csv.get(0), csv.get(0).contains("preflight"));
        assertTrue(csv.get(0), csv.get(0).contains("preflightIssues"));
    }

    /**
     * An encrypted input can't be split, so preflight never sees it: the job fails at the split
     * with the same plain words preflight uses, and its error.txt says so for the operator.
     */
    @Test
    public void encryptedInputFailsTheJobWithAPlainReason() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(42).generate(2, 0, false,
                new StatementPdfGenerator.Defects(false, true, false), input);
        Path errorDir = tmp.resolve("error");

        Job job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.load("statements-preflight", tmp.resolve("output")),
                StepRegistry.withBuiltIns(), errorDir, TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(JobState.ERROR, job.state());
        String error = Files.readString(errorDir.resolve(job.id()).resolve("error.txt"));
        assertTrue(error, error.contains("Step:   split-and-index"));
        assertTrue(error, error.contains("file is encrypted, so it cannot be printed as supplied"));
        assertTrue(error, !error.contains("password is incorrect"));
    }
}
