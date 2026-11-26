package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.steps.WriteOutputStep;
import dev.yjimmy.printlab.store.StoredDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds a document's file in the output folder. {@code WriteOutput} copies each document to
 * {@code <output>/<jobId>/<queue>/<NNNNN><ext>}, numbering them within the queue in sequence
 * order, so this works the number out the same way. The result is only returned if it lies inside
 * the job's own folder, so a job id or queue that points elsewhere finds nothing.
 */
final class OutputFiles {

    private OutputFiles() {}

    /**
     * @param jobDocuments all the documents of the document's job, in sequence order, with ids
     * @return the file, or empty if it isn't under the output folder or isn't written
     */
    static Optional<Path> locate(Path outputRoot, StoredDocument doc,
            List<StoredDocument> jobDocuments) {
        if (doc.document().file() == null) {
            return Optional.empty();
        }
        String queue = doc.document().property("queue");
        int position = 0;
        for (StoredDocument d : jobDocuments) {
            if (Objects.equals(queue, d.document().property("queue"))) {
                position++;
            }
            if (d.id() == doc.id()) {
                break;
            }
        }
        String folder = queue == null ? "" : queue + "/";
        String name = folder + String.format("%05d", position)
                + WriteOutputStep.extension(doc.document().file());

        Path root = outputRoot.toAbsolutePath().normalize();
        Path jobFolder = root.resolve(doc.jobId()).normalize();
        Path file = jobFolder.resolve(name).normalize();
        if (!jobFolder.startsWith(root) || !file.startsWith(jobFolder)
                || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(file);
    }
}
