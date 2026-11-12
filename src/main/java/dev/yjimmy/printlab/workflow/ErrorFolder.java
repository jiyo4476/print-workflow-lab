package dev.yjimmy.printlab.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Where failed jobs go: {@code <root>/<jobId>/} holds the job's input file and an
 * {@code error.txt} that says which step failed, when, and why. Each job gets its own folder, so
 * two failed inputs with the same file name never overwrite each other.
 */
public final class ErrorFolder {

    public static final String ERROR_FILE_NAME = "error.txt";

    private final Path root;

    public ErrorFolder(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Moves the failed job's input into its error folder and writes {@code error.txt}. If the
     * input can't be moved (already gone, or still locked on Windows), {@code error.txt} says
     * where it was left, so the operator can still find it.
     *
     * @return the job's error folder
     */
    public Path receive(Job job, String step, String message, Instant failedAt)
            throws IOException {
        Path dir = Files.createDirectories(root.resolve(job.id()));
        Path input = job.inputFile();

        String moveProblem = null;
        try {
            Files.move(input, dir.resolve(input.getFileName()));
        } catch (IOException e) {
            moveProblem = "input file could not be moved here (" + e
                    + "); it was left at " + input.toAbsolutePath();
        }

        StringBuilder text = new StringBuilder()
                .append("Job:    ").append(job.id()).append('\n')
                .append("Input:  ").append(input.getFileName()).append('\n')
                .append("Step:   ").append(step).append('\n')
                .append("Time:   ").append(failedAt).append('\n')
                .append("Error:  ").append(message).append('\n');
        if (moveProblem != null) {
            text.append("Note:   ").append(moveProblem).append('\n');
        }
        Files.writeString(dir.resolve(ERROR_FILE_NAME), text);
        return dir;
    }
}
