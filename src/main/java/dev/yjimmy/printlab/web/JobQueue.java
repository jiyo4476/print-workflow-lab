package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.workflow.Job;

/**
 * Where a job built by an action (a reprint, PRD-15) is run: the app's input devices queue it on
 * the shared job thread in production; tests supply a fake.
 */
interface JobQueue {

    /**
     * Queues the job to run; {@code done} runs on the job thread once it has finished.
     *
     * @throws IllegalStateException if the job can't be queued
     */
    void submit(Job job, Runnable done);
}
