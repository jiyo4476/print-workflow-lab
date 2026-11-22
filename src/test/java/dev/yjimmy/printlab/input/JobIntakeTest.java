package dev.yjimmy.printlab.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.FileAlreadyExistsException;
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
    public void jobTicketMovesWithTheInput() throws Exception {
        Path hot = Files.createDirectories(tmp.getRoot().toPath().resolve("hot"));
        Files.writeString(hot.resolve("run.pdf.jobprops"), "customer=ACME");
        Files.writeString(hot.resolve("other.pdf.jobprops"), "customer=OTHER");
        Path file = Files.writeString(hot.resolve("run.pdf"), "pdf");

        Job job = new JobIntake(tmp.getRoot().toPath().resolve("work")).claim(file);

        assertEquals("ticket in the work folder", "customer=ACME",
                Files.readString(job.workDir().resolve("run.pdf.jobprops")));
        assertFalse("ticket gone from the hot folder", Files.exists(hot.resolve("run.pdf.jobprops")));
        assertTrue("another input's ticket stays", Files.exists(hot.resolve("other.pdf.jobprops")));
    }

    @Test
    public void ticketInAnyCaseMovesWithTheInputUnderItsUsualName() throws Exception {
        Path hot = Files.createDirectories(tmp.getRoot().toPath().resolve("hot"));
        Files.writeString(hot.resolve("run.pdf.JOBPROPS"), "customer=ACME");
        Path file = Files.writeString(hot.resolve("run.pdf"), "pdf");

        Job job = new JobIntake(tmp.getRoot().toPath().resolve("work")).claim(file);

        assertEquals("ticket in the work folder", "customer=ACME",
                Files.readString(job.workDir().resolve("run.pdf.jobprops")));
        try (var left = Files.list(hot)) {
            assertThat(left).as("left in the hot folder").isEmpty();
        }
    }

    @Test
    public void claimForADeviceRecordsTheDevice() throws Exception {
        Path hot = Files.createDirectories(tmp.getRoot().toPath().resolve("hot"));
        Path file = Files.writeString(hot.resolve("run.pdf"), "pdf");

        Job job = new JobIntake(tmp.getRoot().toPath().resolve("work")).claim(file, "pdf-in");

        assertEquals("inputDevice", "pdf-in", job.properties().get(JobIntake.INPUT_DEVICE));
    }

    @Test
    public void ticketThatCannotBeMovedFailsTheClaimAndLeavesBothFiles() throws Exception {
        Path hot = Files.createDirectories(tmp.getRoot().toPath().resolve("hot"));
        Path work = tmp.getRoot().toPath().resolve("work");
        Path ticket = Files.writeString(hot.resolve("run.pdf.jobprops"), "customer=ACME");
        Path file = Files.writeString(hot.resolve("run.pdf"), "pdf");
        // Something already at the ticket's destination makes its move fail, as a lock would.
        Files.writeString(Files.createDirectories(work.resolve("job00001"))
                .resolve("run.pdf.jobprops"), "in the way");

        assertThrows(FileAlreadyExistsException.class,
                () -> new JobIntake(work, () -> "job00001").claim(file));

        assertEquals("input still in the hot folder", "pdf", Files.readString(file));
        assertEquals("ticket still in the hot folder", "customer=ACME", Files.readString(ticket));
    }

    @Test
    public void failedInputMovePutsTheTicketBack() throws Exception {
        Path hot = Files.createDirectories(tmp.getRoot().toPath().resolve("hot"));
        Path work = tmp.getRoot().toPath().resolve("work");
        Path ticket = Files.writeString(hot.resolve("missing.pdf.jobprops"), "customer=ACME");

        assertThrows(NoSuchFileException.class,
                () -> new JobIntake(work).claim(hot.resolve("missing.pdf")));

        assertEquals("ticket back in the hot folder", "customer=ACME", Files.readString(ticket));
        try (var left = Files.list(work)) {
            assertThat(left).as("job folders left behind").isEmpty();
        }
    }

    @Test
    public void unclaimPutsTheInputAndTicketBackAndRemovesTheWorkFolder() throws Exception {
        Path hot = Files.createDirectories(tmp.getRoot().toPath().resolve("hot"));
        Files.writeString(hot.resolve("run.pdf.jobprops"), "customer=ACME");
        Path file = Files.writeString(hot.resolve("run.pdf"), "pdf");
        JobIntake intake = new JobIntake(tmp.getRoot().toPath().resolve("work"));
        Job job = intake.claim(file);

        intake.unclaim(job, hot);

        assertEquals("input back", "pdf", Files.readString(file));
        assertEquals("ticket back", "customer=ACME",
                Files.readString(hot.resolve("run.pdf.jobprops")));
        assertFalse("work folder removed", Files.exists(job.workDir()));
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
