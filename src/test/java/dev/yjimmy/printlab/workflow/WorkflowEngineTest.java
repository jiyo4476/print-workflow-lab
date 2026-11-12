package dev.yjimmy.printlab.workflow;

import static dev.yjimmy.printlab.testsupport.TestWorkflows.step;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.RecordingStep;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WorkflowEngineTest {

    @Test
    public void runsStepsInOrderAndCompletes() {
        List<String> trace = new ArrayList<>();
        StepRegistry registry = new StepRegistry();
        registry.register("A", new RecordingStep("A", trace).factory());
        registry.register("B", new RecordingStep("B", trace).factory());

        Job job = new Job("j1", Path.of("in.pdf"), Path.of("work"));
        new WorkflowEngine(TestWorkflows.of(step("a", "A"), step("b", "B")), registry).run(job);

        assertEquals("steps should run in workflow order", List.of("A", "B"), trace);
        assertEquals(JobState.COMPLETE, job.state());
    }

    @Test
    public void stopsAtFailingStepAndRecordsError() {
        List<String> trace = new ArrayList<>();
        RecordingStep ok = new RecordingStep("Ok", trace);
        RecordingStep boom = new RecordingStep("Boom", trace)
                .failingOnCall(1, new IllegalStateException("bad page"));
        StepRegistry registry = new StepRegistry();
        registry.register("Ok", ok.factory());
        registry.register("Boom", boom.factory());

        Job job = new Job("j2", Path.of("in.pdf"), Path.of("work"));
        new WorkflowEngine(
                TestWorkflows.of(step("ok", "Ok"), step("boom", "Boom"), step("ok-again", "Ok")),
                registry).run(job);

        assertEquals("no step should run after the failure", List.of("Ok", "Boom"), trace);
        assertEquals(JobState.ERROR, job.state());
        assertEquals("boom", job.currentStep());
        assertEquals("bad page", job.errorMessage());
    }

    @Test
    public void unknownStepTypeFailsAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowEngine(TestWorkflows.of(step("nope", "Nope")),
                        StepRegistry.withBuiltIns()));
    }
}
