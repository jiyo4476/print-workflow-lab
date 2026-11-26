package dev.yjimmy.printlab.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testsupport.TestDatabases;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;
import org.junit.Test;

public class MigrationTest {

    @Test
    public void migrationsApplyToEmptyDatabase() throws Exception {
        Database db = TestDatabases.fresh();
        try (Connection c = db.connect()) {
            Set<String> tables = new TreeSet<>();
            try (ResultSet rs = c.getMetaData().getTables(null, c.getSchema(), "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            assertThat(tables).contains(
                    "jobs", "job_properties", "job_steps", "documents", "document_properties");
        }
    }

    @Test
    public void migrationsAreRepeatableNoOpOnSecondRun() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        assertEquals(1, Database.migrate(url, "sa", ""));
        assertEquals(0, Database.migrate(url, "sa", ""));
    }
}
