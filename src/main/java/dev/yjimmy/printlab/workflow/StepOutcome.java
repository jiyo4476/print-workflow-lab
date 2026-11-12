package dev.yjimmy.printlab.workflow;

/** How a step ended. PRD-02 adds {@code SKIPPED} for steps a routing rule bypasses. */
public enum StepOutcome {
    COMPLETED,
    FAILED
}
