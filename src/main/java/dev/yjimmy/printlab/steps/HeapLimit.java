package dev.yjimmy.printlab.steps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much Java heap the PDF steps need for a file, so a file that can't fit fails its job with a
 * clear message instead of running out of memory part way through.
 *
 * <p>PDFBox, and this project's own reader, keep the whole page structure of a PDF in memory, so
 * the heap needed grows with the file. Measured with generated statement runs: a 5.8 MB file
 * (5,000 statements) split in 72 MB of heap but not 56 MB, and a 23 MB file (20,000 statements)
 * split in 384 MB but not 256 MB, so about 10 to 16 times the file's size. The estimates are
 * deliberately two figures:
 * <ul>
 *   <li>the <b>minimum</b>, 6 times the size plus 24 MB, is below what any file measured needed;
 *       a step fails before reading a file when the heap can't hold even that;</li>
 *   <li>the <b>recommended</b> heap, 16 times the size plus 64 MB, is above it; a step logs a
 *       warning when the heap is smaller, and the messages name it as what to configure.</li>
 * </ul>
 * Files with larger pages, many images or deeply nested structures can need more than either; the
 * steps also turn an {@link OutOfMemoryError} into a failed job (see {@link #outOfMemory}).
 */
final class HeapLimit {

    private static final Logger log = LoggerFactory.getLogger(HeapLimit.class);

    private static final long MB = 1024 * 1024;
    static final double MINIMUM_PER_BYTE = 6;
    static final long MINIMUM_BASE = 24 * MB;
    static final double RECOMMENDED_PER_BYTE = 16;
    static final long RECOMMENDED_BASE = 64 * MB;

    private HeapLimit() {
    }

    /** The least heap a file of {@code fileBytes} can be read in; less always fails. */
    static long minimumHeap(long fileBytes) {
        return MINIMUM_BASE + (long) (MINIMUM_PER_BYTE * fileBytes);
    }

    /** The heap to give the JVM for a file of {@code fileBytes}. */
    static long recommendedHeap(long fileBytes) {
        return RECOMMENDED_BASE + (long) (RECOMMENDED_PER_BYTE * fileBytes);
    }

    /**
     * Fails if the JVM's heap limit ({@code maxHeap}) can't hold {@link #minimumHeap} for
     * {@code file}, and logs a warning if it can't hold {@link #recommendedHeap}.
     */
    static void check(Path file, String step, LongSupplier maxHeap) throws IOException {
        long size = Files.size(file);
        long max = maxHeap.getAsLong();
        if (max < minimumHeap(size)) {
            throw new IllegalStateException(step + " needs at least about " + megabytes(minimumHeap(size))
                    + " MB of Java heap to read a " + megabytes(size) + " MB PDF (and "
                    + megabytes(recommendedHeap(size)) + " MB to run comfortably), but this JVM's"
                    + " limit is " + megabytes(max) + " MB. " + advice(size));
        }
        if (max < recommendedHeap(size)) {
            log.warn("{}: a {} MB PDF is best split with {} MB of Java heap, but this JVM's limit is"
                    + " {} MB; it may run out of memory. {}", step, megabytes(size),
                    megabytes(recommendedHeap(size)), megabytes(max), advice(size));
        }
    }

    /**
     * The failure to throw for an {@link OutOfMemoryError} raised while a step read or wrote
     * {@code file}. The error is only about this job, whose objects are unreachable once the step
     * is left, so the JVM and the job queue carry on.
     */
    static IllegalStateException outOfMemory(Path file, String step, long maxHeap,
            OutOfMemoryError cause) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        return new IllegalStateException(step + " ran out of memory reading a " + megabytes(size)
                + " MB PDF with a heap limit of " + megabytes(maxHeap) + " MB; about "
                + megabytes(recommendedHeap(size)) + " MB would be safer. " + advice(size), cause);
    }

    private static String advice(long size) {
        return "Start the JVM with more heap (-Xmx" + megabytes(recommendedHeap(size)) + "m, or"
                + " JAVA_TOOL_OPTIONS=-Xmx" + megabytes(recommendedHeap(size)) + "m), or split the"
                + " file into smaller ones first. See the README, \"Memory\".";
    }

    /** Whole megabytes, rounded up, so a message never understates what's needed. */
    static long megabytes(long bytes) {
        return (bytes + MB - 1) / MB;
    }
}
