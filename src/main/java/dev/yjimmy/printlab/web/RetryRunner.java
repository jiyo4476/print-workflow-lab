package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.workflow.Job;

/**
 * Where a retry runs. The app's input devices implement it in production, so the retry runs on
 * the same job thread as every other job; tests supply a fake.
 */
interface RetryRunner {

    /**
     * Says whether a job on {@code device} can be resumed from {@code step}, without starting it.
     *
     * @throws IllegalArgumentException if there is no such device
     * @throws IllegalStateException if the step can't be run again on its own
     */
    void check(String device, String step);

    /**
     * Queues the job to resume from {@code step}; {@code done} runs once that has finished.
     *
     * @throws IllegalStateException if the job can't be queued
     */
    void start(Job job, String device, String step, Runnable done);
}
