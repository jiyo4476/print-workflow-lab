package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a split step wrote, against what it read. Splitting a PDF copies the fonts and images each
 * document uses into that document, so a run whose pages share a large resource can come out many
 * times bigger than it went in (a test with one embedded font made 885 KB into 32 MB). The total
 * is a job property, {@code documentBytes}, and a very large ratio is logged, so it's noticed
 * before the disk fills.
 */
final class SplitOutput {

    private static final Logger log = LoggerFactory.getLogger(SplitOutput.class);

    /** Output at least this many times the input is worth a warning... */
    static final double WARN_RATIO = 10;
    /** ...once it's big enough to matter. */
    static final long WARN_MIN_BYTES = 50L * 1024 * 1024;

    private SplitOutput() {
    }

    /** Sets {@code documentBytes} to the total size of the job's documents, and warns if large. */
    static void record(Job job, String step) throws IOException {
        long total = 0;
        for (Document document : job.documents()) {
            total += Files.size(document.file());
        }
        job.properties().put("documentBytes", String.valueOf(total));
        long input = Files.size(job.inputFile());
        if (isLarge(input, total)) {
            log.warn("{}: {} wrote {} MB of documents from a {} MB file ({}x). Each document"
                    + " carries its own copy of the fonts and images its pages use.", job.id(),
                    step, HeapLimit.megabytes(total), HeapLimit.megabytes(input),
                    total / Math.max(1, input));
        }
    }

    /** Whether {@code output} bytes from {@code input} bytes is large enough to warn about. */
    static boolean isLarge(long input, long output) {
        return output >= WARN_MIN_BYTES && output > WARN_RATIO * input;
    }
}
