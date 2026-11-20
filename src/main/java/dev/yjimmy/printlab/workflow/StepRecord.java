package dev.yjimmy.printlab.workflow;

import java.time.Instant;

/**
 * One step a job went through: the job's step history, written to {@code job.json}.
 *
 * @param name the step's name from the workflow YAML
 * @param phase the phase the step belongs to
 * @param startedAt when the engine started the step
 * @param finishedAt when the step returned or threw
 * @param outcome whether it completed or failed
 */
public record StepRecord(
        String name, Phase phase, Instant startedAt, Instant finishedAt, StepOutcome outcome) {}
