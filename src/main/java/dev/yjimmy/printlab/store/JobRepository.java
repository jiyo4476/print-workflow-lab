package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import dev.yjimmy.printlab.workflow.Phase;
import dev.yjimmy.printlab.workflow.StepOutcome;
import dev.yjimmy.printlab.workflow.StepRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code jobs}, {@code job_properties} and {@code job_steps} tables plain JDBC: every query is
 * a {@link PreparedStatement} with its values as parameters (NFR-1). The methods take the
 * connection, so {@link JdbcJobStore} can run several of them in one transaction.
 */
public final class JobRepository {

    /**
     * Inserts the job, or updates its state if it's already there. A new job gets
     * {@code createdAt}; an existing one keeps its original creation time.
     */
    public void upsert(Connection c, JobSummary job, Instant createdAt) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("""
                UPDATE jobs SET state = ?, phase = ?, current_step = ?, error_message = ?,
                    document_count = ? WHERE id = ?""")) {
            bindState(update, job);
            update.setString(6, job.id());
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = c.prepareStatement("""
                INSERT INTO jobs (state, phase, current_step, error_message, document_count,
                    id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            bindState(insert, job);
            insert.setString(6, job.id());
            insert.setTimestamp(7, Timestamp.from(createdAt));
            insert.executeUpdate();
        }
    }

    private static void bindState(PreparedStatement ps, JobSummary job) throws SQLException {
        ps.setString(1, job.state().name());
        ps.setString(2, job.phase().name());
        ps.setString(3, job.currentStep());
        ps.setString(4, job.errorMessage());
        ps.setInt(5, job.documentCount());
    }

    /** Replaces the job's properties with {@code properties}. */
    public void replaceProperties(Connection c, String jobId, Map<String, String> properties)
            throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM job_properties WHERE job_id = ?")) {
            delete.setString(1, jobId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO job_properties (job_id, prop_name, prop_value) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                insert.setString(1, jobId);
                insert.setString(2, e.getKey());
                insert.setString(3, e.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** Replaces the job's step history with {@code steps}, kept in the order given. */
    public void replaceSteps(Connection c, String jobId, List<StepRecord> steps)
            throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM job_steps WHERE job_id = ?")) {
            delete.setString(1, jobId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = c.prepareStatement("""
                INSERT INTO job_steps (job_id, step_position, step_name, phase, started_at,
                    finished_at, outcome) VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            for (int i = 0; i < steps.size(); i++) {
                StepRecord s = steps.get(i);
                insert.setString(1, jobId);
                insert.setInt(2, i + 1);
                insert.setString(3, s.name());
                insert.setString(4, s.phase().name());
                insert.setTimestamp(5, Timestamp.from(s.startedAt()));
                insert.setTimestamp(6, Timestamp.from(s.finishedAt()));
                insert.setString(7, s.outcome().name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** The job with its properties and step history, or empty if there's no such job. */
    public Optional<StoredJob> find(Connection c, String jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_JOB + " WHERE id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(load(c, rs)) : Optional.empty();
            }
        }
    }

    /** One page of jobs, newest first. {@code offset} counts from the newest job. */
    public List<StoredJob> listNewestFirst(Connection c, int limit, int offset) throws SQLException {
        return list(c, SELECT_JOB + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                null, limit, offset);
    }

    /** One page of the jobs in {@code state}, newest first. */
    public List<StoredJob> listByState(Connection c, JobState state, int limit, int offset)
            throws SQLException {
        return list(c,
                SELECT_JOB + " WHERE state = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                state, limit, offset);
    }

    /** Deletes the job; its properties, steps and documents go with it. */
    public boolean delete(Connection c, String jobId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM jobs WHERE id = ?")) {
            ps.setString(1, jobId);
            return ps.executeUpdate() == 1;
        }
    }

    private static final String SELECT_JOB = """
            SELECT id, state, phase, current_step, error_message, document_count, created_at
            FROM jobs""";

    private List<StoredJob> list(Connection c, String sql, JobState state, int limit, int offset)
            throws SQLException {
        List<StoredJob> jobs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (state != null) {
                ps.setString(i++, state.name());
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    jobs.add(load(c, rs));
                }
            }
        }
        return jobs;
    }

    /** Turns the current row of a {@link #SELECT_JOB} result into a job, with its children. */
    private StoredJob load(Connection c, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        JobSummary summary = new JobSummary(
                id,
                JobState.valueOf(rs.getString("state")),
                Phase.valueOf(rs.getString("phase")),
                rs.getString("current_step"),
                rs.getString("error_message"),
                properties(c, id),
                rs.getInt("document_count"),
                steps(c, id));
        return new StoredJob(summary, rs.getTimestamp("created_at").toInstant());
    }

    private static Map<String, String> properties(Connection c, String jobId) throws SQLException {
        Map<String, String> properties = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT prop_name, prop_value FROM job_properties WHERE job_id = ?
                ORDER BY prop_name""")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    properties.put(rs.getString("prop_name"), rs.getString("prop_value"));
                }
            }
        }
        return properties;
    }

    private static List<StepRecord> steps(Connection c, String jobId) throws SQLException {
        List<StepRecord> steps = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT step_name, phase, started_at, finished_at, outcome FROM job_steps
                WHERE job_id = ? ORDER BY step_position""")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    steps.add(new StepRecord(
                            rs.getString("step_name"),
                            Phase.valueOf(rs.getString("phase")),
                            rs.getTimestamp("started_at").toInstant(),
                            rs.getTimestamp("finished_at").toInstant(),
                            StepOutcome.valueOf(rs.getString("outcome"))));
                }
            }
        }
        return steps;
    }
}
