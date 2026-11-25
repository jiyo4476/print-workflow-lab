package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The heap estimates, and what the PDF steps say when a file can't fit. */
public class HeapLimitTest {

    private static final long MB = 1024 * 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void estimatesGrowWithTheFile() {
        assertThat(HeapLimit.minimumHeap(0)).isEqualTo(24 * MB);
        assertThat(HeapLimit.recommendedHeap(0)).isEqualTo(64 * MB);
        assertThat(HeapLimit.minimumHeap(10 * MB)).isEqualTo(24 * MB + 60 * MB);
        assertThat(HeapLimit.recommendedHeap(10 * MB)).isEqualTo(64 * MB + 160 * MB);
    }

    @Test
    public void theMinimumIsBelowWhatTheMeasuredRunsNeeded() {
        // Measured: a 5.8 MB file split in 72 MB of heap but not 56; a 23 MB file in 384 MB but
        // not 256. The minimum must never be above a heap that worked.
        assertThat(HeapLimit.minimumHeap((long) (5.8 * MB))).isLessThan(72 * MB);
        assertThat(HeapLimit.minimumHeap(23 * MB)).isLessThan(384 * MB);
        // And the recommendation is above the heaps that failed, or at least what worked.
        assertThat(HeapLimit.recommendedHeap((long) (5.8 * MB))).isGreaterThan(72 * MB);
        assertThat(HeapLimit.recommendedHeap(23 * MB)).isGreaterThan(384 * MB);
    }

    @Test
    public void aFileThatFitsPasses() throws IOException {
        Path file = fileOfSize(5 * MB);

        assertThatCode(() -> HeapLimit.check(file, "SplitPdf", () -> 1024 * MB))
                .doesNotThrowAnyException();
    }

    @Test
    public void aHeapBetweenTheMinimumAndTheRecommendationStillPasses() throws IOException {
        Path file = fileOfSize(5 * MB);
        long between = (HeapLimit.minimumHeap(5 * MB) + HeapLimit.recommendedHeap(5 * MB)) / 2;

        assertThatCode(() -> HeapLimit.check(file, "SplitPdf", () -> between))
                .doesNotThrowAnyException();
    }

    @Test
    public void aFileThatCannotFitFailsAndSaysWhatToDo() throws IOException {
        Path file = fileOfSize(100 * MB);

        assertThatThrownBy(() -> HeapLimit.check(file, "SplitPdfByDPart", () -> 256 * MB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SplitPdfByDPart needs at least about 624 MB")
                .hasMessageContaining("100 MB PDF")
                .hasMessageContaining("1664 MB to run comfortably")
                .hasMessageContaining("limit is 256 MB")
                .hasMessageContaining("-Xmx1664m")
                .hasMessageContaining("JAVA_TOOL_OPTIONS")
                .hasMessageContaining("README");
    }

    @Test
    public void outOfMemoryBecomesAJobFailureThatNamesTheStepAndTheHeap() throws IOException {
        Path file = fileOfSize(20 * MB);
        OutOfMemoryError cause = new OutOfMemoryError("Java heap space");

        IllegalStateException e = HeapLimit.outOfMemory(file, "SplitPdf", 128 * MB, cause);

        assertThat(e).hasMessageContaining("SplitPdf ran out of memory reading a 20 MB PDF")
                .hasMessageContaining("heap limit of 128 MB")
                .hasMessageContaining("384 MB would be safer")
                .hasMessageContaining("-Xmx384m");
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    public void outOfMemoryForAFileThatIsGoneStillMakesAMessage() {
        Path missing = folder.getRoot().toPath().resolve("gone.pdf");

        IllegalStateException e = HeapLimit.outOfMemory(missing, "SplitPdf", 64 * MB,
                new OutOfMemoryError());

        assertThat(e).hasMessageContaining("ran out of memory reading a 0 MB PDF");
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
