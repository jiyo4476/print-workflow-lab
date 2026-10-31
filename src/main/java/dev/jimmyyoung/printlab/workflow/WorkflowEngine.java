package dev.jimmyyoung.printlab.workflow;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a job through every step of a workflow in order, stopping on the first failure. */
public final class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowDefinition definition;
    private final List<NamedStep> steps = new ArrayList<>();

    private record NamedStep(String name, Step step) {}

    public WorkflowEngine(WorkflowDefinition definition, StepRegistry registry) {
        this.definition = definition;
        // Build every step up front so a bad workflow file fails at startup, not mid-job.
        for (WorkflowDefinition.StepDefinition def : definition.steps()) {
            steps.add(new NamedStep(def.name(), registry.create(def)));
        }
    }

    public void run(Job job) {
        log.info("{} starting workflow '{}'", job, definition.name());
        for (NamedStep s : steps) {
            job.moveTo(JobState.PROCESSING, s.name());
            log.info("{} -> step '{}'", job.id(), s.name());
            try {
                s.step().execute(job);
            } catch (Exception e) {
                job.fail(s.name(), e.getMessage());
                log.error("{} failed in step '{}': {}", job.id(), s.name(), e.getMessage(), e);
                return;
            }
        }
        job.moveTo(JobState.COMPLETE, null);
        log.info("{} complete: {} documents", job, job.documents().size());
    }
}
