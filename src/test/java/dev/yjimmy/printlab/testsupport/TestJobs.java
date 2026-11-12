package dev.yjimmy.printlab.testsupport;

import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds jobs whose work directory lives under a test's temp folder. */
public final class TestJobs {

    /** Fixed id so tests can predict output paths such as {@code output/test0001/}. */
    public static final String DEFAULT_ID = "test0001";

    private TestJobs() {}

    public static Job newJob(Path tempDir, Path input) throws IOException {
        return newJob(DEFAULT_ID, tempDir, input);
    }

    public static Job newJob(String id, Path tempDir, Path input) throws IOException {
        Path workDir = Files.createDirectories(tempDir.resolve("work").resolve(id));
        return new Job(id, input, workDir);
    }
}
