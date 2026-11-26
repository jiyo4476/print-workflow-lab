package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.JobSummary;
import java.time.Instant;

/**
 * A job as the database holds it.
 *
 * @param summary the job's state, properties and step history, the same shape as its
 *     {@code job.json}
 * @param createdAt when the job was first saved
 */
public record StoredJob(JobSummary summary, Instant createdAt) {}
