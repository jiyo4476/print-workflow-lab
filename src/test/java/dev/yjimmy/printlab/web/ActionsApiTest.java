package dev.yjimmy.printlab.web;

import static dev.yjimmy.printlab.testsupport.TestWorkflows.step;
import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.RecordingStep;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import com.fasterxml.jackson.databind.JsonNode;
import dev.yjimmy.printlab.store.StoredDocument;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepOutcome;
import dev.yjimmy.printlab.workflow.StepRecord;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The action endpoints over HTTP: retry (PRD-15). Reprint is added with its own service. */
public class ActionsApiTest extends ApiTestBase {

    /** A step that succeeds, then a resumable step that fails on its first call. */
    private RecordingStep ok;
    private RecordingStep flaky;

    /**
     * Sets up the workflow {@code a: Ok, b: Flaky}, runs the job {@code id} on device
     * "statements" so it fails at {@code b}, and leaves its input in the error folder.
     */
    private Job failedJob(String id) throws Exception {
        ok = new RecordingStep("Ok");
        flaky = new RecordingStep("Flaky").asResumable()
                .failingOnCall(1, new IllegalStateException("disk full"));
        registry.register("Ok", ok.factory());
        registry.register("Flaky", flaky.factory());
        WorkflowEngine engine = new WorkflowEngine(
                TestWorkflows.of(step("a", "Ok"), step("b", "Flaky")), registry,
                tmp.resolve("error"), TickingClock.oneSecondTicks(), store);
        runner.engine = engine;

        Path input = tmp.resolve("work").resolve(id).resolve(id + "-in.pdf");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "x");
        Job job = TestJobs.newJob(id, tmp, input);
        job.properties().put("inputDevice", "statements");
        engine.run(job);
        assertThat(job.state()).isEqualTo(JobState.ERROR);
        return job;
    }

    /**
     * Runs a job of {@code n} statements through WriteOutput, so its documents have files in the
     * output folder as an earlier run would leave them. Document {@code i}'s content is
     * {@code "doc-<id>-<i>"}.
     */
    private Job writtenJob(String id, int n) throws Exception {
        Job job = TestJobs.newJob(id, tmp, tmp.resolve(id + "-in.pdf"));
        for (int i = 1; i <= n; i++) {
            Path file = Files.writeString(tmp.resolve(id + "-" + i + ".pdf"), "doc-" + id + "-" + i);
            job.documents().add(new Document(i, file, Map.of("accountNumber", "acct" + i,
                    "format", "pdf", "queue", "statements"), i));
        }
        new WorkflowEngine(TestWorkflows.of(step("write", "WriteOutput")), registry,
                tmp.resolve("error"), TickingClock.oneSecondTicks(), store).run(job);
        assertThat(job.state()).isEqualTo(JobState.COMPLETE);
        return job;
    }

    /** The database ids of a job's documents, in sequence order. */
    private List<Long> documentIds(String jobId) throws Exception {
        return store.findStoredDocuments(jobId).stream().map(StoredDocument::id).toList();
    }

    private HttpResponse<String> reprint(String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.uri(
                "/api/documents/reprint")).POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void reprintMakesAChildJobWithOnlyTheSelectedDocuments() throws Exception {
        writtenJob("job-p", 3);
        List<Long> ids = documentIds("job-p");

        var response = reprint("{\"documentIds\": [" + ids.get(0) + ", " + ids.get(2) + "]}");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = ApiJson.MAPPER.readTree(response.body());
        String child = body.get("jobId").asText();
        assertThat(body.get("parentJobId").asText()).isEqualTo("job-p");
        assertThat(body.get("documents").asInt()).isEqualTo(2);
        var stored = store.findJob(child).orElseThrow().summary();
        assertThat(stored.state()).isEqualTo(JobState.COMPLETE);
        assertThat(stored.properties()).containsEntry("parentJobId", "job-p");
        assertThat(store.findDocuments(child)).hasSize(2);
        assertThat(Files.readString(output.resolve(child).resolve("statements")
                .resolve("00001.pdf"))).isEqualTo("doc-job-p-1");
        assertThat(Files.readString(output.resolve(child).resolve("statements")
                .resolve("00002.pdf"))).isEqualTo("doc-job-p-3");
    }

    @Test
    public void reprintLeavesTheOriginalJobUnchanged() throws Exception {
        writtenJob("job-keep", 2);
        List<Long> ids = documentIds("job-keep");

        reprint("{\"documentIds\": [" + ids.get(0) + "]}");

        assertThat(store.findStoredDocuments("job-keep")).hasSize(2);
        assertThat(store.findJob("job-keep").orElseThrow().summary().properties())
                .doesNotContainKey("parentJobId");
    }

    @Test
    public void reprintWithAnUnknownIdFailsTheWholeRequest() throws Exception {
        writtenJob("job-u", 1);
        long known = documentIds("job-u").get(0);

        var response = reprint("{\"documentIds\": [" + known + ", 9999]}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("9999");
        assertThat(store.listNewestFirst(10, 0)).as("no child job was made").hasSize(1);
    }

    @Test
    public void reprintAcrossJobsIsRefusedAndNamesTheJobs() throws Exception {
        writtenJob("job-a", 1);
        writtenJob("job-b", 1);
        long a = documentIds("job-a").get(0);
        long b = documentIds("job-b").get(0);

        var response = reprint("{\"documentIds\": [" + a + ", " + b + "]}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("job-a").contains("job-b");
        assertThat(store.listNewestFirst(10, 0)).hasSize(2);
    }

    @Test
    public void reprintWithNoDocumentsOrBadBodyIs400() throws Exception {
        writtenJob("job-e", 1);

        assertThat(reprint("{\"documentIds\": []}").statusCode()).isEqualTo(400);
        assertThat(reprint("{}").statusCode()).isEqualTo(400);
        assertThat(reprint("not json").statusCode()).isEqualTo(400);
        assertThat(reprint("{\"documentIds\": [\"one\"]}").statusCode()).isEqualTo(400);
    }

    @Test
    public void reprintOfADocumentWithoutAFileIs409() throws Exception {
        writtenJob("job-gone", 1);
        long id = documentIds("job-gone").get(0);
        Files.delete(output.resolve("job-gone").resolve("statements").resolve("00001.pdf"));

        assertThat(reprint("{\"documentIds\": [" + id + "]}").statusCode()).isEqualTo(409);
    }

    @Test
    public void reprintThatCannotBeQueuedLeavesNoChildJob() throws Exception {
        writtenJob("job-q", 1);
        long id = documentIds("job-q").get(0);
        reprintQueue.refuse = true;

        var response = reprint("{\"documentIds\": [" + id + "]}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(store.listNewestFirst(10, 0)).as("the child was removed").hasSize(1);
    }

    @Test
    public void retryOfUnknownJobReturns404() throws Exception {
        assertThat(post("/api/jobs/nope/retry").statusCode()).isEqualTo(404);
    }

    @Test
    public void retryOfCompletedJobIsRejected() throws Exception {
        completedJob("job-done");

        var response = post("/api/jobs/job-done/retry");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("only a failed job can be retried");
    }

    @Test
    public void retryRunsFromTheFailedStepAndRecordsIt() throws Exception {
        failedJob("job-f");

        var response = post("/api/jobs/job-f/retry");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"step\":\"b\"").contains("\"status\":\"queued\"");
        assertThat(ok.calls()).as("the step before the failure is not run again").hasSize(1);
        assertThat(flaky.calls()).hasSize(2);
        var stored = store.findJob("job-f").orElseThrow().summary();
        assertThat(stored.state()).isEqualTo(JobState.COMPLETE);
        assertThat(stored.steps().stream().map(StepRecord::outcome).toList())
                .contains(StepOutcome.RETRIED);
    }

    @Test
    public void retryPassesTheDeviceToTheRunner() throws Exception {
        failedJob("job-dev");

        post("/api/jobs/job-dev/retry");

        assertThat(runner.checkedDevices).containsExactly("statements");
    }

    @Test
    public void doubleSubmitRetryDoesNotRunTwice() throws Exception {
        failedJob("job-twice");
        runner.runAtOnce = false;

        assertThat(post("/api/jobs/job-twice/retry").statusCode()).isEqualTo(202);
        var second = post("/api/jobs/job-twice/retry");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("already running");
        runner.runQueued();
        assertThat(flaky.calls()).as("one failed run and one retry").hasSize(2);
        assertThat(post("/api/jobs/job-twice/retry").statusCode()).isEqualTo(409);
    }

    @Test
    public void retryMovesTheInputBackFromTheErrorFolder() throws Exception {
        failedJob("job-move");
        Path input = tmp.resolve("work").resolve("job-move").resolve("job-move-in.pdf");
        Path errorJob = tmp.resolve("error").resolve("job-move");
        assertThat(Files.exists(errorJob)).isTrue();
        assertThat(Files.exists(input)).isFalse();

        assertThat(post("/api/jobs/job-move/retry").statusCode()).isEqualTo(202);

        assertThat(Files.exists(errorJob)).as("the error folder is removed").isFalse();
        assertThat(Files.exists(input)).isTrue();
    }

    @Test
    public void retryWithTheInputGoneIsRefused() throws Exception {
        failedJob("job-gone");
        Files.delete(tmp.resolve("error").resolve("job-gone").resolve("job-gone-in.pdf"));

        var response = post("/api/jobs/job-gone/retry");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("no longer in its work or error folder");
        assertThat(flaky.calls()).hasSize(1);
    }

    @Test
    public void retryFromAStepThatCannotRunAgainIsRefusedAndMovesNothing() throws Exception {
        registry.register("Fixed", new RecordingStep("Fixed")
                .failingOnCall(1, new IllegalStateException("boom")).factory());
        WorkflowEngine engine = new WorkflowEngine(
                TestWorkflows.of(step("a", "Fixed")), registry, tmp.resolve("error"),
                TickingClock.oneSecondTicks(), store);
        runner.engine = engine;
        Path input = tmp.resolve("work").resolve("job-no").resolve("job-no-in.pdf");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "x");
        Job job = TestJobs.newJob("job-no", tmp, input);
        job.properties().put("inputDevice", "statements");
        engine.run(job);

        var response = post("/api/jobs/job-no/retry");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("cannot be run again on its own");
        assertThat(Files.exists(tmp.resolve("error").resolve("job-no").resolve("job-no-in.pdf")))
                .as("the input stays in the error folder").isTrue();
    }
}
