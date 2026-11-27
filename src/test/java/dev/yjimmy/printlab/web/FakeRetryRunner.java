package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link RetryRunner} for tests. It runs retries on the engine the test gives it, either at
 * once or when the test calls {@link #runQueued()}, so a test can check what happens while a
 * retry is still waiting.
 */
final class FakeRetryRunner implements RetryRunner {

    /** A retry the runner has taken, waiting to run. */
    private record Queued(Job job, String step, Runnable done) {}

    private final List<Queued> queued = new ArrayList<>();
    /** The engine retries run on. */
    WorkflowEngine engine;
    /** When true, {@link #start} runs the retry at once instead of queueing it. */
    boolean runAtOnce = true;
    /** The devices {@link #check} was asked about, so a test can see the device was checked. */
    final List<String> checkedDevices = new ArrayList<>();

    @Override
    public void check(String device, String step) {
        checkedDevices.add(device);
        engine.checkCanResume(step);
    }

    @Override
    public void start(Job job, String device, String step, Runnable done) {
        if (runAtOnce) {
            run(new Queued(job, step, done));
        } else {
            queued.add(new Queued(job, step, done));
        }
    }

    /** Runs every retry waiting in the queue, in order. */
    void runQueued() {
        List<Queued> waiting = new ArrayList<>(queued);
        queued.clear();
        waiting.forEach(this::run);
    }

    int queuedCount() {
        return queued.size();
    }

    private void run(Queued retry) {
        try {
            engine.resume(retry.job(), retry.step());
        } finally {
            retry.done().run();
        }
    }
}
