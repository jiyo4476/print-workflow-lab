package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The heap recommendation, and what the PDF steps do when memory runs short. */
public class HeapLimitTest {

    private static final long MB = 1024 * 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // The recommendation.

    @Test
    public void theRecommendationGrowsWithTheFile() {
        assertThat(HeapLimit.recommendedHeap(0)).isEqualTo(64 * MB);
        assertThat(HeapLimit.recommendedHeap(10 * MB)).isEqualTo(64 * MB + 160 * MB);
    }

    @Test
    public void theRecommendationIsAboveTheHeapsThatWorkedInTheMeasuredRuns() {
        // Measured: a 5.8 MB file split in 72 MB of heap but not 56; a 23 MB file in 384 MB but
        // not 256.
        assertThat(HeapLimit.recommendedHeap((long) (5.8 * MB))).isGreaterThan(72 * MB);
        assertThat(HeapLimit.recommendedHeap(23 * MB)).isGreaterThan(384 * MB);
    }

    @Test
    public void aWarningIsGivenOnlyBelowTheRecommendation() throws IOException {
        Path file = fileOfSize(5 * MB);
        long recommended = HeapLimit.recommendedHeap(5 * MB);

        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended - 1)).isTrue();
        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended)).isFalse();
        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended + 1)).isFalse();
    }

    @Test
    public void aMissingFileIsAnIoError() {
        Path missing = folder.getRoot().toPath().resolve("gone.pdf");

        assertThrows(NoSuchFileException.class,
                () -> HeapLimit.warnIfSmall(missing, "SplitPdf", 1024 * MB));
    }

    // Running the work.

    @Test
    public void theWorkIsDoneEvenWhenTheHeapIsBelowTheRecommendation() throws Exception {
        Path file = fileOfSize(5 * MB);
        AtomicBoolean ran = new AtomicBoolean();

        // 1 MB of heap for a 5 MB file: far below any recommendation, and no refusal.
        HeapLimit.run(file, "SplitPdf", () -> MB, () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    public void otherExceptionsPassThroughUnchanged() throws IOException {
        Path file = fileOfSize(1024);
        IllegalStateException failure = new IllegalStateException("no pages");

        assertThatThrownBy(() -> HeapLimit.run(file, "SplitPdf", () -> 1024 * MB, () -> {
            throw failure;
        })).isSameAs(failure);
    }

    @Test
    public void runningOutOfJavaHeapBecomesAJobFailureThatSaysWhatToDo() throws IOException {
        Path file = fileOfSize(20 * MB);
        for (String message : new String[] {"Java heap space", "GC overhead limit exceeded"}) {
            OutOfMemoryError cause = new OutOfMemoryError(message);

            assertThatThrownBy(() -> HeapLimit.run(file, "SplitPdf", () -> 128 * MB, () -> {
                throw cause;
            }))
                    .as(message)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SplitPdf ran out of memory (" + message + ")")
                    .hasMessageContaining("reading a 20 MB PDF")
                    .hasMessageContaining("heap limit of 128 MB")
                    .hasMessageContaining("384 MB would be safer")
                    .hasMessageContaining("-Xmx384m")
                    .hasMessageContaining("Memory and large files")
                    .hasCause(cause);
        }
    }

    @Test
    public void otherKindsOfOutOfMemoryAreLeftToEscape() throws IOException {
        Path file = fileOfSize(1024);
        // None of these is about the file, and a bigger -Xmx wouldn't help.
        String[] others = {"Metaspace", "unable to create native thread: possibly out of memory"
                + " or process/resource limits reached", "Direct buffer memory",
                "Requested array size exceeds VM limit", null};
        for (String message : others) {
            OutOfMemoryError error = message == null ? new OutOfMemoryError()
                    : new OutOfMemoryError(message);

            OutOfMemoryError thrown = assertThrows("" + message, OutOfMemoryError.class,
                    () -> HeapLimit.run(file, "SplitPdf", () -> 1024 * MB, () -> {
                        throw error;
                    }));

            assertThat(thrown).isSameAs(error);
        }
    }

    @Test
    public void onlyJavaHeapExhaustionCountsAsHeapExhaustion() {
        assertThat(HeapLimit.isHeapExhaustion(new OutOfMemoryError("Java heap space"))).isTrue();
        assertThat(HeapLimit.isHeapExhaustion(new OutOfMemoryError("Java heap space: failed"
                + " reallocation of scalar replaced objects"))).isTrue();
        assertThat(HeapLimit.isHeapExhaustion(new OutOfMemoryError("GC overhead limit exceeded")))
                .isTrue();
        assertThat(HeapLimit.isHeapExhaustion(new OutOfMemoryError("Metaspace"))).isFalse();
        assertThat(HeapLimit.isHeapExhaustion(new OutOfMemoryError())).isFalse();
    }

    @Test
    public void theFailureForAFileThatIsGoneStillMakesAMessage() {
        Path missing = folder.getRoot().toPath().resolve("gone.pdf");

        IllegalStateException e = HeapLimit.outOfMemory(missing, "SplitPdf", 64 * MB,
                new OutOfMemoryError("Java heap space"));

        assertThat(e).hasMessageContaining("ran out of memory (Java heap space) reading a 0 MB PDF");
    }

    @Test
    public void megabytesRoundUp() {
        assertThat(HeapLimit.megabytes(0)).isEqualTo(0);
        assertThat(HeapLimit.megabytes(1)).isEqualTo(1);
        assertThat(HeapLimit.megabytes(MB)).isEqualTo(1);
        assertThat(HeapLimit.megabytes(MB + 1)).isEqualTo(2);
    }

    /** A file of {@code size} bytes, made sparse so it costs nothing to write. */
    private Path fileOfSize(long size) throws IOException {
        Path file = folder.newFile().toPath();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
        }
        return file;
    }
}
