package dev.yjimmy.printlab.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of a job as written to {@code work/<jobId>/job.json}. It reads back into this same
 * shape, so tests and later the file-based job store (PRD-13) can load it.
 *
 * @param errorMessage why the job failed, or {@code null} unless {@code state} is ERROR
 */
public record JobSummary(
        String id,
        JobState state,
        String currentStep,
        String errorMessage,
        Map<String, String> properties,
        int documentCount,
        List<StepRecord> steps) {

    public static JobSummary of(Job job) {
        return new JobSummary(
                job.id(),
                job.state(),
                job.currentStep(),
                job.errorMessage(),
                new LinkedHashMap<>(job.properties()),
                job.documents().size(),
                List.copyOf(job.stepRecords()));
    }
}
