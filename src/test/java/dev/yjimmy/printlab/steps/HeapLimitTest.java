package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.OptionalLong;
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
    public void theRecommendationGrowsWithTheObjectsAndTheFile() {
        assertThat(HeapLimit.recommendedHeap(0, OptionalLong.of(0))).isEqualTo(64 * MB);
        assertThat(HeapLimit.recommendedHeap(10 * MB, OptionalLong.of(100_000)))
                .isEqualTo(64 * MB + 100_000 * 1843L + 10 * MB);
    }

    @Test
    public void withoutAnObjectCountItFallsBackToSixteenTimesTheFile() {
        assertThat(HeapLimit.recommendedHeap(10 * MB, OptionalLong.empty()))
                .isEqualTo(64 * MB + 160 * MB);
    }

    /**
     * The files the formula was fitted to: size in bytes, objects, and the smallest heap in MB
     * that split them (the heap just below it failed). If a change to the formula, or to the
     * steps, drops the recommendation below one of them, the formula needs measuring again.
     */
    private static final long[][] MEASURED = {
        // bytes,        objects, smallest working heap (MB)
        {5_800_000L,      50_498, 58},    // 5,000 statements
        {23_250_000L,    201_045, 270},   // 20,000 statements
        {2_035_000L,     201_409, 336},   // 200,000 blank pages
        {12_569_000L,    301_208, 304},   // 100,000 pages with a drawing
        {19_563_000L,         42, 32},    // 5 incompressible images
        {4_073_000L,     402_409, 670},   // 400,000 blank pages (not used to fit the formula)
    };

    @Test
    public void theRecommendationStaysAboveTheNeedAsFilesGrowVeryLarge() {
        // The base stops mattering at millions of objects; the per-object term must carry it. The
        // worst measured file needed about 1.64 KB per object once the 24 MB base is taken off.
        long objects = 5_000_000;
        long needed = 24 * MB + (long) (1.64 * 1024 * objects);

        assertThat(HeapLimit.recommendedHeap(40 * MB, OptionalLong.of(objects)))
                .isGreaterThan(needed);
    }

    @Test
    public void theRecommendationIsAboveTheSmallestHeapThatWorkedForEveryMeasuredFile() {
        for (long[] file : MEASURED) {
            long recommended = HeapLimit.recommendedHeap(file[0], OptionalLong.of(file[1]));

            assertThat(recommended).as("file of %d objects", file[1]).isGreaterThan(file[2] * MB);
            // And not wildly above it: more than 3x would warn about files that are fine.
            assertThat(recommended).as("file of %d objects", file[1]).isLessThan(3 * file[2] * MB
                    + 64 * MB);
        }
    }

    @Test
    public void theOldSizeBasedRuleWouldHaveBeenWrongForTwoOfTheMeasuredFiles() {
        // Why the object count is used: 16 times the size, plus 64 MB, misses the 2 MB file of
        // blank pages by three times and overshoots the image file by more than ten.
        long blank = 64 * MB + 16 * MEASURED[2][0];
        long image = 64 * MB + 16 * MEASURED[4][0];

        assertThat(blank).isLessThan(MEASURED[2][2] * MB);
        assertThat(image).isGreaterThan(10 * MEASURED[4][2] * MB);
    }

    @Test
    public void aWarningIsGivenOnlyBelowTheRecommendation() throws Exception {
        Path file = write(classicPdf());
        long recommended = HeapLimit.recommendedHeap(file);
        assertThat(PdfSize.objects(file)).as("the file has a countable trailer").isPresent();

        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended - 1)).isTrue();
        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended)).isFalse();
        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended + 1)).isFalse();
    }

    @Test
    public void aFileWithNoCountableTrailerStillGetsAWarningFromItsSize() throws Exception {
        Path file = fileOfSize(5 * MB);
        long recommended = HeapLimit.recommendedHeap(file);

        assertThat(recommended).isEqualTo(64 * MB + 80 * MB);
        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended - 1)).isTrue();
        assertThat(HeapLimit.warnIfSmall(file, "SplitPdf", recommended)).isFalse();
    }

    private Path write(byte[] bytes) throws IOException {
        return Files.write(folder.newFile().toPath(), bytes);
    }

    private static byte[] classicPdf() {
        return new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .build("/Root 1 0 R");
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
