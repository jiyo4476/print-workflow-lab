package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.steps.SetJobPropsFromTextFileStep;
import dev.yjimmy.printlab.store.JdbcJobStore;
import dev.yjimmy.printlab.store.StoredJob;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Retries a failed job from the step that failed (PRD-15, FR-5 to FR-7). The job's input goes
 * back from the error folder to its work folder, its error folder is removed, and the job is
 * queued on its device's runner. Only one retry of a job can be pending at a time, so a double
 * submit doesn't run it twice.
 */
final class RetryService {

    /** A retry that was queued. */
    record Accepted(String jobId, String step) {}

    /** The property the job's input device is recorded under by {@code JobIntake}. */
    static final String DEVICE_PROPERTY = "inputDevice";
    /** The property the job's input file name is recorded under. */
    static final String INPUT_PROPERTY = "inputFileName";

    private final JdbcJobStore jobs;
    private final RetryRunner runner;
    private final Path workRoot;
    private final Path errorDir;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    RetryService(JdbcJobStore jobs, RetryRunner runner, Path workRoot, Path errorDir) {
        this.jobs = jobs;
        this.runner = runner;
        this.workRoot = workRoot;
        this.errorDir = errorDir;
    }

    /**
     * Retries the job. Synchronized, so two requests for one job are handled one after the other:
     * the second sees the first as pending and is refused.
     *
     * @throws ApiException 404 if there is no such job; 409 if it is not a failed job, is already
     *     being retried, or its input is gone; 500 if its files can't be moved
     */
    synchronized Accepted retry(String jobId) throws IOException {
        StoredJob stored = jobs.findJob(jobId)
                .orElseThrow(() -> ApiException.notFound("there is no job with id " + jobId));
        JobSummary summary = stored.summary();
        if (pending.contains(jobId) || summary.state() == JobState.PROCESSING) {
            throw new ApiException(409, "job " + jobId + " is already running");
        }
        if (summary.state() != JobState.ERROR) {
            throw new ApiException(409, "only a failed job can be retried, and job " + jobId
                    + " is " + summary.state().name().toLowerCase());
        }
        String step = summary.currentStep();
        String device = summary.properties().get(DEVICE_PROPERTY);
        String inputName = summary.properties().get(INPUT_PROPERTY);
        if (step == null || device == null || inputName == null) {
            throw new ApiException(409, "job " + jobId + " does not record the step, device or"
                    + " input file it failed with, so it can't be retried");
        }
        try {
            runner.check(device, step);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }

        Path workDir = workRoot.resolve(jobId);
        Path input = workDir.resolve(inputName);
        restoreInput(jobId, input);
        List<Document> documents = jobs.findDocuments(jobId);
        // The error message belongs to the failure; the retry clears it.
        Job job = Job.restore(jobId, input, workDir, summary.properties(), documents,
                summary.steps(), JobState.ERROR, summary.phase(), step, null);

        pending.add(jobId);
        try {
            runner.start(job, device, step, () -> pending.remove(jobId));
        } catch (RuntimeException e) {
            pending.remove(jobId);
            throw e;
        }
        return new Accepted(jobId, step);
    }

    /**
     * Moves the input (and its job ticket) from the error folder back to the work folder, then
     * removes the error folder. If the input is already in the work folder, an earlier retry
     * moved it and failed to start, so nothing moves.
     */
    private void restoreInput(String jobId, Path input) throws IOException {
        Path errorJob = errorDir.resolve(jobId);
        if (!Files.exists(input)) {
            Path fromError = errorJob.resolve(input.getFileName());
            if (!Files.exists(fromError)) {
                throw new ApiException(409, "the input file for job " + jobId + " is no longer in"
                        + " its work or error folder, so the job can't be retried; resubmit the"
                        + " input");
            }
            Files.createDirectories(input.getParent());
            Files.move(fromError, input);
            Path ticketFromError = errorJob.resolve(
                    SetJobPropsFromTextFileStep.ticketFor(input).getFileName());
            if (Files.isRegularFile(ticketFromError)) {
                Files.move(ticketFromError, SetJobPropsFromTextFileStep.ticketFor(input));
            }
        }
        deleteRecursively(errorJob);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
