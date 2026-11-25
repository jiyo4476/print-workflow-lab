package dev.yjimmy.printlab.steps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much Java heap the PDF steps need for a file, and what happens when there isn't enough: the
 * job fails with a message that says what to do, instead of the worker dying of an
 * {@link OutOfMemoryError} with the job stranded in the work folder.
 *
 * <p>PDFBox, and this project's own reader, turn every object of a PDF that they reach into Java
 * objects, so the heap needed follows the file's <em>object count</em> (see {@link PdfSize}), not
 * its size on disk. Measured with {@code SplitPdfByDPart}, the smallest heap that worked (the heap
 * just below it failed):
 * <table>
 *   <caption>Smallest working heap by file shape</caption>
 *   <tr><th>File</th><th>Size</th><th>Objects</th><th>Heap</th></tr>
 *   <tr><td>5,000 statements</td><td>5.8 MB</td><td>50,498</td><td>about 58 MB</td></tr>
 *   <tr><td>20,000 statements</td><td>23 MB</td><td>201,045</td><td>about 270 MB</td></tr>
 *   <tr><td>200,000 blank pages</td><td>2 MB</td><td>201,409</td><td>about 336 MB</td></tr>
 *   <tr><td>400,000 blank pages (not used to fit the formula)</td><td>4 MB</td><td>402,409</td>
 *       <td>about 670 MB</td></tr>
 *   <tr><td>100,000 pages with a drawing</td><td>12 MB</td><td>301,208</td><td>about 304 MB</td></tr>
 *   <tr><td>5 incompressible 4 MB images</td><td>19 MB</td><td>42</td><td>32 MB or less</td></tr>
 * </table>
 * That is 0.7 to 1.6 KB per object (above a base of about 24 MB), and it scaled linearly when the
 * blank-pages file was doubled. Per byte of file it is anywhere from 0.2 to 170 times, and per
 * page 1.6 to 7 KB, so neither of those predicts it. The recommendation is {@value #BASE_MB} MB,
 * plus 1.8 KB per object, plus the file's size once more (this project's reader holds the file's
 * bytes): above every measurement, and about 1.1 to 2.7 times the smallest heap that worked.
 *
 * <p>It is only ever <b>advice</b>: {@link #run} logs a warning when the JVM's heap limit is below
 * it and then lets the step try, because the step is what knows whether it fits. If the trailer
 * gives no object count, the estimate falls back to 16 times the file's size, which is what
 * the statement runs needed before the object count was known to matter. {@link #run} also turns
 * running out of Java heap space into a failed job.
 *
 * <p>Limits: the figures assume the JVM is otherwise idle, and {@code maxMemory()} is the ceiling,
 * not what is free, so under Tomcat (where the dashboard shares the heap) a file that passes can
 * still not fit. Only running out of <em>heap</em> is converted; running out of native threads,
 * direct buffers or metaspace has nothing to do with the file and is left to escape as before. A
 * heap far too small can spend minutes in garbage collection before the failure comes (the
 * 5.8 MB file in 62 MB took about 100 seconds). Five shapes are not every PDF: a file with huge
 * objects (very large dictionaries or arrays) could need more per object.
 */
final class HeapLimit {

    private static final Logger log = LoggerFactory.getLogger(HeapLimit.class);

    private static final long MB = 1024 * 1024;
    static final long BASE_MB = 64;
    static final long BASE = BASE_MB * MB;
    /**
     * About 1.8 KB: above the 1.64 KB per object that the worst measured file needed once the base
     * is taken off, so the recommendation stays above the need as files get large and the base
     * stops mattering.
     */
    static final long PER_OBJECT = 1843;
    /** The size-based figure used when the object count can't be read. */
    static final double PER_BYTE_WITHOUT_OBJECTS = 16;

    private HeapLimit() {
    }

    /** What the step does with the file; may throw anything a step may. */
    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    /** The heap to give the JVM for a PDF of {@code fileBytes} with {@code objects} objects. */
    static long recommendedHeap(long fileBytes, OptionalLong objects) {
        if (objects.isEmpty()) {
            return BASE + (long) (PER_BYTE_WITHOUT_OBJECTS * fileBytes);
        }
        return BASE + PER_OBJECT * objects.getAsLong() + fileBytes;
    }

    /** The heap to give the JVM for the PDF at {@code file}. */
    static long recommendedHeap(Path file) throws IOException {
        return recommendedHeap(Files.size(file), PdfSize.objects(file));
    }

    /**
     * Runs {@code work} on {@code file} for {@code step}: warns first if the heap limit is below
     * {@link #recommendedHeap(Path)}, and turns an {@link OutOfMemoryError} for lack of Java heap
     * space into an {@link IllegalStateException} that says what to do. Any other exception, and
     * any other kind of {@code OutOfMemoryError}, passes through unchanged.
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
        OptionalLong objects = PdfSize.objects(file);
        long recommended = recommendedHeap(size, objects);
        if (maxHeap >= recommended) {
            return false;
        }
        log.warn("{}: {} is best split with about {} MB of Java heap, but this JVM's limit is {} MB;"
                + " it may run out of memory, depending on what the file holds. {}", step,
                describe(size, objects), megabytes(recommended), megabytes(maxHeap),
                advice(recommended));
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
        OptionalLong objects = PdfSize.objects(file);
        long recommended = recommendedHeap(size, objects);
        return new IllegalStateException(step + " ran out of memory (" + cause.getMessage()
                + ") reading " + describe(size, objects) + " with a heap limit of "
                + megabytes(maxHeap) + " MB; about " + megabytes(recommended)
                + " MB would be safer for a file like this. " + advice(recommended), cause);
    }

    /** {@code a 6 MB PDF of 50,498 objects}, or just the size when the count isn't known. */
    private static String describe(long size, OptionalLong objects) {
        return "a " + megabytes(size) + " MB PDF" + (objects.isPresent()
                ? " of " + String.format("%,d", objects.getAsLong()) + " objects" : "");
    }

    private static String advice(long recommended) {
        return "Start the JVM with more heap (-Xmx" + megabytes(recommended) + "m, or"
                + " JAVA_TOOL_OPTIONS=-Xmx" + megabytes(recommended) + "m), or split the"
                + " file into smaller ones first. See the README, \"Memory and large files\".";
    }

    /** Whole megabytes, rounded up, so a message never understates what's needed. */
    static long megabytes(long bytes) {
        return (bytes + MB - 1) / MB;
    }
}
