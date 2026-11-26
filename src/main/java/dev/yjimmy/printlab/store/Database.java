package dev.yjimmy.printlab.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;

/**
 * Where the persistence layer's connections come from: a JDBC URL, a user and a password, with
 * the schema migrated to the latest version when the database is opened.
 *
 * <p>There's no connection pool. Each repository call takes a connection from {@link #connect()}
 * and returns it when it's done, which is plenty for one app writing a job at a time.
 */
public final class Database {

    private final String url;
    private final String user;
    private final String password;

    private Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * Opens the database at {@code url} and applies any migrations it hasn't had yet.
     *
     * @throws SQLException if the database can't be reached or a migration fails
     */
    public static Database open(String url, String user, String password) throws SQLException {
        migrate(url, user, password);
        return new Database(url, user, password);
    }

    /**
     * Applies the schema migrations in {@code db/migration} that the database hasn't had yet.
     *
     * @return how many migrations ran: zero when the schema was already up to date
     */
    public static int migrate(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
                .migrationsExecuted;
    }

    /** A new connection. The caller closes it. */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** The URL without credentials, safe to print in a log. */
    public String url() {
        return url;
    }
}
