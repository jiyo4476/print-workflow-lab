package dev.yjimmy.printlab.store;

import static dev.yjimmy.printlab.testsupport.TestWorkflows.step;
import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.RecordingStep;
import dev.yjimmy.printlab.testsupport.TestDatabases;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepOutcome;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PersistentWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Database db;
    private Path tmp;
    private Path errorDir;
    private StepRegistry registry;

    @Before
    public void setUp() throws Exception {
        db = TestDatabases.fresh();
        tmp = folder.getRoot().toPath();
        errorDir = tmp.resolve("error");
        registry = new StepRegistry();
    }

    private Job newJob(String id) throws Exception {
        Path input = Files.writeString(tmp.resolve(id + "-in.pdf"), "not really a pdf");
        Job job = TestJobs.newJob(id, tmp, input);
        job.documents().add(new Document(1, tmp.resolve(id + "-1.afp"),
                Map.of("accountNumber", "1234567890", "queue", "statements"), 1));
        return job;
    }

    @Test
    public void completedJobIsReadableAfterEngineRestart() throws Exception {
        registry.register("Ok", new RecordingStep("Ok").factory());
        WorkflowDefinition workflow = TestWorkflows.of(step("a", "Ok"), step("b", "Ok"));
        Job job = newJob("job1");
        new WorkflowEngine(workflow, registry, errorDir, TickingClock.oneSecondTicks(),
                new JdbcJobStore(db, TickingClock.oneSecondTicks())).run(job);

        // A restart: a new engine and a new store, on the same database.
        JdbcJobStore afterRestart = new JdbcJobStore(db, TickingClock.oneSecondTicks());
        new WorkflowEngine(workflow, registry, errorDir, TickingClock.oneSecondTicks(),
                afterRestart).run(newJob("job2"));

        StoredJob stored = afterRestart.findJob("job1").orElseThrow();
        assertThat(stored.summary().state()).isEqualTo(JobState.COMPLETE);
        assertThat(stored.summary().documentCount()).isEqualTo(1);
        assertThat(stored.summary().steps())
                .extracting(s -> s.name() + ":" + s.outcome())
                .containsExactly("a:" + StepOutcome.COMPLETED, "b:" + StepOutcome.COMPLETED);
        assertThat(afterRestart.findDocuments("job1"))
                .extracting(d -> d.property("accountNumber")).containsExactly("1234567890");
        assertThat(afterRestart.findDocumentsByQueue("statements"))
                .extracting(StoredDocument::jobId).containsExactly("job1", "job2");
    }

    @Test
    public void failedJobIsStoredWithErrorAndStep() throws Exception {
        registry.register("Ok", new RecordingStep("Ok").factory());
        registry.register("Boom", new RecordingStep("Boom")
                .failingOnCall(1, new IllegalStateException("bad page")).factory());
        JdbcJobStore store = new JdbcJobStore(db, TickingClock.oneSecondTicks());
        Job job = newJob("job1");

        new WorkflowEngine(TestWorkflows.of(step("a", "Ok"), step("b", "Boom"), step("c", "Ok")),
                registry, errorDir, TickingClock.oneSecondTicks(), store).run(job);

        StoredJob stored = store.findJob("job1").orElseThrow();
        assertThat(stored.summary().state()).isEqualTo(JobState.ERROR);
        assertThat(stored.summary().currentStep()).isEqualTo("b");
        assertThat(stored.summary().errorMessage()).isEqualTo("bad page");
        assertThat(stored.summary().steps())
                .extracting(s -> s.name() + ":" + s.outcome())
                .containsExactly("a:" + StepOutcome.COMPLETED, "b:" + StepOutcome.FAILED);
    }
}
