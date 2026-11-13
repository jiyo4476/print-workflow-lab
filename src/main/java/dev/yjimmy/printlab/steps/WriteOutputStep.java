package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public final class WriteOutputStep implements Step {

    private final Path outputDir;

    public WriteOutputStep(Map<String, Object> rawParams) {
        this.outputDir = Path.of(new Params(rawParams).string("outputDir", "output"));
    }

    @Override
    public void execute(Job job) throws IOException {
        Path jobOut = outputDir.resolve(job.id());
        Files.createDirectories(jobOut);

        Set<String> columns = new LinkedHashSet<>(List.of("position", "sequence", "file"));
        job.documents().forEach(d -> columns.addAll(d.properties().keySet()));

        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", columns));
        Map<String, Integer> positions = new HashMap<>();
        for (Document doc : job.documents()) {
            String queue = doc.property(RouteDocumentsStep.QUEUE);
            String folder = queue == null ? "" : RouteDocumentsStep.checkQueueName(queue) + "/";
            int position = positions.merge(folder, 1, Integer::sum);
            String fileName = folder + String.format("%05d.pdf", position);
            Path target = jobOut.resolve(fileName);
            Files.createDirectories(target.getParent());
            Files.copy(doc.file(), target);
            List<String> row = new ArrayList<>();
            for (String col : columns) {
                row.add(csv(switch (col) {
                    case "position" -> String.valueOf(position);
                    case "sequence" -> String.valueOf(doc.sequence());
                    case "file" -> fileName;
                    default -> doc.properties().getOrDefault(col, "");
                }));
            }
            lines.add(String.join(",", row));
        }
        Files.write(jobOut.resolve("documents.csv"), lines);
        job.properties().put("outputDir", jobOut.toString());
    }

    private static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
