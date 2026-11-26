package dev.yjimmy.printlab.workflow;

import java.io.IOException;

/** Keeps each job's state in {@code work/<jobId>/job.json} (see {@link JobSummaryWriter}). */
public final class FileJobStore implements JobStore {

    private final JobSummaryWriter writer = new JobSummaryWriter();

    @Override
    public void save(Job job) throws IOException {
        writer.write(job);
    }
}
