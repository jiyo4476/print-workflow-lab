package dev.yjimmy.printlab.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a job's {@link JobSummary} to {@code <workDir>/job.json}, and reads it back.
 *
 * <p>The file is written to a temp file in the same folder and then moved over the old one, so
 * anyone reading {@code job.json} sees either the previous summary or the new one, never half of
 * one.
 */
public final class JobSummaryWriter {

    public static final String FILE_NAME = "job.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Writes the job's current summary and returns the path of {@code job.json}. */
    public Path write(Job job) throws IOException {
        Path target = job.workDir().resolve(FILE_NAME);
        Path temp = Files.createTempFile(job.workDir(), "job-", ".json.tmp");
        try {
            JSON.writeValue(temp.toFile(), JobSummary.of(job));
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    public static JobSummary read(Path file) throws IOException {
        return JSON.readValue(file.toFile(), JobSummary.class);
    }
}
