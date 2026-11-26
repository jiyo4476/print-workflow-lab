package dev.yjimmy.printlab.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestDatabases;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JdbcJobStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Database db;
    private JdbcJobStore store;
    private Job job;

    @Before
    public void setUp() throws Exception {
        db = TestDatabases.fresh();
        store = new JdbcJobStore(db, TickingClock.oneSecondTicks());
        Path tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, Files.writeString(tmp.resolve("in.pdf"), "x"));
        job.documents().add(new Document(1, tmp.resolve("1.afp"),
                Map.of("accountNumber", "111", "queue", "statements"), 1));
    }

    private int documentRows() throws Exception {
        try (Connection c = db.connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM documents")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    public void changedDocumentsAreRewritten() throws Exception {
        store.save(job);
        job.documents().add(new Document(2, null, Map.of("accountNumber", "222"), 2));

        store.save(job);

        assertThat(store.findDocuments(job.id())).hasSize(2);
        assertThat(documentRows()).isEqualTo(2);
    }

    @Test
    public void unchangedDocumentsAreNotWrittenAgain() throws Exception {
        store.save(job);
        // Remove the rows behind the store's back. A save with unchanged documents doesn't
        // rewrite them, so they stay gone; this is what skipping the rewrite means.
        try (Connection c = db.connect();
                Statement s = c.createStatement()) {
            s.execute("DELETE FROM documents");
        }

        job.properties().put("pageCount", "3");
        store.save(job);

        assertThat(documentRows()).isZero();
        assertThat(store.findJob(job.id()).orElseThrow().summary().properties())
                .containsEntry("pageCount", "3");
    }

    @Test
    public void finishedJobsAreSavedInFull() throws Exception {
        store.save(job);
        try (Connection c = db.connect();
                Statement s = c.createStatement()) {
            s.execute("DELETE FROM documents");
        }

        // The engine finishes the job, and its final save writes the documents again even though
        // they haven't changed since the last save.
        new WorkflowEngine(TestWorkflows.of(), new StepRegistry(), folder.getRoot().toPath(),
                TickingClock.oneSecondTicks(), store).run(job);

        assertThat(job.state()).isEqualTo(JobState.COMPLETE);
        assertThat(documentRows()).isEqualTo(1);
    }
}
