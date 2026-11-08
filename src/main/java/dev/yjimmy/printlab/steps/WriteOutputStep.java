package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies the job's documents, in their current (possibly sorted) order, into
 * {@code <outputDir>/<jobId>/} and writes {@code documents.csv}: one row per document with every
 * document property — a simple document properties file.
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
        int position = 1;
        for (Document doc : job.documents()) {
            String fileName = String.format("%05d.pdf", position);
            Files.copy(doc.file(), jobOut.resolve(fileName));
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
            position++;
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
