package dev.yjimmy.printlab.testsupport;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A fake {@link Step} that records every call and can be told to throw on its Nth call.
 *
 * <p>Steps that share a {@code trace} list append their name to it, so a test can check the
 * order steps ran in across the whole workflow.
 */
public final class RecordingStep implements Step {

    /** One call: its 1-based number on this step, and the job it ran for. */
    public record Call(int number, String jobId) {}

    private final String name;
    private final List<String> trace;
    private final List<Call> calls = new ArrayList<>();
    private int failOnCall;
    private Exception failure;

    public RecordingStep(String name) {
        this(name, new ArrayList<>());
    }

    public RecordingStep(String name, List<String> trace) {
        this.name = name;
        this.trace = trace;
    }

    /** Throw {@code failure} on call number {@code n} (1-based). The call is still recorded. */
    public RecordingStep failingOnCall(int n, Exception failure) {
        if (n < 1) {
            throw new IllegalArgumentException("Call numbers start at 1, got " + n);
        }
        this.failOnCall = n;
        this.failure = failure;
        return this;
    }

    /** For {@code StepRegistry.register(type, step.factory())}: always returns this instance. */
    public Function<Map<String, Object>, Step> factory() {
        return params -> this;
    }

    public List<Call> calls() {
        return Collections.unmodifiableList(calls);
    }

    @Override
    public void execute(Job job) throws Exception {
        int number = calls.size() + 1;
        calls.add(new Call(number, job.id()));
        trace.add(name);
        if (number == failOnCall) {
            throw failure;
        }
    }
}
