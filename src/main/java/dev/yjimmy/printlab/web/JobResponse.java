package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.StoredJob;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import dev.yjimmy.printlab.workflow.Phase;
import dev.yjimmy.printlab.workflow.StepRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A job in the API. It's the same as {@code job.json} plus {@code createdAt}, which the database
 * keeps for the ordering. This record is the response contract: {@code fixtures/api/job.json}
 * pins its shape.
 */
record JobResponse(
        String id,
        JobState state,
        Phase phase,
        String currentStep,
        String errorMessage,
        Map<String, String> properties,
        int documentCount,
        List<StepRecord> steps,
        Instant createdAt) {

    /** A Windows drive path, or a Unix absolute path with at least one more segment. */
    private static final Pattern SERVER_PATH = Pattern.compile(
            "[A-Za-z]:[\\\\/][^\\s:*?\"<>|]*|(?<![\\w.])/[^\\s/:]+/[^\\s:*?\"<>|]*");

    static JobResponse of(StoredJob job) {
        JobSummary s = job.summary();
        return new JobResponse(s.id(), s.state(), s.phase(), s.currentStep(),
                scrub(s.errorMessage()), s.properties(), s.documentCount(), s.steps(),
                job.createdAt());
    }

    /**
     * The error message with server file paths replaced by {@code <path>}. Step errors often name
     * the file they failed on, and that reveals the server's folder layout. The stored job keeps
     * the full message.
     */
    static String scrub(String message) {
        if (message == null) {
            return null;
        }
        return SERVER_PATH.matcher(message).replaceAll("<path>");
    }
}
