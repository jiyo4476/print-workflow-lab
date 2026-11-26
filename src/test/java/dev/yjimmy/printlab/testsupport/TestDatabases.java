package dev.yjimmy.printlab.testsupport;

import dev.yjimmy.printlab.store.Database;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Fresh, empty databases for the persistence tests.
 *
 * <p>By default each one is an in-memory H2 database in PostgreSQL mode, so the tests run
 * anywhere in seconds. When {@code PRINTLAB_TEST_POSTGRES_URL} is set (for example
 * {@code jdbc:postgresql://localhost:5432/pwl}), each one is instead a new schema on that real
 * PostgreSQL server, using {@code PRINTLAB_TEST_POSTGRES_USER} and
 * {@code PRINTLAB_TEST_POSTGRES_PASSWORD}. The Jenkins stage for PRD-13 runs the tests that way.
 */
public final class TestDatabases {

    private TestDatabases() {}

    /**
     * A new database with the schema migrated. Each call is a separate database, so tests never
     * share rows.
     */
    public static Database fresh() throws SQLException {
        String postgres = System.getenv("PRINTLAB_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("PRINTLAB_TEST_POSTGRES_USER", "");
        String password = System.getenv().getOrDefault("PRINTLAB_TEST_POSTGRES_PASSWORD", "");
        if (postgres == null || postgres.isBlank()) {
            String name = UUID.randomUUID().toString().replace("-", "");
            return Database.open("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    "sa", "");
        }
        String schema = "pwl_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(postgres, user, password);
                Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        dropOnExit(postgres, user, password, schema);
        String separator = postgres.contains("?") ? "&" : "?";
        return Database.open(postgres + separator + "currentSchema=" + schema, user, password);
    }

    /** Drops the schema when the test JVM exits, so repeated runs don't leave schemas behind. */
    private static void dropOnExit(String url, String user, String password, String schema) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (Connection c = DriverManager.getConnection(url, user, password);
                    Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } catch (SQLException e) {
                System.err.println("Could not drop test schema " + schema + ": " + e);
            }
        }));
    }
}
