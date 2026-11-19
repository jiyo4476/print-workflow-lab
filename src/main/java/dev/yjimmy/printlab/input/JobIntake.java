package dev.yjimmy.printlab.input;

import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Claims a file from a hot folder as a new job: moves it into {@code <workRoot>/<jobId>/}, so it
 * can never be picked up twice, and returns the job. This is the {@link HotFolderWatcher}'s
 * handler contract: if the move fails the file stays where it was, and the watcher retries it.
 */
public final class JobIntake {

    private final Path workRoot;

    public JobIntake(Path workRoot) {
        this.workRoot = workRoot;
    }

    /**
     * @throws IOException if the file can't be moved (still locked, gone, ...); nothing is left
     *     behind in the work folder
     */
    public Job claim(Path file) throws IOException {
        String id = Job.newId();
        Path workDir = workRoot.resolve(id);
        Files.createDirectories(workDir);
        try {
            Path input = Files.move(file, workDir.resolve(file.getFileName()));
            return new Job(id, input, workDir);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(workDir);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}
