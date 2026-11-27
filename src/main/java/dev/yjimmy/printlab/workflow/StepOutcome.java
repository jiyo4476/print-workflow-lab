package dev.yjimmy.printlab.workflow;

/**
 * How a step ended. {@code SKIPPED} means its {@code when:} condition was false. {@code RETRIED}
 * is the audit entry a retry writes for the step it resumes from (PRD-15); it's not a run of the
 * step.
 */
public enum StepOutcome {
    COMPLETED,
    FAILED,
    SKIPPED,
    RETRIED
}
