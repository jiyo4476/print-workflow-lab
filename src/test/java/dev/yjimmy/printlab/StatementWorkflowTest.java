package dev.yjimmy.printlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
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

/** End to end: generated print run -> the real statements.yaml workflow -> sorted output. */
public class StatementWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void splitsIndexesAndSortsAStatementRun() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(42).generate(25, input);
        Path outputDir = tmp.resolve("output");

        Job job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.load("statements", outputDir), StepRegistry.withBuiltIns())
                .run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        List<Document> docs = job.documents();
        assertEquals(25, docs.size());

        int pages = 0;
        for (Document d : docs) {
            assertTrue(d.properties().toString(), d.property("accountNumber").matches("\\d{10}"));
            assertTrue(d.properties().toString(), d.property("zip").matches("\\d{5}"));
            assertTrue(d.property("cityStateZip").endsWith(d.property("zip")));
            assertTrue(!d.property("name").isBlank());
            pages += Integer.parseInt(d.property("pageCount"));
        }
        assertEquals(Integer.parseInt(job.properties().get("totalPages")), pages);

        for (int i = 1; i < docs.size(); i++) {
            assertTrue("documents should be presorted by zip",
                    docs.get(i - 1).property("zip").compareTo(docs.get(i).property("zip")) <= 0);
        }

        Path jobOut = outputDir.resolve(job.id());
        List<String> csv = Files.readAllLines(jobOut.resolve("documents.csv"));
        assertEquals(26, csv.size());
        assertTrue(Files.exists(jobOut.resolve("00025.pdf")));
    }
}
