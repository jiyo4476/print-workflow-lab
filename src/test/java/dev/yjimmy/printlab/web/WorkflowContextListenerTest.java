package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.yjimmy.printlab.store.Database;
import dev.yjimmy.printlab.testsupport.TestDatabases;
import dev.yjimmy.printlab.workflow.Job;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkflowContextListenerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TomcatTestServer server;

    @After
    public void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void startsWatcherOnStartupAndStopsItOnShutdown() throws Exception {
        Path home = folder.newFolder("home").toPath();
        writeDevices(home, "*.txt");
        WorkflowContextListener listener = new WorkflowContextListener(
                Map.of("PRINTLAB_HOME", home.toString())::get, job -> { });
        server = new TomcatTestServer(folder.getRoot().toPath()).listener(listener).start();
        List<Thread> watchers = inputDeviceThreads();

        assertThat(watchers).isNotEmpty().allMatch(Thread::isAlive);

        server.close();
        server = null;
        for (Thread watcher : watchers) {
            watcher.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertThat(watchers).noneMatch(Thread::isAlive);
    }

    @Test
    public void fileDroppedInHotFolderBecomesAJobVisibleInApi() throws Exception {
        Path home = folder.newFolder("home").toPath();
        writeDevices(home, "*.txt");
        Database db = TestDatabases.fresh();
        CountDownLatch jobDone = new CountDownLatch(1);
        WorkflowContextListener listener = new WorkflowContextListener(Map.of(
                "PRINTLAB_HOME", home.toString(),
                "PRINTLAB_DB_URL", db.url(),
                "PRINTLAB_DB_USER", "sa")::get, job -> jobDone.countDown());
        server = new TomcatTestServer(folder.getRoot().toPath())
                .listener(listener)
                .servlet("jobs", new JobsServlet(), "/api/jobs/*")
                .start();

        Files.writeString(home.resolve("hotfolder").resolve("statement.txt"), "x");

        assertThat(jobDone.await(60, TimeUnit.SECONDS)).isTrue();
        JsonNode jobs = ApiJson.MAPPER.readTree(get("/api/jobs").body());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("state").asText()).isEqualTo("COMPLETE");
        assertThat(jobs.get(0).get("properties").get("inputDevice").asText())
                .isEqualTo("statements");
    }

    @Test
    public void failedJobIsRetriedThroughTheApiOnTheDeviceThread() throws Exception {
        Path home = folder.newFolder("home").toPath();
        writeDevices(home, "*.txt");
        Path hot = home.resolve("hotfolder");
        Database db = TestDatabases.fresh();
        BlockingQueue<Job> done = new LinkedBlockingQueue<>();
        WorkflowContextListener listener = new WorkflowContextListener(Map.of(
                "PRINTLAB_HOME", home.toString(),
                "PRINTLAB_DB_URL", db.url(),
                "PRINTLAB_DB_USER", "sa")::get, done::add);
        server = new TomcatTestServer(folder.getRoot().toPath())
                .listener(listener)
                .servlet("jobs", new JobsServlet(), "/api/jobs/*")
                .start();
        // A ticket line with no '=' fails the job's first step.
        Files.writeString(hot.resolve("statement.txt.jobprops"), "not a property line\n");
        Files.writeString(hot.resolve("statement.txt"), "x");

        Job failed = done.poll(60, TimeUnit.SECONDS);
        assertThat(failed).isNotNull();
        String id = failed.id();
        assertThat(ApiJson.MAPPER.readTree(get("/api/jobs/" + id).body()).get("state").asText())
                .isEqualTo("ERROR");

        // The operator fixes the ticket in the error folder, then asks for a retry.
        Files.writeString(home.resolve("error").resolve(id).resolve("statement.txt.jobprops"),
                "statementNumber=7\n");
        var accepted = post("/api/jobs/" + id + "/retry");
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.body()).contains("\"step\":\"set-job-props\"");

        Job retried = done.poll(60, TimeUnit.SECONDS);
        assertThat(retried).isNotNull();
        JsonNode job = ApiJson.MAPPER.readTree(get("/api/jobs/" + id).body());
        assertThat(job.get("state").asText()).isEqualTo("COMPLETE");
        assertThat(job.get("properties").get("statementNumber").asText()).isEqualTo("7");
        assertThat(job.get("steps").toString()).contains("RETRIED");
        assertThat(Files.exists(home.resolve("error").resolve(id))).isFalse();
    }

    @Test
    public void apiAnswers503WithoutDatabase() throws Exception {
        Path home = folder.newFolder("home").toPath();
        WorkflowContextListener listener = new WorkflowContextListener(
                Map.of("PRINTLAB_HOME", home.toString())::get, job -> { });
        server = new TomcatTestServer(folder.getRoot().toPath())
                .listener(listener)
                .servlet("jobs", new JobsServlet(), "/api/jobs/*")
                .start();

        var response = get("/api/jobs");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("PRINTLAB_DB_URL");
    }

    /** Writes a devices file with one device on {@code hotfolder/}, and its workflow. */
    private static void writeDevices(Path home, String glob) throws IOException {
        Files.createDirectories(home.resolve("config"));
        Files.createDirectories(home.resolve("workflows"));
        Files.createDirectories(home.resolve("hotfolder"));
        Files.writeString(home.resolve("config").resolve("input-devices.yaml"), """
                devices:
                  - name: statements
                    folder: hotfolder
                    glob: "%s"
                    workflow: workflows/ok.yaml
                """.formatted(glob));
        Files.writeString(home.resolve("workflows").resolve("ok.yaml"), """
                name: ok
                description: Sets the job properties and nothing else
                phases:
                  receive:
                    - name: set-job-props
                      type: SetJobPropsFromTextFile
                """);
    }

    /** The watcher threads the devices started, which are alive until the devices stop. */
    private static List<Thread> inputDeviceThreads() {
        List<Thread> threads = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("input-device-") && t.isAlive()) {
                threads.add(t);
            }
        }
        return threads;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.uri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.uri(path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
