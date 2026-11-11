package dev.yjimmy.printlab.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WorkflowEngineTest {

    private static WorkflowDefinition workflow(String... types) {
        List<WorkflowDefinition.StepDefinition> steps = new ArrayList<>();
        for (String t : types) {
            steps.add(new WorkflowDefinition.StepDefinition(t.toLowerCase(), t, Map.of()));
        }
        return new WorkflowDefinition("test", null, steps);
    }

    @Test
    public void runsStepsInOrderAndCompletes() {
        List<String> ran = new ArrayList<>();
        StepRegistry registry = new StepRegistry();
        registry.register("A", p -> job -> ran.add("A"));
        registry.register("B", p -> job -> ran.add("B"));

        Job job = new Job("j1", Path.of("in.pdf"), Path.of("work"));
        new WorkflowEngine(workflow("A", "B"), registry).run(job);

        assertEquals(List.of("A", "B"), ran);
        assertEquals(JobState.COMPLETE, job.state());
    }

    @Test
    public void stopsAtFailingStepAndRecordsError() {
        List<String> ran = new ArrayList<>();
        StepRegistry registry = new StepRegistry();
        registry.register("Ok", p -> job -> ran.add("Ok"));
        registry.register("Boom", p -> job -> { throw new IllegalStateException("bad page"); });

        Job job = new Job("j2", Path.of("in.pdf"), Path.of("work"));
        new WorkflowEngine(workflow("Ok", "Boom", "Ok"), registry).run(job);

        assertEquals(List.of("Ok"), ran);
        assertEquals(JobState.ERROR, job.state());
        assertEquals("boom", job.currentStep());
        assertEquals("bad page", job.errorMessage());
    }

    @Test
    public void unknownStepTypeFailsAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowEngine(workflow("Nope"), StepRegistry.withBuiltIns()));
    }
}
