package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.yjimmy.printlab.workflow.Document;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The jobs endpoints over HTTP, against a seeded database. */
public class JobsApiTest extends ApiTestBase {

    @Test
    public void listsJobsNewestFirst() throws Exception {
        savedJob("job-a");
        completedJob("job-b");
        failedJob("job-c");

        var response = get("/api/jobs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.body()).contains("\"id\":\"job-c\"").contains("\"id\":\"job-b\"");
        assertThat(ids(response.body())).containsExactly("job-c", "job-b", "job-a");
    }

    @Test
    public void filtersByState() throws Exception {
        savedJob("job-a");
        completedJob("job-b");
        failedJob("job-c");

        assertThat(ids(get("/api/jobs?state=complete").body())).containsExactly("job-b");
        assertThat(ids(get("/api/jobs?state=ERROR").body())).containsExactly("job-c");
        assertThat(ids(get("/api/jobs?state=").body()))
                .containsExactly("job-c", "job-b", "job-a");
    }

    @Test
    public void limitIsCapped() throws Exception {
        for (int i = 1; i <= 205; i++) {
            savedJob(String.format("job-%03d", i));
        }

        assertThat(ids(get("/api/jobs?limit=2").body())).hasSize(2);
        assertThat(ids(get("/api/jobs").body())).hasSize(JobsServlet.DEFAULT_LIMIT);
        assertThat(ids(get("/api/jobs?limit=100000").body())).hasSize(JobsServlet.MAX_LIMIT);
    }

    @Test
    public void getJobIncludesStepHistory() throws Exception {
        completedJob("job-b", document("job-b", 1, "statements", "111"));

        var response = get("/api/jobs/job-b");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"state\":\"COMPLETE\"")
                .contains("\"documentCount\":1")
                .contains("\"name\":\"a\"")
                .contains("\"outcome\":\"COMPLETED\"")
                .contains("\"createdAt\":");
    }

    @Test
    public void unknownJobReturns404Json() throws Exception {
        var response = get("/api/jobs/no-such-job");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith(
                "application/json");
        assertThat(response.body()).contains("\"status\":404").contains("no-such-job");
    }

    @Test
    public void invalidStateParamReturns400() throws Exception {
        var response = get("/api/jobs?state=PAUSED");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"status\":400").contains("state must be one of");
    }

    @Test
    public void offsetSkipsNewerJobs() throws Exception {
        savedJob("job-a");
        completedJob("job-b");
        failedJob("job-c");

        assertThat(ids(get("/api/jobs?limit=1&offset=1").body())).containsExactly("job-b");
        assertThat(ids(get("/api/jobs?state=complete&offset=1").body())).isEmpty();
        assertThat(ids(get("/api/jobs?offset=10").body())).isEmpty();
    }

    @Test
    public void invalidOffsetReturns400() throws Exception {
        assertThat(get("/api/jobs?offset=-1").statusCode()).isEqualTo(400);
        assertThat(get("/api/jobs?offset=abc").statusCode()).isEqualTo(400);
    }

    @Test
    public void invalidLimitReturns400() throws Exception {
        assertThat(get("/api/jobs?limit=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/jobs?limit=many").statusCode()).isEqualTo(400);
    }

    @Test
    public void listsDocumentsForJobFilteredByQueue() throws Exception {
        Document statement = document("job-d", 1, "statements", "111");
        Document exception = document("job-d", 2, "exceptions", "222");
        completedJob("job-d", statement, exception);

        var all = get("/api/jobs/job-d/documents");
        var queued = get("/api/jobs/job-d/documents?queue=exceptions");

        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body()).contains("\"sequence\":1").contains("\"sequence\":2");
        assertThat(queued.body()).contains("\"sequence\":2").doesNotContain("\"sequence\":1");
    }

    @Test
    public void documentsOfUnknownJobReturn404() throws Exception {
        assertThat(get("/api/jobs/nope/documents").statusCode()).isEqualTo(404);
    }

    @Test
    public void unknownSubResourceReturns404Json() throws Exception {
        var response = get("/api/jobs/job-b/bogus");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"status\":404");
    }

    /** The job ids in a JSON array of jobs, in order. */
    static List<String> ids(String json) throws IOException {
        List<String> ids = new ArrayList<>();
        for (JsonNode job : ApiJson.MAPPER.readTree(json)) {
            ids.add(job.get("id").asText());
        }
        return ids;
    }
}
