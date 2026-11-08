package dev.yjimmy.printlab.workflow;

/**
 * A single unit of processing in a workflow — the equivalent of a step built from a step template.
 *
 * <p>Implementations are created by {@link StepRegistry} from a step's {@code type} and configured
 * from its {@code params} in the workflow YAML. Throw an exception to put the job into ERROR.
 */
public interface Step {

    void execute(Job job) throws Exception;
}
