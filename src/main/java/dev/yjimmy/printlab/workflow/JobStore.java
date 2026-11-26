package dev.yjimmy.printlab.workflow;

import java.io.IOException;

/**
 * Where a job's state is kept. The {@link WorkflowEngine} saves the job after every step, and
 * again when it finishes or fails, so whatever the store holds is never more than one step
 * behind.
 *
 * <p>Two implementations: {@link FileJobStore} writes {@code work/<jobId>/job.json} and needs no
 * database; the JDBC store in {@code dev.yjimmy.printlab.store} keeps jobs and documents in
 * PostgreSQL (PRD-13).
 */
public interface JobStore {

    /**
     * Saves the job as it is now. Saving the same job again replaces what was saved before.
     *
     * @throws IOException if the job could not be saved; the engine logs this and carries on,
     *     because the job's output does not depend on its record
     */
    void save(Job job) throws IOException;
}
