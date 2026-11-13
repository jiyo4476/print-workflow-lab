package dev.yjimmy.printlab.workflow;

import dev.yjimmy.printlab.workflow.rules.Condition;
import dev.yjimmy.printlab.workflow.rules.RuleParser;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a job through every step of a workflow in order, stopping on the first failure.
 *
 * <p>A step with a {@code when:} condition runs only if the condition holds for the job's
 * properties at that point; otherwise it's recorded as {@code SKIPPED}.
 *
 * <p>Every step is timed and recorded on the job, and {@code work/<jobId>/job.json} is rewritten
 * after each one. When a step throws, the job goes to ERROR, later steps don't run, and the input
 * file moves to the {@link ErrorFolder} with an {@code error.txt}.
 */
public final class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowDefinition definition;
    private final List<NamedStep> steps = new ArrayList<>();
    private final ErrorFolder errorFolder;
    private final Clock clock;
    private final JobSummaryWriter summaries = new JobSummaryWriter();

    /** @param when the step's condition, or {@code null} to always run */
    private record NamedStep(String name, Step step, Condition when) {}

    /**
     * @param errorDir root of the error folder; each failed job gets {@code <errorDir>/<jobId>/}
     * @param clock source of every step's start and finish time
     */
    public WorkflowEngine(
            WorkflowDefinition definition, StepRegistry registry, Path errorDir, Clock clock) {
        this.definition = definition;
        this.errorFolder = new ErrorFolder(errorDir);
        this.clock = clock;
        // Build every step up front so a bad workflow file fails at startup, not mid-job.
        for (WorkflowDefinition.StepDefinition def : definition.steps()) {
            steps.add(new NamedStep(def.name(), registry.create(def), when(def)));
        }
    }

    private static Condition when(WorkflowDefinition.StepDefinition def) {
        if (def.when() == null) {
            return null;
        }
        try {
            return RuleParser.parse(def.when());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Step '" + def.name() + "' has an invalid 'when': " + e.getMessage(), e);
        }
    }

    public void run(Job job) {
        log.info("{} starting workflow '{}'", job, definition.name());
        for (NamedStep s : steps) {
            job.moveTo(JobState.PROCESSING, s.name());
            log.info("{} -> step '{}'", job.id(), s.name());
            Instant startedAt = clock.instant();
            if (s.when() != null && !s.when().test(job.properties())) {
                log.info("{} skipped step '{}': its 'when' is false", job.id(), s.name());
                job.addStepRecord(
                        new StepRecord(s.name(), startedAt, clock.instant(), StepOutcome.SKIPPED));
                writeSummary(job);
                continue;
            }
            try {
                s.step().execute(job);
            } catch (Exception | StackOverflowError | LinkageError e) {
                // StackOverflowError: PDFBox can recurse too deep on a deeply nested or malformed
                // PDF. LinkageError: a missing or broken class. Both are about this one job, so
                // the job fails cleanly. Other Errors (OutOfMemoryError, ...) are left to escape.
                fail(job, s.name(), startedAt, e);
                return;
            }
            job.addStepRecord(
                    new StepRecord(s.name(), startedAt, clock.instant(), StepOutcome.COMPLETED));
            writeSummary(job);
        }
        job.moveTo(JobState.COMPLETE, null);
        writeSummary(job);
        log.info("{} complete: {} documents", job, job.documents().size());
    }

    private void fail(Job job, String step, Instant startedAt, Throwable e) {
        Instant failedAt = clock.instant();
        String message = describe(e);
        job.fail(step, message);
        job.addStepRecord(new StepRecord(step, startedAt, failedAt, StepOutcome.FAILED));
        log.error("{} failed in step '{}': {}", job.id(), step, message, e);
        writeSummary(job);
        try {
            Path dir = errorFolder.receive(job, step, message, failedAt);
            log.info("{} moved to {}", job.id(), dir);
        } catch (IOException ioe) {
            log.error("{} could not be moved to the error folder {}: {}",
                    job.id(), errorFolder.root(), ioe.toString());
        }
    }

    /** The exception's message, or its class name when it has none (a bare NPE, say). */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getName() : message;
    }

    /**
     * The summary is a diagnostic record, not part of the job's output, so failing to write it is
     * logged rather than failing the job.
     */
    private void writeSummary(Job job) {
        try {
            summaries.write(job);
        } catch (IOException e) {
            log.warn("{} could not write {}: {}", job.id(), JobSummaryWriter.FILE_NAME, e.toString());
        }
    }
}
