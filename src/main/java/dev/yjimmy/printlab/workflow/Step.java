package dev.yjimmy.printlab.workflow;

/**
 * A single unit of processing in a workflow — the equivalent of a step built from a step template.
 *
 * <p>Implementations are created by {@link StepRegistry} from a step's {@code type} and configured
 * from its {@code params} in the workflow YAML. Throw an exception to put the job into ERROR.
 */
public interface Step {

    void execute(Job job) throws Exception;

    /**
     * Whether a retry may run this step again, starting from the job state saved after the last
     * step that completed (PRD-15). The engine rolls the job's documents and properties back to
     * that state when a step fails, so a resumable step must run correctly on it, and must
     * only read files it or an earlier step wrote in the job's work folder. Steps that run
     * external programs, or change files outside the work folder, are not resumable.
     *
     * <p>The default is {@code false}: a step opts in only once it has been checked.
     */
    default boolean resumable() {
        return false;
    }
}
