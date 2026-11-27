package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.yjimmy.printlab.store.StoredJob;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import dev.yjimmy.printlab.workflow.Phase;
import dev.yjimmy.printlab.workflow.StepOutcome;
import dev.yjimmy.printlab.workflow.StepRecord;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Pins the shape of one job in the API (AC-5). {@code fixtures/api/job.json} is the contract the
 * dashboard's tests load too (PRD-16): change the fixture only together with the dashboard.
 */
public class ApiJsonShapeTest {

    @Test
    public void jobResponseMatchesFixture() throws Exception {
        JobSummary summary = new JobSummary("job-1", JobState.COMPLETE, Phase.COMPLETE, null, null,
                Map.of("accountNumber", "1234567890"), 2,
                List.of(new StepRecord("split", Phase.PREPARE,
                        Instant.parse("2026-01-01T00:00:01Z"),
                        Instant.parse("2026-01-01T00:00:02Z"), StepOutcome.COMPLETED)));
        StoredJob job = new StoredJob(summary, Instant.parse("2026-01-01T00:00:00Z"));

        JsonNode actual = ApiJson.MAPPER.valueToTree(JobResponse.of(job));
        JsonNode expected;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/api/job.json")) {
            expected = ApiJson.MAPPER.readTree(in);
        }

        assertThat(actual).isEqualTo(expected);
    }
}
