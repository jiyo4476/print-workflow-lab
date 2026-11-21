package dev.yjimmy.printlab.input;

import dev.yjimmy.printlab.steps.SetJobPropsFromTextFileStep;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims a file from a hot folder as a new job: moves it into {@code <workRoot>/<jobId>/}, so it
 * can never be picked up twice, and returns the job. This is the {@link HotFolderWatcher}'s
 * handler contract: if the move fails the file stays where it was, and the watcher retries it.
 *
 * <p>A job ticket next to the input, {@code <input>.jobprops} (say {@code run.pdf.jobprops}),
 * moves with it, for the {@code SetJobPropsFromTextFile} step. Only a ticket already in the
 * folder when the input is claimed goes with it, so copy the ticket in first.
 */
public final class JobIntake {

    private static final Logger log = LoggerFactory.getLogger(JobIntake.class);

    private final Path workRoot;

    public JobIntake(Path workRoot) {
        this.workRoot = workRoot;
    }

    /** The job property naming the input device a job came from. */
    public static final String INPUT_DEVICE = "inputDevice";

    /**
     * Claims {@code file} for the input device {@code device}, recorded as the job's
     * {@link #INPUT_DEVICE} property.
     *
     * @throws IOException as {@link #claim(Path)}
     */
    public Job claim(Path file, String device) throws IOException {
        Job job = claim(file);
        job.properties().put(INPUT_DEVICE, device);
        return job;
    }

    /**
     * @throws IOException if the file can't be moved (still locked, gone, ...); nothing is left
     *     behind in the work folder
     */
    public Job claim(Path file) throws IOException {
        String id = Job.newId();
        Path workDir = workRoot.resolve(id);
        Files.createDirectories(workDir);
        Path input;
        try {
            input = Files.move(file, workDir.resolve(file.getFileName()));
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(workDir);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        claimJobTicket(id, file, workDir);
        return new Job(id, input, workDir);
    }

    /**
     * Moves the input's ticket along, if there is one. The input is already claimed, so a ticket
     * that can't be moved is logged, not thrown: the job runs without it (and a step that
     * requires it fails the job, which says why).
     */
    private static void claimJobTicket(String id, Path file, Path workDir) {
        Path ticket = file.resolveSibling(file.getFileName() + SetJobPropsFromTextFileStep.EXTENSION);
        if (!Files.isRegularFile(ticket)) {
            return;
        }
        try {
            Files.move(ticket, workDir.resolve(ticket.getFileName()));
        } catch (IOException e) {
            log.warn("{}: job ticket {} could not be moved with its input: {}", id, ticket, e.toString());
        }
    }
}
