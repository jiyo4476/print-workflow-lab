package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobStore;
import dev.yjimmy.printlab.workflow.JobSummary;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps jobs and their documents in the database (PRD-13). {@link #save} writes a job's row,
 * properties, steps and documents in one transaction, so a failure part way leaves nothing
 * behind. The read methods are for the API (PRD-14) and the reprint and retry actions.
 */
public final class JdbcJobStore implements JobStore {

    private final Database db;
    private final Clock clock;
    private final JobRepository jobs = new JobRepository();
    private final DocumentRepository documents = new DocumentRepository();
    /**
     * The documents each unfinished job last saved. A job's documents rarely change between
     * steps, so an unchanged list is not written again. Entries are dropped once the job is
     * complete or failed, so the map only holds the jobs in progress.
     */
    private final Map<String, List<Document>> savedDocuments = new ConcurrentHashMap<>();

    /** @param clock gives the time a job is first saved, which orders the job lists */
    public JdbcJobStore(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public void save(Job job) throws IOException {
        try (Connection c = db.connect()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                jobs.upsert(c, JobSummary.of(job), clock.instant());
                jobs.replaceProperties(c, job.id(), job.properties());
                jobs.replaceSteps(c, job.id(), job.stepRecords());
                boolean finished = job.state() == JobState.COMPLETE || job.state() == JobState.ERROR;
                // A finished job's documents are always written in full, as a final record.
                if (finished || !job.documents().equals(savedDocuments.get(job.id()))) {
                    documents.replaceAll(c, job.id(), job.documents());
                }
                c.commit();
                if (finished) {
                    savedDocuments.remove(job.id());
                } else {
                    savedDocuments.put(job.id(), List.copyOf(job.documents()));
                }
            } catch (SQLException | RuntimeException e) {
                rollback(c, e);
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IOException("could not save job " + job.id() + ": " + e.getMessage(), e);
        }
    }

    private static void rollback(Connection c, Exception cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    /** The job, or empty if the database has no job with that id. */
    public Optional<StoredJob> findJob(String jobId) throws IOException {
        return read(c -> jobs.find(c, jobId));
    }

    /** One page of jobs, newest first. {@code offset} counts from the newest job. */
    public List<StoredJob> listNewestFirst(int limit, int offset) throws IOException {
        return read(c -> jobs.listNewestFirst(c, limit, offset));
    }

    /** One page of the jobs in {@code state}, newest first. */
    public List<StoredJob> listByState(JobState state, int limit, int offset) throws IOException {
        return read(c -> jobs.listByState(c, state, limit, offset));
    }

    /** The job's documents in sequence order. */
    public List<Document> findDocuments(String jobId) throws IOException {
        return read(c -> documents.findByJob(c, jobId));
    }

    /** Every document whose property {@code name} has exactly {@code value}, in any job. */
    public List<StoredDocument> findDocumentsByProperty(String name, String value)
            throws IOException {
        return read(c -> documents.findByPropertyValue(c, name, value));
    }

    /** Every document in {@code queue}, in any job. */
    public List<StoredDocument> findDocumentsByQueue(String queue) throws IOException {
        return read(c -> documents.findByQueue(c, queue));
    }

    /** Deletes a job and, with it, its steps, properties and documents. */
    public boolean deleteJob(String jobId) throws IOException {
        savedDocuments.remove(jobId);
        try (Connection c = db.connect()) {
            return jobs.delete(c, jobId);
        } catch (SQLException e) {
            throw new IOException("could not delete job " + jobId + ": " + e.getMessage(), e);
        }
    }

    /** A query's result. */
    @FunctionalInterface
    private interface Query<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T read(Query<T> query) throws IOException {
        try (Connection c = db.connect()) {
            return query.run(c);
        } catch (SQLException e) {
            throw new IOException("could not read from " + db.url() + ": " + e.getMessage(), e);
        }
    }
}
