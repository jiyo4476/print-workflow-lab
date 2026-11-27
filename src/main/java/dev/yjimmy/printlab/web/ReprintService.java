package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.JdbcJobStore;
import dev.yjimmy.printlab.store.StoredDocument;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reprints selected documents as a new child job (PRD-15, FR-1 to FR-4). The child gets copies of
 * the documents' output files and their properties, and records its parent job. It is queued on
 * the job thread to run through the {@code reprint} workflow.
 *
 * <p>Nothing is created unless every document is found and all of them come from one job
 * (Q-06), so a bad request leaves no partial reprint behind.
 */
final class ReprintService {

    /** A reprint that was queued. */
    record Accepted(String jobId, String parentJobId, int documents) {}

    /** The job property a child job records its parent under. */
    static final String PARENT_PROPERTY = "parentJobId";
    /** The request file a child job takes as its input, which records what was asked for. */
    static final String REQUEST_FILE = "reprint.request";

    private final JdbcJobStore jobs;
    private final JobQueue queue;
    private final Path workRoot;
    private final Path outputRoot;

    ReprintService(JdbcJobStore jobs, JobQueue queue, Path workRoot, Path outputRoot) {
        this.jobs = jobs;
        this.queue = queue;
        this.workRoot = workRoot;
        this.outputRoot = outputRoot;
    }

    /**
     * Creates and queues the child job.
     *
     * @throws ApiException 400 if no documents are named, or they come from more than one job;
     *     404 if one of them doesn't exist; 409 if one has no file in the output folder
     */
    Accepted reprint(List<Long> requested) throws IOException {
        Set<Long> ids = new LinkedHashSet<>(requested);
        if (ids.isEmpty()) {
            throw ApiException.badRequest("list the ids of the documents to reprint");
        }
        List<StoredDocument> docs = new ArrayList<>();
        for (long id : ids) {
            docs.add(jobs.findDocument(id).orElseThrow(
                    () -> ApiException.notFound("there is no document with id " + id)));
        }
        Set<String> sources = docs.stream().map(StoredDocument::jobId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sources.size() > 1) {
            throw ApiException.badRequest("a reprint takes documents from one job, but these come"
                    + " from jobs " + String.join(", ", sources) + "; reprint each job's"
                    + " documents separately");
        }
        String parent = sources.iterator().next();

        List<StoredDocument> parentDocs = jobs.findStoredDocuments(parent);
        List<Path> files = new ArrayList<>();
        for (StoredDocument doc : docs) {
            Optional<Path> file = OutputFiles.locate(outputRoot, doc, parentDocs);
            files.add(file.orElseThrow(() -> new ApiException(409, "document " + doc.id()
                    + " has no file in the output folder, so it can't be reprinted")));
        }

        String childId = Job.newId();
        Path workDir = workRoot.resolve(childId);
        Files.createDirectories(workDir);
        Path request = workDir.resolve(REQUEST_FILE);
        Files.writeString(request, "parentJobId=" + parent + System.lineSeparator()
                + "documentIds=" + ids.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")) + System.lineSeparator());
        Job job = new Job(childId, request, workDir);
        job.properties().put(PARENT_PROPERTY, parent);
        for (int i = 0; i < docs.size(); i++) {
            int sequence = i + 1;
            Path source = files.get(i);
            Path copy = workDir.resolve(String.format("%05d", sequence) + extension(source));
            Files.copy(source, copy);
            Document original = docs.get(i).document();
            job.documents().add(new Document(sequence, copy, original.properties(), sequence));
        }
        jobs.save(job);
        try {
            queue.submit(job, () -> { });
        } catch (IllegalStateException e) {
            // A job that never reached the queue must not stay in the database as received.
            jobs.deleteJob(childId);
            throw new ApiException(503, e.getMessage());
        }
        return new Accepted(childId, parent, docs.size());
    }

    /** The file's extension with its dot, or an empty string if it has none. */
    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}
