package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The same statement run split three ways, from one seed: by the DPart tree in the PDF, by text
 * regions on the page, and by the page groups and TLEs of the AFP file. All three must give the
 * same documents, properties and zip order.
 */
public class IndexParityTest {

    private static final long SEED = 7;
    private static final int STATEMENTS = 30;
    private static final int BAD = 4;
    /** The index all three formats carry, in output order. */
    private static final List<String> INDEX = List.of(
            "accountNumber", "name", "zip", "pageCount", "queue");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void dpartRegionAndAfpWorkflowsGiveTheSameIndexForTheSameSeed() throws Exception {
        // One PDF with a DPart tree serves both PDF workflows: region splitting ignores the tree.
        Path pdf = tmp().resolve("run.pdf");
        new StatementPdfGenerator(SEED).generate(STATEMENTS, BAD, true, pdf);
        Path afp = tmp().resolve("run.afp");
        new StatementAfpGenerator(SEED).generate(STATEMENTS, BAD, afp);

        Job regions = run("statements", "parity01", pdf);
        Job dpart = run("statements-dpart", "parity02", pdf);
        Job afpJob = run("statements-afp", "parity03", afp);

        assertThat(dpart.documents()).hasSize(STATEMENTS);
        assertEquals("DPart index, in zip order", index(afpJob), index(dpart));
        assertEquals("region index, in zip order", index(afpJob), index(regions));
        assertEquals("totalPages", regions.properties().get("totalPages"),
                dpart.properties().get("totalPages"));
        assertThat(dpart.documents()).filteredOn(d -> "exceptions".equals(d.property("queue")))
                .hasSize(BAD);
    }

    private Path tmp() {
        return folder.getRoot().toPath();
    }

    private Job run(String workflow, String jobId, Path input) throws Exception {
        Job job = TestJobs.newJob(jobId, tmp(), input);
        new WorkflowEngine(TestWorkflows.load(workflow, tmp().resolve("output")),
                StepRegistry.withBuiltIns(), tmp().resolve("error"), TickingClock.oneSecondTicks())
                .run(job);
        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        return job;
    }

    private static List<List<String>> index(Job job) {
        return job.documents().stream()
                .map(d -> INDEX.stream().map(d::property).toList())
                .toList();
    }
}
