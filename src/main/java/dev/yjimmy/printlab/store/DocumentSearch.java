package dev.yjimmy.printlab.store;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Which documents a search finds, across jobs: those whose property {@code property} has exactly
 * {@code value}, and/or those in {@code queue}. Each filter is optional, but a property needs its
 * value, which the API checks.
 *
 * @param property the property name to match, or {@code null}
 * @param value the value {@code property} must have
 * @param queue the queue the document must be in, or {@code null}
 */
public record DocumentSearch(String property, String value, String queue) {

    /** The SQL for the filters, starting with WHERE; {@code d} is the documents alias. */
    String whereClause() {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (property != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM document_properties p"
                    + " WHERE p.document_id = d.id AND p.prop_name = ? AND p.prop_value = ?)");
        }
        if (queue != null) {
            sql.append(" AND d.queue = ?");
        }
        return sql.toString();
    }

    /** Binds the filters' values from parameter {@code first} on; returns the next index. */
    int bind(PreparedStatement ps, int first) throws SQLException {
        int i = first;
        if (property != null) {
            ps.setString(i++, property);
            ps.setString(i++, value);
        }
        if (queue != null) {
            ps.setString(i++, queue);
        }
        return i;
    }
}
