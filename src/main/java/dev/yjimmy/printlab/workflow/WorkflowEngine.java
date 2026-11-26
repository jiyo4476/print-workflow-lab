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
 * <p>Steps are grouped into {@link Phase}s (receive, prepare, assemble, print, insert, complete)
 * and run phase by phase. The job's {@link Job#phase()} is the phase of the step it's on; it
 * starts in receive and ends in complete, whether or not those phases have steps. A failed job
 * stays in the phase of the step that failed.
 *
 * <p>A step with a {@code when:} condition runs only if the condition holds for the job's
 * properties at that point; otherwise it's recorded as {@code SKIPPED}.
 *
 * <p>Every step is timed and recorded on the job, and the job is saved through its
 * {@link JobStore} after each one (by default, {@code work/<jobId>/job.json}). When a step throws,
 * the job goes to ERROR, later steps don't run, and the input file moves to the
 * {@link ErrorFolder} with an {@code error.txt}.
 *
 * <p>An interrupted thread (a shutdown, say) fails the job the same way, saying it was
 * interrupted, and {@link #run} returns with the thread's interrupt flag still set.
 */
public final class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowDefinition definition;
    private final List<NamedStep> steps = new ArrayList<>();
    private final ErrorFolder errorFolder;
    private final Clock clock;
    private final JobStore store;

    /** @param when the step's condition, or {@code null} to always run */
    private record NamedStep(String name, Phase phase, Step step, Condition when) {}

    /**
     * Saves each job's state as {@code work/<jobId>/job.json}.
     *
     * @param errorDir root of the error folder; each failed job gets {@code <errorDir>/<jobId>/}
     * @param clock source of every step's start and finish time
     */
    public WorkflowEngine(
            WorkflowDefinition definition, StepRegistry registry, Path errorDir, Clock clock) {
        this(definition, registry, errorDir, clock, new FileJobStore());
    }

    /**
     * @param store where each job is saved after every step (see {@link JobStore})
     */
    public WorkflowEngine(WorkflowDefinition definition, StepRegistry registry, Path errorDir,
            Clock clock, JobStore store) {
        this.definition = definition;
        this.errorFolder = new ErrorFolder(errorDir);
        this.clock = clock;
        this.store = store;
        // Build every step up front so a bad workflow file fails at startup, not mid-job.
        for (WorkflowDefinition.StepDefinition def : definition.steps()) {
            steps.add(new NamedStep(def.name(), def.phase(), registry.create(def), when(def)));
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
            if (s.phase() != job.phase()) {
                log.info("{} entered phase '{}'", job.id(), s.phase().yamlName());
            }
            job.moveTo(JobState.PROCESSING, s.phase(), s.name());
            Instant startedAt = clock.instant();
            if (Thread.interrupted()) {
                // Interrupted between steps: don't start another one.
                fail(job, s, startedAt, "interrupted before the step started; resubmit"
                        + " the input to run it again", null);
                Thread.currentThread().interrupt();
                return;
            }
            log.info("{} -> step '{}'", job.id(), s.name());
            if (s.when() != null && !s.when().test(job.properties())) {
                log.info("{} skipped step '{}': its 'when' is false", job.id(), s.name());
                job.addStepRecord(
                        new StepRecord(s.name(), s.phase(), startedAt, clock.instant(),
                                StepOutcome.SKIPPED));
                saveJob(job);
                continue;
            }
            try {
                s.step().execute(job);
            } catch (Exception | StackOverflowError | LinkageError e) {
                // StackOverflowError: PDFBox can recurse too deep on a deeply nested or malformed
                // PDF. LinkageError: a missing or broken class. Both are about this one job, so
                // the job fails cleanly. Other Errors (OutOfMemoryError, ...) are left to escape,
                // except that the PDF split steps turn running out of heap into an exception
                // themselves (steps/HeapLimit), since the memory they use is theirs alone.
                //
                // An interrupt means someone wants this thread to stop (a shutdown, say). The job
                // can't finish, so it fails like any other and its input goes to the error folder
                // to be resubmitted. The interrupt flag is cleared while the error folder is
                // written (an interrupted thread can't write files through NIO channels) and
                // then restored, so the caller still sees it.
                boolean interrupted = Thread.interrupted() || e instanceof InterruptedException;
                String message = interrupted
                        ? "interrupted before the step finished (" + describe(e)
                                + "); resubmit the input to run it again"
                        : describe(e);
                fail(job, s, startedAt, message, e);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            job.addStepRecord(new StepRecord(
                    s.name(), s.phase(), startedAt, clock.instant(), StepOutcome.COMPLETED));
            saveJob(job);
        }
        // A finished job is in the complete phase, whether or not that phase has steps.
        job.moveTo(JobState.COMPLETE, Phase.COMPLETE, null);
        saveJob(job);
        log.info("{} complete: {} documents", job, job.documents().size());
    }

    private void fail(Job job, NamedStep s, Instant startedAt, String message, Throwable e) {
        Instant failedAt = clock.instant();
        job.fail(s.name(), message);
        job.addStepRecord(
                new StepRecord(s.name(), s.phase(), startedAt, failedAt, StepOutcome.FAILED));
        log.error("{} failed in phase '{}', step '{}': {}",
                job.id(), s.phase().yamlName(), s.name(), message, e);
        saveJob(job);
        try {
            Path dir = errorFolder.receive(job, s.name(), message, failedAt);
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
     * The saved state is a record of the job, not part of its output, so failing to save it is
     * logged rather than failing the job.
     */
    private void saveJob(Job job) {
        try {
            store.save(job);
        } catch (IOException e) {
            log.warn("{} could not be saved: {}", job.id(), e.toString());
        }
    }
}
