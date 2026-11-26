package dev.yjimmy.printlab.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.yjimmy.printlab.testsupport.TestDatabases;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import dev.yjimmy.printlab.workflow.Phase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DocumentRepositoryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Database db;
    private final JobRepository jobs = new JobRepository();
    private final DocumentRepository documents = new DocumentRepository();

    @Before
    public void setUp() throws Exception {
        db = TestDatabases.fresh();
    }

    private void addJob(Connection c, String id) throws Exception {
        jobs.upsert(c, new JobSummary(id, JobState.COMPLETE, Phase.COMPLETE, null, null,
                Map.of(), 0, List.of()), TickingClock.DEFAULT_START);
    }

    private static Document doc(int sequence, String account, String queue) {
        return new Document(sequence, Path.of("out", "doc" + sequence + ".afp"),
                Map.of("accountNumber", account, "queue", queue, "format", "afp"), sequence);
    }

    @Test
    public void insertWithPropertiesAndFindByJob() throws Exception {
        try (Connection c = db.connect()) {
            addJob(c, "j1");
            documents.replaceAll(c, "j1", List.of(
                    doc(1, "111", "statements"), doc(2, "222", "exceptions")));

            List<Document> found = documents.findByJob(c, "j1");
            assertThat(found).containsExactly(
                    doc(1, "111", "statements"), doc(2, "222", "exceptions"));
            assertThat(found.get(1).property("queue")).isEqualTo("exceptions");
        }
    }

    @Test
    public void findByPropertyValue() throws Exception {
        try (Connection c = db.connect()) {
            addJob(c, "j1");
            addJob(c, "j2");
            documents.replaceAll(c, "j1", List.of(doc(1, "1234567890", "statements")));
            documents.replaceAll(c, "j2", List.of(
                    doc(1, "9999999999", "statements"), doc(2, "1234567890", "statements")));

            assertThat(documents.findByPropertyValue(c, "accountNumber", "1234567890"))
                    .extracting(StoredDocument::jobId).containsExactly("j1", "j2");
            assertThat(documents.findByPropertyValue(c, "accountNumber", "0000000000"))
                    .isEmpty();
        }
    }

    @Test
    public void findByQueue() throws Exception {
        try (Connection c = db.connect()) {
            addJob(c, "j1");
            documents.replaceAll(c, "j1", List.of(
                    doc(1, "111", "statements"), doc(2, "222", "exceptions")));

            assertThat(documents.findByQueue(c, "exceptions"))
                    .extracting(d -> d.document().sequence()).containsExactly(2);
        }
    }

    @Test
    public void deletingJobCascadesToDocumentsAndProperties() throws Exception {
        try (Connection c = db.connect()) {
            addJob(c, "j1");
            documents.replaceAll(c, "j1", List.of(doc(1, "111", "statements")));

            assertThat(jobs.delete(c, "j1")).isTrue();

            assertThat(documents.findByJob(c, "j1")).isEmpty();
            assertThat(documents.findByPropertyValue(c, "accountNumber", "111")).isEmpty();
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM document_properties")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    public void saveIsTransactional() throws Exception {
        JdbcJobStore store = new JdbcJobStore(db, TickingClock.oneSecondTicks());
        Path tmp = folder.getRoot().toPath();
        Job job = TestJobs.newJob(tmp, Files.writeString(tmp.resolve("in.pdf"), "x"));
        // The job row and its properties are written first. The document's property value is
        // longer than its column allows, so the save fails part way through the documents.
        job.documents().add(new Document(1, null, Map.of("accountNumber", "x".repeat(2001)), 1));

        assertThatThrownBy(() -> store.save(job)).isInstanceOf(IOException.class);

        assertThat(store.findJob(job.id())).isEmpty();
        assertThat(store.findDocuments(job.id())).isEmpty();
    }
}
