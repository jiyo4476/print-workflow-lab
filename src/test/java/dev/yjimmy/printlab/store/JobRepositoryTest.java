package dev.yjimmy.printlab.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestDatabases;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import dev.yjimmy.printlab.workflow.Phase;
import dev.yjimmy.printlab.workflow.StepOutcome;
import dev.yjimmy.printlab.workflow.StepRecord;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class JobRepositoryTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private Database db;
    private final JobRepository jobs = new JobRepository();

    @Before
    public void setUp() throws Exception {
        db = TestDatabases.fresh();
    }

    private static JobSummary job(String id, JobState state) {
        return new JobSummary(id, state, Phase.PREPARE, "split", null,
                Map.of("accountNumber", "1234567890"), 0, List.of());
    }

    private static Instant at(long seconds) {
        return START.plusSeconds(seconds);
    }

    /** Saves a job the way {@code JdbcJobStore} does, minus the documents. */
    private void save(Connection c, JobSummary job, Instant createdAt) throws Exception {
        jobs.upsert(c, job, createdAt);
        jobs.replaceProperties(c, job.id(), job.properties());
        jobs.replaceSteps(c, job.id(), job.steps());
    }

    @Test
    public void insertAndFindById() throws Exception {
        try (Connection c = db.connect()) {
            save(c, job("j1", JobState.RECEIVED), at(1));

            StoredJob found = jobs.find(c, "j1").orElseThrow();
            assertThat(found.summary()).isEqualTo(job("j1", JobState.RECEIVED));
            assertThat(found.createdAt()).isEqualTo(at(1));
            assertThat(jobs.find(c, "missing")).isEmpty();
        }
    }

    @Test
    public void updateStateAndCurrentStep() throws Exception {
        try (Connection c = db.connect()) {
            save(c, job("j1", JobState.PROCESSING), at(1));

            JobSummary finished = new JobSummary("j1", JobState.COMPLETE, Phase.COMPLETE, null,
                    null, Map.of("accountNumber", "1234567890"), 3, List.of());
            jobs.upsert(c, finished, at(99));

            StoredJob found = jobs.find(c, "j1").orElseThrow();
            assertThat(found.summary().state()).isEqualTo(JobState.COMPLETE);
            assertThat(found.summary().phase()).isEqualTo(Phase.COMPLETE);
            assertThat(found.summary().currentStep()).isNull();
            assertThat(found.summary().documentCount()).isEqualTo(3);
            // The creation time is kept from the first save.
            assertThat(found.createdAt()).isEqualTo(at(1));
        }
    }

    @Test
    public void stepHistoryIsOrdered() throws Exception {
        try (Connection c = db.connect()) {
            JobSummary withSteps = new JobSummary("j1", JobState.PROCESSING, Phase.PRINT, "c",
                    null, Map.of(), 0, List.of(
                            new StepRecord("a", Phase.RECEIVE, at(1), at(2), StepOutcome.COMPLETED),
                            new StepRecord("b", Phase.PREPARE, at(2), at(3), StepOutcome.SKIPPED),
                            new StepRecord("c", Phase.PRINT, at(3), at(4), StepOutcome.FAILED)));
            save(c, withSteps, at(1));

            List<StepRecord> steps = jobs.find(c, "j1").orElseThrow().summary().steps();
            assertThat(steps).extracting(StepRecord::name).containsExactly("a", "b", "c");
            assertThat(steps).extracting(StepRecord::phase)
                    .containsExactly(Phase.RECEIVE, Phase.PREPARE, Phase.PRINT);
        }
    }

    @Test
    public void listNewestFirstWithPaging() throws Exception {
        try (Connection c = db.connect()) {
            for (int i = 1; i <= 5; i++) {
                save(c, job("j" + i, JobState.COMPLETE), at(i));
            }

            assertThat(jobs.listNewestFirst(c, 2, 0))
                    .extracting(s -> s.summary().id()).containsExactly("j5", "j4");
            assertThat(jobs.listNewestFirst(c, 2, 2))
                    .extracting(s -> s.summary().id()).containsExactly("j3", "j2");
            assertThat(jobs.listNewestFirst(c, 2, 4))
                    .extracting(s -> s.summary().id()).containsExactly("j1");
        }
    }

    @Test
    public void filterByState() throws Exception {
        try (Connection c = db.connect()) {
            save(c, job("ok1", JobState.COMPLETE), at(1));
            save(c, job("bad1", JobState.ERROR), at(2));
            save(c, job("ok2", JobState.COMPLETE), at(3));
            save(c, job("bad2", JobState.ERROR), at(4));

            assertThat(jobs.listByState(c, JobState.ERROR, 10, 0))
                    .extracting(s -> s.summary().id()).containsExactly("bad2", "bad1");
            assertThat(jobs.listByState(c, JobState.RECEIVED, 10, 0)).isEmpty();
        }
    }
}
