package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The size of what a split step wrote, against what it read. */
public class SplitOutputTest {

    private static final long MB = 1024 * 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void onlyAnOutputThatIsBothBigAndManyTimesTheInputIsLarge() {
        assertThat(SplitOutput.isLarge(1 * MB, 49 * MB)).as("big ratio, small output").isFalse();
        assertThat(SplitOutput.isLarge(1 * MB, 60 * MB)).as("big and 60x").isTrue();
        assertThat(SplitOutput.isLarge(10 * MB, 60 * MB)).as("big but 6x").isFalse();
        assertThat(SplitOutput.isLarge(10 * MB, 100 * MB)).as("exactly 10x is not over").isFalse();
        assertThat(SplitOutput.isLarge(10 * MB, 100 * MB + 1)).as("just over 10x").isTrue();
        assertThat(SplitOutput.isLarge(0, 60 * MB)).as("from an empty file").isTrue();
        assertThat(SplitOutput.isLarge(1 * MB, 50 * MB)).as("at the size floor, 50x").isTrue();
    }

    @Test
    public void recordsTheTotalSizeOfTheDocuments() throws IOException {
        Job job = jobWithInputOfSize(1000);
        addDocument(job, 1, 300);
        addDocument(job, 2, 450);

        SplitOutput.record(job, "SplitPdf");

        assertThat(job.properties()).containsEntry("documentBytes", "750");
    }

    @Test
    public void recordsZeroForAJobWithNoDocuments() throws IOException {
        Job job = jobWithInputOfSize(10);

        SplitOutput.record(job, "SplitPdf");

        assertThat(job.properties()).containsEntry("documentBytes", "0");
    }

    @Test
    public void aHugeRatioIsLoggedAndStillRecorded() throws IOException {
        // 60 MB of documents from a 1 KB file: over both limits, so it warns; sparse files cost
        // nothing to make.
        Job job = jobWithInputOfSize(1024);
        Path big = folder.newFile().toPath();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(60 * MB);
        }
        job.documents().add(new Document(1, big, new LinkedHashMap<>()));

        SplitOutput.record(job, "SplitPdf");

        assertThat(job.properties()).containsEntry("documentBytes", String.valueOf(60 * MB));
    }

    private Job jobWithInputOfSize(int bytes) throws IOException {
        Path input = Files.write(folder.newFile().toPath(), new byte[bytes]);
        return TestJobs.newJob(folder.getRoot().toPath(), input);
    }

    private void addDocument(Job job, int sequence, int bytes) throws IOException {
        Path file = Files.write(folder.newFile().toPath(), new byte[bytes]);
        job.documents().add(new Document(sequence, file, new LinkedHashMap<>()));
    }
}
