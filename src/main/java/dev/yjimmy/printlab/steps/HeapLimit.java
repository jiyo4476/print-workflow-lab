package dev.yjimmy.printlab.steps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much Java heap the PDF steps need for a file, and what happens when there isn't enough: the
 * job fails with a message that says what to do, instead of the worker dying of an
 * {@link OutOfMemoryError} with the job stranded in the work folder.
 *
 * <p>PDFBox, and this project's own reader, keep the page structure of a PDF in memory, so for the
 * text-heavy statement runs measured the heap needed grows with the file: a 5.8 MB file (5,000
 * statements) split in 72 MB of heap but not 56 MB, and a 23 MB file (20,000 statements) in 384 MB
 * but not 256 MB, about 10 to 16 times the file's size. {@link #recommendedHeap} is above both
 * (16 times plus 64 MB).
 *
 * <p>That ratio is for files like those. It is not a rule for every PDF: a 100 MB PDF that was one
 * large image split in 64 MB, because PDFBox reads stream data from the file as it is needed. So the
 * estimate is only ever <b>advice</b>: {@link #run} logs a warning when the JVM's heap limit is
 * below it and then lets the step try. The step is what knows whether it fits, and
 * {@link #run} turns running out of Java heap space into a failed job.
 *
 * <p>Limits: the figures assume the JVM is otherwise idle, and {@code maxMemory()} is the ceiling,
 * not what is free, so under Tomcat (where the dashboard shares the heap) a file that passes can
 * still not fit. Only running out of <em>heap</em> is converted; running out of native threads,
 * direct buffers or metaspace has nothing to do with the file and is left to escape as before. A
 * heap far too small can spend minutes in garbage collection before the failure comes (the
 * 5.8 MB file in 62 MB took about 100 seconds).
 */
final class HeapLimit {

    private static final Logger log = LoggerFactory.getLogger(HeapLimit.class);

    private static final long MB = 1024 * 1024;
    static final double RECOMMENDED_PER_BYTE = 16;
    static final long RECOMMENDED_BASE = 64 * MB;

    private HeapLimit() {
    }

    /** What the step does with the file; may throw anything a step may. */
    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    /** The heap to give the JVM for a text-heavy PDF of {@code fileBytes}. */
    static long recommendedHeap(long fileBytes) {
        return RECOMMENDED_BASE + (long) (RECOMMENDED_PER_BYTE * fileBytes);
    }

    /**
     * Runs {@code work} on {@code file} for {@code step}: warns first if the heap limit is below
     * {@link #recommendedHeap}, and turns an {@link OutOfMemoryError} for lack of Java heap space
     * into an {@link IllegalStateException} that says what to do. Any other exception, and any
     * other kind of {@code OutOfMemoryError}, passes through unchanged.
     */
    static void run(Path file, String step, LongSupplier maxHeap, Work work) throws Exception {
        warnIfSmall(file, step, maxHeap.getAsLong());
        try {
            work.run();
        } catch (OutOfMemoryError e) {
            if (!isHeapExhaustion(e)) {
                throw e;
            }
            throw outOfMemory(file, step, maxHeap.getAsLong(), e);
        }
    }

    /** Logs a warning if {@code maxHeap} is below the recommendation; true if it did. */
    static boolean warnIfSmall(Path file, String step, long maxHeap) throws IOException {
        long size = Files.size(file);
        if (maxHeap >= recommendedHeap(size)) {
            return false;
        }
        log.warn("{}: a {} MB PDF is best split with about {} MB of Java heap, but this JVM's limit"
                + " is {} MB; it may run out of memory, depending on what the file holds. {}",
                step, megabytes(size), megabytes(recommendedHeap(size)), megabytes(maxHeap),
                advice(size));
        return true;
    }

    /** Whether {@code e} is about the Java heap, which a bigger {@code -Xmx} fixes. */
    static boolean isHeapExhaustion(OutOfMemoryError e) {
        String message = e.getMessage();
        return message != null && (message.startsWith("Java heap space")
                || message.startsWith("GC overhead limit exceeded"));
    }

    /**
     * The failure to throw for an {@link OutOfMemoryError} raised while a step read or wrote
     * {@code file}. The error is only about this job, whose objects are unreachable once the step
     * is left, so the JVM and the job queue carry on; documents the step had already written stay in
     * the job's work folder.
     */
    static IllegalStateException outOfMemory(Path file, String step, long maxHeap,
            OutOfMemoryError cause) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        return new IllegalStateException(step + " ran out of memory (" + cause.getMessage()
                + ") reading a " + megabytes(size) + " MB PDF with a heap limit of "
                + megabytes(maxHeap) + " MB; about " + megabytes(recommendedHeap(size))
                + " MB would be safer for a file like this. " + advice(size), cause);
    }

    private static String advice(long size) {
        return "Start the JVM with more heap (-Xmx" + megabytes(recommendedHeap(size)) + "m, or"
                + " JAVA_TOOL_OPTIONS=-Xmx" + megabytes(recommendedHeap(size)) + "m), or split the"
                + " file into smaller ones first. See the README, \"Memory and large files\".";
    }

    /** Whole megabytes, rounded up, so a message never understates what's needed. */
    static long megabytes(long bytes) {
        return (bytes + MB - 1) / MB;
    }
}
