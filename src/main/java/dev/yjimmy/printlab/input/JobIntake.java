package dev.yjimmy.printlab.input;

import dev.yjimmy.printlab.steps.SetJobPropsFromTextFileStep;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims a file from a hot folder as a new job: moves it into {@code <workRoot>/<jobId>/}, so it
 * can never be picked up twice, and returns the job. This is the {@link HotFolderWatcher}'s
 * handler contract: if the move fails the file stays where it was, and the watcher retries it.
 *
 * <p>A job ticket next to the input, {@code <input>.jobprops} (say {@code run.pdf.jobprops}),
 * moves with it, for the {@code SetJobPropsFromTextFile} step. Only a ticket already in the
 * folder when the input is claimed goes with it, so copy the ticket in first. The ticket moves
 * first: if it can't be moved (still locked by the program copying it, say), the claim fails and
 * both stay in the hot folder for the watcher to retry, so a ticket is never left behind for a
 * later input of the same name.
 */
public final class JobIntake {

    private static final Logger log = LoggerFactory.getLogger(JobIntake.class);

    private final Path workRoot;
    private final Supplier<String> ids;

    public JobIntake(Path workRoot) {
        this(workRoot, Job::newId);
    }

    /** For tests: {@code ids} gives each new job's id. */
    JobIntake(Path workRoot, Supplier<String> ids) {
        this.workRoot = workRoot;
        this.ids = ids;
    }

    /**
     * @throws IOException if the file or its ticket can't be moved (still locked, gone, ...);
     *     both are left where they were and nothing is left behind in the work folder
     */
    public Job claim(Path file) throws IOException {
        String id = ids.get();
        Path workDir = workRoot.resolve(id);
        Files.createDirectories(workDir);
        Path ticket = SetJobPropsFromTextFileStep.ticketFor(file);
        Path claimedTicket = null;
        Path input;
        try {
            claimedTicket = claimJobTicket(ticket, workDir);
            input = Files.move(file, workDir.resolve(file.getFileName()));
        } catch (IOException | RuntimeException e) {
            try {
                if (claimedTicket != null) {
                    Files.move(claimedTicket, ticket);
                }
                Files.deleteIfExists(workDir);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
                log.warn("{}: could not undo a failed claim of {}: {}", id, file, cleanup.toString());
            }
            throw e;
        }
        return new Job(id, input, workDir);
    }

    /**
     * Moves the input's ticket into the work folder, if there is one.
     *
     * @return where it went, or null when there's no ticket
     * @throws IOException if it's there but can't be moved
     */
    private static Path claimJobTicket(Path ticket, Path workDir) throws IOException {
        if (!Files.isRegularFile(ticket)) {
            return null;
        }
        try {
            return Files.move(ticket, workDir.resolve(ticket.getFileName()));
        } catch (NoSuchFileException e) {
            // Deleted since the check: the input goes without it. (Not rethrown: the watcher
            // reads NoSuchFileException as "the input is gone".)
            return null;
        }
    }
}
