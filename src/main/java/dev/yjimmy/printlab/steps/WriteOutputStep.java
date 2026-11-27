package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies the job's documents, in their current (possibly sorted) order, into
 * {@code <outputDir>/<jobId>/} and writes {@code documents.csv}: one row per document with every
 * document property — a simple document properties file.
 *
 * <p>Documents with a {@code queue} property (set by {@link RouteDocumentsStep}) go into
 * {@code <jobId>/<queue>/}, numbered from 00001 within each queue, so each queue is its own print
 * file set. {@code position} in the CSV is the position within the document's queue, and
 * {@code file} is the path relative to {@code <jobId>/}. There's still one {@code documents.csv}
 * for the whole job, with a {@code queue} column. Documents without a queue go in
 * {@code <jobId>/} itself.
 *
 * <p>Output files keep the extension of the document's own file ({@code 00001.pdf},
 * {@code 00001.afp}). The CSV's first columns, {@code position}, {@code sequence} and
 * {@code file}, are reserved, and so is {@code inputSequence}, the last column: a document
 * property with one of those names fails the job rather than being silently replaced.
 *
 * <p>Reserved names and queue names are checked before anything is copied, so a bad document
 * leaves no partial output. If {@code <jobId>/} already exists, it's left over from an earlier
 * run of this same job that didn't finish (a retry, say), and it's replaced, so the output never
 * mixes files from two runs.
 */
public final class WriteOutputStep implements Step {

    /** The columns every row starts with; a document property can't use these names. */
    static final List<String> RESERVED_COLUMNS = List.of("position", "sequence", "file");

    /**
     * The last column: the document's position in the input before presort renumbered it. Always
     * present (equal to {@code sequence} when nothing was renumbered), so consumers never see a
     * missing column. Reserved like the others.
     */
    static final String INPUT_SEQUENCE = "inputSequence";

    private static final List<String> RESERVED_NAMES =
            List.of("position", "sequence", "file", INPUT_SEQUENCE);

    private final Path outputDir;

    public WriteOutputStep(Map<String, Object> rawParams) {
        this.outputDir = Path.of(new Params(rawParams).string("outputDir", "output"));
    }

    @Override
    public void execute(Job job) throws IOException {
        // Checked before anything is copied, so a failed job leaves no partial output.
        for (Document doc : job.documents()) {
            for (String reserved : RESERVED_NAMES) {
                if (doc.properties().containsKey(reserved)) {
                    throw new IllegalStateException("Document " + doc.sequence() + " has a '"
                            + reserved + "' property, but documents.csv reserves that column for"
                            + " its own " + reserved + "; rename the property (a SplitPdf region,"
                            + " say) in the workflow");
                }
            }
            String queue = doc.property(RouteDocumentsStep.QUEUE);
            if (queue != null) {
                RouteDocumentsStep.checkQueueName(queue);
            }
        }
        Path jobOut = outputDir.resolve(job.id());
        deleteRecursively(jobOut);
        Files.createDirectories(jobOut);

        Set<String> columns = new LinkedHashSet<>(RESERVED_COLUMNS);
        job.documents().forEach(d -> columns.addAll(d.properties().keySet()));
        columns.add(INPUT_SEQUENCE);

        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", columns.stream().map(WriteOutputStep::csv).toList()));
        Map<String, Integer> positions = new HashMap<>();
        for (Document doc : job.documents()) {
            String queue = doc.property(RouteDocumentsStep.QUEUE);
            String folder = queue == null ? "" : queue + "/";
            int position = positions.merge(folder, 1, Integer::sum);
            String fileName = folder + String.format("%05d", position) + extension(doc.file());
            Path target = jobOut.resolve(fileName);
            Files.createDirectories(target.getParent());
            Files.copy(doc.file(), target);
            List<String> row = new ArrayList<>();
            for (String col : columns) {
                row.add(csv(switch (col) {
                    case "position" -> String.valueOf(position);
                    case "sequence" -> String.valueOf(doc.sequence());
                    case INPUT_SEQUENCE -> String.valueOf(doc.inputSequence());
                    case "file" -> fileName;
                    default -> doc.properties().getOrDefault(col, "");
                }));
            }
            lines.add(String.join(",", row));
        }
        Files.write(jobOut.resolve("documents.csv"), lines);
        job.properties().put("outputDir", jobOut.toString());
    }

    /** Deletes {@code dir} and everything in it; does nothing if it doesn't exist. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            // Deepest first, so each folder is empty by the time it's deleted.
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * The document file's extension with its dot, lowercased ({@code .pdf}, {@code .afp}), or
     * nothing if it has none: output keeps the format the split step wrote.
     */
    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    /** Quotes a CSV field when it contains a comma, quote, or line break (RFC 4180). */
    static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
