package dev.yjimmy.printlab.store;

import dev.yjimmy.printlab.workflow.Document;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return query(c, """
                SELECT d.id, d.job_id, d.doc_sequence, d.input_sequence, d.file_path
                FROM documents d JOIN document_properties p ON p.document_id = d.id
                WHERE p.prop_name = ? AND p.prop_value = ?
                ORDER BY d.job_id, d.doc_sequence""",
                ps -> {
                    ps.setString(1, name);
                    ps.setString(2, value);
                });
    }

    /** Every document in {@code queue}, in any job. */
    public List<StoredDocument> findByQueue(Connection c, String queue) throws SQLException {
        return query(c, """
                SELECT d.id, d.job_id, d.doc_sequence, d.input_sequence, d.file_path
                FROM documents d WHERE d.queue = ? ORDER BY d.job_id, d.doc_sequence""",
                ps -> ps.setString(1, queue));
    }

    /** Binds a query's parameters. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<StoredDocument> query(Connection c, String sql, Binder binder)
            throws SQLException {
        List<StoredDocument> found = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String file = rs.getString("file_path");
                    Document document = new Document(
                            rs.getInt("doc_sequence"),
                            file == null ? null : Path.of(file),
                            properties(c, rs.getLong("id")),
                            rs.getInt("input_sequence"));
                    found.add(new StoredDocument(rs.getString("job_id"), document));
                }
            }
        }
        return found;
    }

    private static Map<String, String> properties(Connection c, long documentId)
            throws SQLException {
        Map<String, String> properties = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT prop_name, prop_value FROM document_properties WHERE document_id = ?
                ORDER BY prop_name""")) {
            ps.setLong(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    properties.put(rs.getString("prop_name"), rs.getString("prop_value"));
                }
            }
        }
        return properties;
    }
}
