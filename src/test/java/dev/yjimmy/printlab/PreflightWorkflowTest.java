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
}
