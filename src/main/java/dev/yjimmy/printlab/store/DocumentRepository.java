package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.Document;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code documents} and {@code document_properties} tables, in plain JDBC with parameterised
 * queries (NFR-1).
 *
 * <p>A document's {@code format} and {@code queue} are also kept in their own columns, copied from
 * its {@code format} and {@code queue} properties when it's saved, so they can be searched and
 * indexed without reading every property.
 */
public final class DocumentRepository {

    /** Replaces the job's documents, and their properties, with {@code documents}. */
    public void replaceAll(Connection c, String jobId, List<Document> documents)
            throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM documents WHERE job_id = ?")) {
            delete.setString(1, jobId);
            delete.executeUpdate();
        }
        for (Document d : documents) {
            insert(c, jobId, d);
        }
    }

    private static void insert(Connection c, String jobId, Document d) throws SQLException {
        long id;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO documents (job_id, doc_sequence, input_sequence, format, queue,
                    file_path) VALUES (?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, jobId);
            ps.setInt(2, d.sequence());
            ps.setInt(3, d.inputSequence());
            ps.setString(4, d.property("format"));
            ps.setString(5, d.property("queue"));
            ps.setString(6, d.file() == null ? null : d.file().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO document_properties (document_id, prop_name, prop_value)"
                        + " VALUES (?, ?, ?)")) {
            for (Map.Entry<String, String> e : d.properties().entrySet()) {
                ps.setLong(1, id);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** The job's documents in sequence order. */
    public List<Document> findByJob(Connection c, String jobId) throws SQLException {
        List<StoredDocument> found = query(c, """
                SELECT d.id, d.job_id, d.doc_sequence, d.input_sequence, d.file_path
                FROM documents d WHERE d.job_id = ? ORDER BY d.doc_sequence""",
                ps -> ps.setString(1, jobId));
        return found.stream().map(StoredDocument::document).toList();
    }

    /** Every document, in any job, whose property {@code name} has exactly {@code value}. */
    public List<StoredDocument> findByPropertyValue(Connection c, String name, String value)
            throws SQLException {
        return query(c, SELECT_DOCUMENT + " FROM documents d JOIN document_properties p"
                + " ON p.document_id = d.id WHERE p.prop_name = ? AND p.prop_value = ?"
                + " ORDER BY d.job_id, d.doc_sequence", ps -> {
                    ps.setString(1, name);
                    ps.setString(2, value);
                });
    }

    /** Every document in {@code queue}, in any job, ordered by job id then sequence. */
    public List<StoredDocument> findByQueue(Connection c, String queue) throws SQLException {
        return query(c, SELECT_DOCUMENT + " FROM documents d WHERE d.queue = ?"
                + " ORDER BY d.job_id, d.doc_sequence", ps -> ps.setString(1, queue));
    }

    /**
     * One page of the documents that match {@code filter}, newest job first, then by sequence.
     * A null {@code limit} returns them all.
     */
    public List<StoredDocument> search(Connection c, DocumentSearch filter, Integer limit,
            Integer offset) throws SQLException {
        String sql = SELECT_DOCUMENT + " " + FROM_JOINED + filter.whereClause()
                + " ORDER BY j.created_at DESC, j.id DESC, d.doc_sequence"
                + (limit == null ? "" : " LIMIT ? OFFSET ?");
        return query(c, sql, ps -> {
            int i = filter.bind(ps, 1);
            if (limit != null) {
                ps.setInt(i++, limit);
                ps.setInt(i, offset);
            }
        });
    }

    /** How many documents {@code filter} matches. */
    public long count(Connection c, DocumentSearch filter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) " + FROM_JOINED + filter.whereClause())) {
            filter.bind(ps, 1);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The document with this id, or empty if there's none. */
    public Optional<StoredDocument> find(Connection c, long id) throws SQLException {
        List<StoredDocument> found = query(c, SELECT_DOCUMENT
                + " FROM documents d WHERE d.id = ?", ps -> ps.setLong(1, id));
        return found.stream().findFirst();
    }

    /** All of a job's documents, with their ids, in sequence order. */
    public List<StoredDocument> findStoredByJob(Connection c, String jobId) throws SQLException {
        return query(c, SELECT_DOCUMENT + " FROM documents d WHERE d.job_id = ?"
                + " ORDER BY d.doc_sequence", ps -> ps.setString(1, jobId));
    }

    /** One page of a job's documents in sequence order, optionally only those in {@code queue}. */
    public List<StoredDocument> pageByJob(Connection c, String jobId, String queue, int limit,
            int offset) throws SQLException {
        String sql = SELECT_DOCUMENT + " FROM documents d WHERE d.job_id = ?"
                + (queue == null ? "" : " AND d.queue = ?")
                + " ORDER BY d.doc_sequence LIMIT ? OFFSET ?";
        return query(c, sql, ps -> {
            int i = 1;
            ps.setString(i++, jobId);
            if (queue != null) {
                ps.setString(i++, queue);
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
        });
    }

    /** How many of a job's documents are in {@code queue}, or in the job at all if null. */
    public long countByJob(Connection c, String jobId, String queue) throws SQLException {
        String sql = "SELECT COUNT(*) FROM documents d WHERE d.job_id = ?"
                + (queue == null ? "" : " AND d.queue = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jobId);
            if (queue != null) {
                ps.setString(2, queue);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Binds a query's parameters. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** The most ids in one IN (...) list, well under the database's parameter limits. */
    private static final int IN_CHUNK = 500;

    private static final String SELECT_DOCUMENT =
            "SELECT d.id, d.job_id, d.doc_sequence, d.input_sequence, d.file_path";
    private static final String FROM_JOINED =
            "FROM documents d JOIN jobs j ON j.id = d.job_id";

    /** One documents row, before its properties are read. */
    private record Row(long id, String jobId, int sequence, int inputSequence, String file) {}

    private static List<StoredDocument> query(Connection c, String sql, Binder binder)
            throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("id"), rs.getString("job_id"),
                            rs.getInt("doc_sequence"), rs.getInt("input_sequence"),
                            rs.getString("file_path")));
                }
            }
        }
        // One query for all the rows' properties, not one per document.
        Map<Long, Map<String, String>> properties = properties(c,
                rows.stream().map(Row::id).toList());
        List<StoredDocument> found = new ArrayList<>(rows.size());
        for (Row r : rows) {
            Document document = new Document(
                    r.sequence(),
                    r.file() == null ? null : Path.of(r.file()),
                    properties.getOrDefault(r.id(), new LinkedHashMap<>()),
                    r.inputSequence());
            found.add(new StoredDocument(r.id(), r.jobId(), document));
        }
        return found;
    }

    /** The properties of each document, by document id, read in chunks of {@link #IN_CHUNK}. */
    private static Map<Long, Map<String, String>> properties(Connection c, List<Long> ids)
            throws SQLException {
        Map<Long, Map<String, String>> byId = new HashMap<>();
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + IN_CHUNK));
            String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT document_id, prop_name, prop_value FROM document_properties"
                            + " WHERE document_id IN (" + marks + ")"
                            + " ORDER BY document_id, prop_name")) {
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setLong(i + 1, chunk.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byId.computeIfAbsent(rs.getLong("document_id"), k -> new LinkedHashMap<>())
                                .put(rs.getString("prop_name"), rs.getString("prop_value"));
                    }
                }
            }
        }
        return byId;
    }
}
