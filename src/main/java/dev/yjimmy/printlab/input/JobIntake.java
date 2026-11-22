package dev.yjimmy.printlab.input;

import dev.yjimmy.printlab.steps.SetJobPropsFromTextFileStep;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.DirectoryStream;
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
     * @throws IOException if the file or its ticket can't be moved (still locked, gone, ...);
     *     both are left where they were and nothing is left behind in the work folder
     */
    public Job claim(Path file) throws IOException {
        String id = ids.get();
        Path workDir = workRoot.resolve(id);
        Files.createDirectories(workDir);
        Path expected = SetJobPropsFromTextFileStep.ticketFor(file);
        Path ticket = null;
        Path claimedTicket = null;
        Path input;
        try {
            ticket = findTicket(expected);
            claimedTicket = claimJobTicket(ticket, workDir, expected.getFileName().toString());
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
     * Undoes {@link #claim(Path)} for a job that hasn't run: moves its input, and its ticket if
     * it has one, back into {@code folder}, then removes the empty work folder. A watcher on that
     * folder picks the input up again.
     *
     * @throws IOException if the input can't be moved back; it stays in the job's work folder
     */
    public void unclaim(Job job, Path folder) throws IOException {
        Path input = job.inputFile();
        Path ticket = input.resolveSibling(input.getFileName() + SetJobPropsFromTextFileStep.EXTENSION);
        // The ticket first: the watcher may pick the input up the moment it's back.
        if (Files.isRegularFile(ticket)) {
            Files.move(ticket, folder.resolve(ticket.getFileName()));
        }
        Files.move(input, folder.resolve(input.getFileName()));
        try {
            Files.deleteIfExists(job.workDir());
        } catch (IOException e) {
            log.debug("{}: work folder {} not removed: {}", job.id(), job.workDir(), e.toString());
        }
    }

    /**
     * The ticket {@code expected} names, ignoring case in its name as the watcher does (so
     * {@code run.pdf.JOBPROPS} counts on Linux too), or null if there is none.
     */
    private static Path findTicket(Path expected) throws IOException {
        if (Files.isRegularFile(expected)) {
            return expected;
        }
        String name = expected.getFileName().toString();
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(expected.getParent(),
                path -> path.getFileName().toString().equalsIgnoreCase(name))) {
            for (Path sibling : siblings) {
                if (Files.isRegularFile(sibling)) {
                    return sibling;
                }
            }
        }
        return null;
    }

    /**
     * Moves the input's ticket into the work folder, if there is one, under the name
     * SetJobPropsFromTextFile looks for, whatever case it arrived in.
     *
     * @return where it went, or null when there's no ticket
     * @throws IOException if it's there but can't be moved
     */
    private static Path claimJobTicket(Path ticket, Path workDir, String name) throws IOException {
        if (ticket == null) {
            return null;
        }
        try {
            return Files.move(ticket, workDir.resolve(name));
        } catch (NoSuchFileException e) {
            // Deleted since the check: the input goes without it. (Not rethrown: the watcher
            // reads NoSuchFileException as "the input is gone".)
            return null;
        }
    }
}
