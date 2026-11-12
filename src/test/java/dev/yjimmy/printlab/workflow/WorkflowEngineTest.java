package dev.yjimmy.printlab.workflow;

import static dev.yjimmy.printlab.testsupport.TestWorkflows.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.RecordingStep;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkflowEngineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tmp;
    private Path errorDir;
    private StepRegistry registry;
    private List<String> trace;

    @Before
    public void setUp() {
        tmp = folder.getRoot().toPath();
        errorDir = tmp.resolve("error");
        registry = new StepRegistry();
        trace = new ArrayList<>();
    }

    private Job newJob(String id) throws Exception {
        Path input = Files.writeString(tmp.resolve(id + "-in.pdf"), "not really a pdf");
        return TestJobs.newJob(id, tmp, input);
    }

    private void run(Job job, WorkflowDefinition workflow) {
        new WorkflowEngine(workflow, registry, errorDir, TickingClock.oneSecondTicks()).run(job);
    }

    /** Registers Ok (always succeeds) and Boom (throws "bad page" on its first call). */
    private void registerOkAndBoom() {
        registry.register("Ok", new RecordingStep("Ok", trace).factory());
        registry.register("Boom", new RecordingStep("Boom", trace)
                .failingOnCall(1, new IllegalStateException("bad page")).factory());
    }

    @Test
    public void runsStepsInOrderAndCompletes() throws Exception {
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j1");
        run(job, TestWorkflows.of(step("a", "A"), step("b", "B")));

        assertEquals("steps should run in workflow order", List.of("A", "B"), trace);
        assertEquals(JobState.COMPLETE, job.state());
    }

    @Test
    public void stopsAtFailingStepAndRecordsError() throws Exception {
        registerOkAndBoom();

        Job job = newJob("j2");
        run(job, TestWorkflows.of(step("ok", "Ok"), step("boom", "Boom"), step("ok-again", "Ok")));

        assertEquals("no step should run after the failure", List.of("Ok", "Boom"), trace);
        assertEquals(JobState.ERROR, job.state());
        assertEquals("boom", job.currentStep());
        assertEquals("bad page", job.errorMessage());
    }

    @Test
    public void exceptionWithoutMessageIsDescribedByItsClass() throws Exception {
        registry.register("Npe", new RecordingStep("Npe", trace)
                .failingOnCall(1, new NullPointerException()).factory());

        Job job = newJob("j3");
        run(job, TestWorkflows.of(step("npe", "Npe")));

        assertEquals("java.lang.NullPointerException", job.errorMessage());
    }

    @Test
    public void recordsStartAndFinishForEveryStep() throws Exception {
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j4");
        run(job, TestWorkflows.of(step("a", "A"), step("b", "B")));

        // The clock ticks one second per read: a starts, a ends, b starts, b ends.
        Instant t0 = TickingClock.DEFAULT_START;
        assertEquals(List.of(
                new StepRecord("a", t0, t0.plusSeconds(1), StepOutcome.COMPLETED),
                new StepRecord("b", t0.plusSeconds(2), t0.plusSeconds(3), StepOutcome.COMPLETED)),
                job.stepRecords());
    }

    @Test
    public void failedStepIsRecordedWithOutcomeFailed() throws Exception {
        registerOkAndBoom();

        Job job = newJob("j5");
        run(job, TestWorkflows.of(step("ok", "Ok"), step("boom", "Boom")));

        StepRecord last = job.stepRecords().get(job.stepRecords().size() - 1);
        Instant t0 = TickingClock.DEFAULT_START;
        assertEquals(new StepRecord("boom", t0.plusSeconds(2), t0.plusSeconds(3), StepOutcome.FAILED),
                last);
    }

    @Test
    public void stepsAfterFailureAreNotRecorded() throws Exception {
        registerOkAndBoom();

        Job job = newJob("j6");
        run(job, TestWorkflows.of(step("ok", "Ok"), step("boom", "Boom"), step("ok-again", "Ok")));

        assertThat(job.stepRecords()).extracting(StepRecord::name).containsExactly("ok", "boom");
    }

    @Test
    public void rewritesSummaryAfterEachStep() throws Exception {
        // A step that reads job.json as it stands when the step starts.
        List<JobSummary> seen = new ArrayList<>();
        registry.register("Peek", params -> job ->
                seen.add(JobSummaryWriter.read(job.workDir().resolve(JobSummaryWriter.FILE_NAME))));
        registry.register("A", new RecordingStep("A", trace).factory());

        Job job = newJob("j7");
        run(job, TestWorkflows.of(step("a", "A"), step("peek", "Peek")));

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).steps()).extracting(StepRecord::name).containsExactly("a");
        assertEquals("the summary written after step a still says PROCESSING",
                JobState.PROCESSING, seen.get(0).state());

        JobSummary finalSummary =
                JobSummaryWriter.read(job.workDir().resolve(JobSummaryWriter.FILE_NAME));
        assertEquals(JobState.COMPLETE, finalSummary.state());
        assertThat(finalSummary.steps()).extracting(StepRecord::name).containsExactly("a", "peek");
    }

    @Test
    public void failedJobSummaryRecordsTheError() throws Exception {
        registerOkAndBoom();

        Job job = newJob("j8");
        run(job, TestWorkflows.of(step("ok", "Ok"), step("boom", "Boom")));

        JobSummary summary =
                JobSummaryWriter.read(job.workDir().resolve(JobSummaryWriter.FILE_NAME));
        assertEquals(JobState.ERROR, summary.state());
        assertEquals("boom", summary.currentStep());
        assertEquals("bad page", summary.errorMessage());
        assertThat(summary.steps()).extracting(StepRecord::outcome)
                .containsExactly(StepOutcome.COMPLETED, StepOutcome.FAILED);
    }

    @Test
    public void unknownStepTypeFailsAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowEngine(TestWorkflows.of(step("nope", "Nope")),
                        StepRegistry.withBuiltIns(), errorDir, TickingClock.oneSecondTicks()));
    }
}
