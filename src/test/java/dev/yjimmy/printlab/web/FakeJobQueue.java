package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.WorkflowEngine;

/**
 * A {@link JobQueue} for tests: each job runs at once through the engine the test gives it, or
 * is refused when {@link #refuse} is set, as a stopping app would refuse it.
 */
final class FakeJobQueue implements JobQueue {

    /** The engine jobs run through. */
    WorkflowEngine engine;
    /** When true, {@link #submit} refuses the job. */
    boolean refuse;

    @Override
    public void submit(Job job, Runnable done) {
        if (refuse) {
            throw new IllegalStateException("the app is stopping and can't start the job");
        }
        try {
            engine.run(job);
        } finally {
            done.run();
        }
    }
}
