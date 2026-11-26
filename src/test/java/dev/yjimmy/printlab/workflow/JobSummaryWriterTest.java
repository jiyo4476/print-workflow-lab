package dev.yjimmy.printlab.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testsupport.TestJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JobSummaryWriterTest {

    private static final Instant T0 = Instant.parse("2026-03-04T05:06:07Z");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Job job;
    private final JobSummaryWriter writer = new JobSummaryWriter();

    @Before
    public void setUp() throws Exception {
        Path tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, Files.writeString(tmp.resolve("run.pdf"), "x"));
    }

    @Test
    public void writesJobJsonThatRoundTrips() throws Exception {
        job.properties().put("totalPages", "12");
        job.documents().add(new Document(1, Path.of("doc-00001.pdf"), Map.of("zip", "80301"), 1));
        job.moveTo(JobState.PROCESSING, Phase.PREPARE, "split");
        job.addStepRecord(new StepRecord("split", Phase.PREPARE, T0, T0.plusMillis(1500), StepOutcome.COMPLETED));

        Path file = writer.write(job);

        assertEquals(job.workDir().resolve("job.json"), file);
        JobSummary read = JobSummaryWriter.read(file);
        assertEquals(JobSummary.of(job), read);
        assertEquals("document count, not the documents themselves", 1, read.documentCount());
        assertEquals("properties keep their insertion order",
                List.of("inputFileName", "totalPages"), List.copyOf(read.properties().keySet()));
    }

    @Test
    public void writesTimesAsIso8601() throws Exception {
        job.addStepRecord(new StepRecord("split", Phase.PREPARE, T0, T0.plusSeconds(2), StepOutcome.COMPLETED));

        String json = Files.readString(writer.write(job));

        assertThat(json).contains("\"2026-03-04T05:06:07Z\"", "\"2026-03-04T05:06:09Z\"");
    }

    @Test
    public void overwritesSummaryAfterEachStep() throws Exception {
        job.addStepRecord(new StepRecord("one", Phase.PREPARE, T0, T0.plusSeconds(1), StepOutcome.COMPLETED));
        Path file = writer.write(job);
        job.addStepRecord(new StepRecord("two", Phase.PREPARE, T0.plusSeconds(1), T0.plusSeconds(2),
                StepOutcome.COMPLETED));
        writer.write(job);

        assertThat(JobSummaryWriter.read(file).steps())
                .extracting(StepRecord::name).containsExactly("one", "two");
    }

    @Test
    public void leavesNoTempFilesBehind() throws Exception {
        writer.write(job);
        writer.write(job);

        try (var files = Files.list(job.workDir())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("job.json");
        }
    }
}
