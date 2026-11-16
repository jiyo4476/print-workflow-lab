package dev.yjimmy.printlab.workflow;

/** How a step ended. {@code SKIPPED} means its {@code when:} condition was false. */
public enum StepOutcome {
    COMPLETED,
    FAILED,
    SKIPPED
}
