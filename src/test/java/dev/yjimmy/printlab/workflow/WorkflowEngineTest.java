package dev.yjimmy.printlab.workflow;

import java.nio.channels.ClosedByInterruptException;
import static dev.yjimmy.printlab.testsupport.TestWorkflows.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testsupport.RecordingStep;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public void stackOverflowInStepFailsTheJobCleanly() throws Exception {
        registry.register("Deep", params -> job -> {
            throw new StackOverflowError();
        });

        Job job = newJob("j9");
        Path input = job.inputFile();
        run(job, TestWorkflows.of(step("deep", "Deep")));

        assertEquals(JobState.ERROR, job.state());
        assertEquals("java.lang.StackOverflowError", job.errorMessage());
        assertThat(job.stepRecords()).extracting(StepRecord::outcome)
                .containsExactly(StepOutcome.FAILED);
        assertTrue("input should be in the error folder",
                Files.exists(errorDir.resolve("j9").resolve(input.getFileName())));
    }

    @Test
    public void linkageErrorInStepFailsTheJob() throws Exception {
        registry.register("Broken", params -> job -> {
            throw new NoClassDefFoundError("org/example/Missing");
        });

        Job job = newJob("j10");
        run(job, TestWorkflows.of(step("broken", "Broken")));

        assertEquals(JobState.ERROR, job.state());
        assertEquals("org/example/Missing", job.errorMessage());
    }

    @Test
    public void outOfMemoryErrorIsNotSwallowed() throws Exception {
        registry.register("Hungry", params -> job -> {
            throw new OutOfMemoryError("test");
        });

        Job job = newJob("j11");

        assertThrows("the JVM is in trouble; the engine shouldn't pretend otherwise",
                OutOfMemoryError.class, () -> run(job, TestWorkflows.of(step("hungry", "Hungry"))));
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
                new StepRecord("a", Phase.PREPARE, t0, t0.plusSeconds(1), StepOutcome.COMPLETED),
                new StepRecord("b", Phase.PREPARE, t0.plusSeconds(2), t0.plusSeconds(3), StepOutcome.COMPLETED)),
                job.stepRecords());
    }

    @Test
    public void failedStepIsRecordedWithOutcomeFailed() throws Exception {
        registerOkAndBoom();

        Job job = newJob("j5");
        run(job, TestWorkflows.of(step("ok", "Ok"), step("boom", "Boom")));

        StepRecord last = job.stepRecords().get(job.stepRecords().size() - 1);
        Instant t0 = TickingClock.DEFAULT_START;
        assertEquals(new StepRecord("boom", Phase.PREPARE, t0.plusSeconds(2), t0.plusSeconds(3), StepOutcome.FAILED),
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

    private static WorkflowDefinition.StepDefinition stepWhen(
            String name, String type, Map<String, Object> when) {
        return new WorkflowDefinition.StepDefinition(name, type, Phase.PREPARE, Map.of(), when);
    }

    private static final Map<String, Object> SMALL_JOB =
            Map.of("property", "documentCount", "operator", "lessThan", "value", 50);

    @Test
    public void stepWithFalseWhenIsSkippedAndRecordedAsSkipped() throws Exception {
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j9");
        job.properties().put("documentCount", "500");
        run(job, TestWorkflows.of(step("a", "A"), stepWhen("b", "B", SMALL_JOB), step("c", "A")));

        assertEquals("the step whose 'when' is false should not run", List.of("A", "A"), trace);
        assertEquals(JobState.COMPLETE, job.state());
        assertThat(job.stepRecords()).extracting(StepRecord::name, StepRecord::outcome)
                .containsExactly(
                        tuple("a", StepOutcome.COMPLETED),
                        tuple("b", StepOutcome.SKIPPED),
                        tuple("c", StepOutcome.COMPLETED));
        JobSummary summary =
                JobSummaryWriter.read(job.workDir().resolve(JobSummaryWriter.FILE_NAME));
        assertEquals(StepOutcome.SKIPPED, summary.steps().get(1).outcome());
    }

    @Test
    public void stepWithTrueWhenRuns() throws Exception {
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j10");
        job.properties().put("documentCount", "9");
        run(job, TestWorkflows.of(stepWhen("b", "B", SMALL_JOB)));

        assertEquals(List.of("B"), trace);
        assertEquals(StepOutcome.COMPLETED, job.stepRecords().get(0).outcome());
    }

    @Test
    public void whenSeesPropertiesSetByEarlierSteps() throws Exception {
        registry.register("Count", params -> job -> job.properties().put("documentCount", "3"));
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j11");
        run(job, TestWorkflows.of(step("count", "Count"), stepWhen("b", "B", SMALL_JOB)));

        assertEquals(List.of("B"), trace);
    }

    @Test
    public void invalidWhenFailsAtStartupWithStepName() {
        registry.register("B", new RecordingStep("B", trace).factory());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowEngine(
                        TestWorkflows.of(stepWhen("presort", "B",
                                Map.of("property", "documentCount", "operator", "fewerThan",
                                        "value", 50))),
                        registry, errorDir, TickingClock.oneSecondTicks()));

        assertThat(e).hasMessageContaining("Step 'presort'")
                .hasMessageContaining("Unknown operator 'fewerThan'");
    }

    @Test
    public void whenIsReadFromWorkflowYaml() throws Exception {
        Path yaml = Files.writeString(tmp.resolve("w.yaml"), """
                name: w
                phases:
                  prepare:
                    - name: b
                      type: B
                      when: { property: documentCount, operator: lessThan, value: 50 }
                """);
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j12");
        job.properties().put("documentCount", "50");
        run(job, WorkflowDefinition.load(yaml));

        assertEquals(StepOutcome.SKIPPED, job.stepRecords().get(0).outcome());
    }

    @Test
    public void badStepParamsFailWithStepName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowEngine(TestWorkflows.of(step("presort", "SortDocuments")),
                        StepRegistry.withBuiltIns(), errorDir, TickingClock.oneSecondTicks()));

        assertThat(e).hasMessage(
                "Step 'presort' (SortDocuments): Missing required step param 'by'");
    }

    @Test
    public void workflowWithNoStepsFailsToLoad() throws Exception {
        for (String yaml : new String[] {
                "name: empty\nphases: { prepare: [], print: [] }\n", "name: empty\n"}) {
            Path file = Files.writeString(tmp.resolve("empty.yaml"), yaml);

            IllegalArgumentException e = assertThrows(yaml, IllegalArgumentException.class,
                    () -> WorkflowDefinition.load(file));

            assertThat(e).hasMessage("Workflow 'empty' has no steps");
        }
    }

    @Test
    public void stepsRunPhaseByPhaseAndTheJobRecordsItsPhase() throws Exception {
        Path yaml = Files.writeString(tmp.resolve("phased.yaml"), """
                name: phased
                phases:
                  receive:
                    - { name: detect, type: A }
                  prepare: []
                  print:
                    - { name: write, type: B }
                  complete:
                    - { name: notify, type: A }
                """);
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = newJob("j30");
        run(job, WorkflowDefinition.load(yaml));

        assertThat(job.stepRecords()).extracting(StepRecord::name, StepRecord::phase)
                .containsExactly(tuple("detect", Phase.RECEIVE), tuple("write", Phase.PRINT),
                        tuple("notify", Phase.COMPLETE));
        assertEquals("finished job's phase", Phase.COMPLETE, job.phase());
        JobSummary summary = JobSummaryWriter.read(job.workDir().resolve(JobSummaryWriter.FILE_NAME));
        assertEquals("phase in job.json", Phase.COMPLETE, summary.phase());
    }

    @Test
    public void newJobIsInTheReceivePhase() throws Exception {
        assertEquals(Phase.RECEIVE, newJob("j31").phase());
    }

    @Test
    public void failedJobStaysInThePhaseOfTheFailedStep() throws Exception {
        registerOkAndBoom();

        Job job = newJob("j32");
        run(job, TestWorkflows.of(step("ok", "Ok").inPhase(Phase.RECEIVE),
                step("boom", "Boom").inPhase(Phase.PRINT)));

        assertEquals(JobState.ERROR, job.state());
        assertEquals("phase", Phase.PRINT, job.phase());
        assertThat(Files.readString(errorDir.resolve("j32").resolve(ErrorFolder.ERROR_FILE_NAME)))
                .contains("Phase:  print\nStep:   boom\n");
    }

    @Test
    public void phasesOutOfOrderFailToLoad() throws Exception {
        Path file = Files.writeString(tmp.resolve("order.yaml"), """
                name: order
                phases:
                  print:
                    - { name: write, type: B }
                  prepare:
                    - { name: split, type: A }
                """);

        assertThat(assertThrows(IllegalArgumentException.class, () -> WorkflowDefinition.load(file)))
                .hasMessage("Workflow 'order' lists phase 'prepare' after 'print'; list them in the"
                        + " order receive, prepare, assemble, print, insert, complete");
    }

    @Test
    public void unknownPhaseFailsToLoad() throws Exception {
        Path file = Files.writeString(tmp.resolve("unknown.yaml"), """
                name: unknown
                phases:
                  prepare:
                    - { name: split, type: A }
                  mail:
                    - { name: send, type: B }
                """);

        assertThat(assertThrows(IllegalArgumentException.class, () -> WorkflowDefinition.load(file)))
                .hasMessageContaining("unknown phase 'mail'").hasMessageContaining("receive, prepare");
    }

    @Test
    public void phaseNamesIgnoreCase() throws Exception {
        Path file = Files.writeString(tmp.resolve("case.yaml"), """
                name: case
                phases:
                  Prepare:
                    - { name: split, type: A }
                  PRINT:
                    - { name: write, type: B }
                """);

        assertThat(WorkflowDefinition.load(file).steps()).extracting(StepDefinition::phase)
                .containsExactly(Phase.PREPARE, Phase.PRINT);
    }

    @Test
    public void repeatedPhaseFailsToLoad() throws Exception {
        Path file = Files.writeString(tmp.resolve("twice.yaml"), """
                name: twice
                phases:
                  prepare:
                    - { name: split, type: A }
                  prepare:
                    - { name: sort, type: B }
                """);

        assertThat(assertThrows(Exception.class, () -> WorkflowDefinition.load(file)))
                .hasMessageContaining("prepare");
    }

    @Test
    public void topLevelStepsListPointsToPhases() throws Exception {
        Path file = Files.writeString(tmp.resolve("old.yaml"), """
                name: old
                steps:
                  - { name: split, type: A }
                """);

        assertThat(assertThrows(IllegalArgumentException.class, () -> WorkflowDefinition.load(file)))
                .hasMessageContaining("group them by phase under 'phases:'");
    }

    @Test
    public void workflowBuiltInCodeMustKeepPhaseOrder() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestWorkflows.of(step("write", "B").inPhase(Phase.PRINT),
                        step("split", "A").inPhase(Phase.PREPARE)));

        assertThat(e).hasMessageStartingWith("Step 'split' (prepare) comes after step 'write' (print)");
    }

    @Test
    public void unknownKeyInWorkflowYamlFailsToLoad() throws Exception {
        Path file = Files.writeString(tmp.resolve("typo.yaml"), """
                name: typo
                stpes:
                  - name: a
                    type: A
                """);

        assertThat(assertThrows(Exception.class, () -> WorkflowDefinition.load(file)))
                .hasMessageContaining("stpes");
    }

    @Test
    public void errorFolderThatCannotBeWrittenStillLeavesTheJobInError() throws Exception {
        registerOkAndBoom();
        Files.writeString(errorDir, "a file where the error folder should be");

        Job job = newJob("j13");
        run(job, TestWorkflows.of(step("boom", "Boom")));

        assertEquals(JobState.ERROR, job.state());
        assertEquals("bad page", job.errorMessage());
        assertTrue("input stays where it was", Files.exists(job.inputFile()));
    }

    @Test
    public void interruptFailsTheJobAndKeepsTheInterruptFlag() throws Exception {
        registry.register("Ok", new RecordingStep("Ok", trace).factory());
        registry.register("Wait", new RecordingStep("Wait", trace)
                .failingOnCall(1, new InterruptedException()).factory());

        Job job = newJob("j20");
        boolean flagRestored;
        try {
            run(job, TestWorkflows.of(step("wait", "Wait"), step("ok", "Ok")));
        } finally {
            // Clear it so it doesn't leak into other tests.
            flagRestored = Thread.interrupted();
        }

        assertTrue("interrupt flag restored", flagRestored);
        assertEquals("no step after the interrupt", List.of("Wait"), trace);
        assertEquals(JobState.ERROR, job.state());
        assertThat(job.errorMessage()).startsWith("interrupted before the step finished")
                .contains("resubmit");
        assertTrue("input in the error folder",
                Files.exists(errorDir.resolve("j20").resolve("j20-in.pdf")));
    }

    @Test
    public void interruptSeenAsAnIoErrorStillWritesTheErrorFolder() throws Exception {
        // NIO reports an interrupt during file I/O as ClosedByInterruptException with the flag
        // set. The engine must clear it while writing error.txt, or that write fails too.
        registry.register("Io", params -> job -> {
            Thread.currentThread().interrupt();
            throw new ClosedByInterruptException();
        });

        Job job = newJob("j21");
        boolean flagRestored;
        try {
            run(job, TestWorkflows.of(step("io", "Io")));
        } finally {
            flagRestored = Thread.interrupted();
        }

        assertTrue("interrupt flag restored", flagRestored);
        assertThat(job.errorMessage()).startsWith("interrupted before the step finished");
        assertTrue("error.txt written", Files.exists(
                errorDir.resolve("j21").resolve(ErrorFolder.ERROR_FILE_NAME)));
    }

    @Test
    public void interruptBetweenStepsStopsBeforeTheNextStep() throws Exception {
        registry.register("Ok", new RecordingStep("Ok", trace).factory());
        registry.register("Interrupt", params -> job -> Thread.currentThread().interrupt());

        Job job = newJob("j22");
        boolean flagRestored;
        try {
            run(job, TestWorkflows.of(step("interrupt", "Interrupt"), step("ok", "Ok")));
        } finally {
            flagRestored = Thread.interrupted();
        }

        assertTrue("interrupt flag restored", flagRestored);
        assertEquals("the next step never ran", List.of(), trace);
        assertEquals(JobState.ERROR, job.state());
        assertEquals("ok", job.currentStep());
        assertThat(job.errorMessage()).startsWith("interrupted before the step started");
    }

    @Test
    public void newIdsAreTwelveHexDigits() {
        assertThat(Job.newId()).matches("[0-9a-f]{12}");
        assertThat(Job.newId()).isNotEqualTo(Job.newId());
    }

    @Test
    public void resumeRunsFromTheNamedStepOnly() throws Exception {
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace).asResumable()
                .failingOnCall(1, new IllegalStateException("disk full")).factory());
        registry.register("C", new RecordingStep("C", trace).factory());
        WorkflowDefinition workflow = TestWorkflows.of(
                step("a", "A"), step("b", "B"), step("c", "C"));
        Job job = newJob("job-resume");
        run(job, workflow);
        assertEquals(JobState.ERROR, job.state());
        trace.clear();

        new WorkflowEngine(workflow, registry, errorDir, TickingClock.oneSecondTicks())
                .resume(job, "b");

        assertEquals("A is not run again", List.of("B", "C"), trace);
        assertEquals(JobState.COMPLETE, job.state());
    }

    @Test
    public void resumeKeepsDocumentsTheCompletedStepsMade() throws Exception {
        registry.register("Split", params -> job -> job.documents().add(
                new Document(1, tmp.resolve("one.pdf"), Map.of("accountNumber", "111"), 1)));
        registry.register("Preflight", new RecordingStep("Preflight", trace).asResumable()
                .failingOnCall(1, new IllegalStateException("unreadable")).factory());
        WorkflowDefinition workflow = TestWorkflows.of(
                step("split", "Split"), step("preflight", "Preflight"));
        Job job = newJob("job-keep");
        run(job, workflow);

        new WorkflowEngine(workflow, registry, errorDir, TickingClock.oneSecondTicks())
                .resume(job, "preflight");

        assertEquals("the split is not run again, so its document is not doubled",
                1, job.documents().size());
    }

    @Test
    public void failedStepLeavesDocumentsAndPropertiesAsTheLastCompletedStepDid() throws Exception {
        registry.register("Split", params -> job -> job.documents().add(
                new Document(1, tmp.resolve("one.pdf"), Map.of("accountNumber", "111"), 1)));
        registry.register("Partial", params -> job -> {
            job.documents().add(new Document(2, tmp.resolve("two.pdf"), Map.of(), 2));
            job.properties().put("halfDone", "yes");
            throw new IllegalStateException("half done");
        });
        Job job = newJob("job-partial");

        run(job, TestWorkflows.of(step("split", "Split"), step("partial", "Partial")));

        assertEquals(JobState.ERROR, job.state());
        assertEquals("only the completed step's document is kept", 1, job.documents().size());
        assertEquals("111", job.documents().get(0).property("accountNumber"));
        assertThat(job.properties()).doesNotContainKey("halfDone");
    }

    @Test
    public void resumeRefusesAStepThatIsNotResumable() throws Exception {
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace)
                .failingOnCall(1, new IllegalStateException("boom")).factory());
        WorkflowDefinition workflow = TestWorkflows.of(step("a", "A"), step("b", "B"));
        Job job = newJob("job-refused");
        run(job, workflow);
        trace.clear();

        WorkflowEngine engine = new WorkflowEngine(
                workflow, registry, errorDir, TickingClock.oneSecondTicks());
        assertThrows(IllegalStateException.class, () -> engine.resume(job, "b"));

        assertEquals("nothing ran", List.of(), trace);
        assertEquals(JobState.ERROR, job.state());
    }

    @Test
    public void resumeRefusesAStepTheWorkflowDoesNotHave() throws Exception {
        registry.register("A", new RecordingStep("A", trace).factory());
        WorkflowDefinition workflow = TestWorkflows.of(step("a", "A"));
        Job job = newJob("job-unknown");
        WorkflowEngine engine = new WorkflowEngine(
                workflow, registry, errorDir, TickingClock.oneSecondTicks());

        assertThrows(IllegalArgumentException.class, () -> engine.resume(job, "nope"));
    }
}
