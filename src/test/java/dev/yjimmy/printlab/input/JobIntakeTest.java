package dev.yjimmy.printlab.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JobIntakeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void movesTheFileIntoANewJobsWorkFolder() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path file = Files.writeString(Files.createDirectories(root.resolve("hot")).resolve("run.pdf"),
                "pdf");

        Job job = new JobIntake(root.resolve("work")).claim(file);

        assertFalse("gone from the hot folder", Files.exists(file));
        assertEquals("work dir", root.resolve("work").resolve(job.id()), job.workDir());
        assertEquals("input", job.workDir().resolve("run.pdf"), job.inputFile());
        assertEquals("content", "pdf", Files.readString(job.inputFile()));
    }

    @Test
    public void aFailedMoveLeavesNoWorkFolderBehind() throws Exception {
        Path work = tmp.getRoot().toPath().resolve("work");
        Path missing = tmp.getRoot().toPath().resolve("missing.pdf");

        assertThrows(NoSuchFileException.class, () -> new JobIntake(work).claim(missing));

        assertTrue("work root exists", Files.isDirectory(work));
        try (var left = Files.list(work)) {
            assertThat(left).as("job folders left behind").isEmpty();
        }
    }
}
