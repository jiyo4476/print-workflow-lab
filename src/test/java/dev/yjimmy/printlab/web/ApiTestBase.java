package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.JdbcJobStore;
import dev.yjimmy.printlab.testsupport.RecordingStep;
import dev.yjimmy.printlab.testsupport.TestDatabases;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 * An embedded Tomcat with the job and document servlets, a fresh database and the services
 * attribute set, for the API tests. Jobs are saved in the order the test makes them, so the
 * newest job is the last one saved.
 */
abstract class ApiTestBase {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    protected Path tmp;
    protected Path output;
    protected JdbcJobStore store;
    protected StepRegistry registry;
    protected TomcatTestServer server;

    @Before
    public void startServer() throws Exception {
        tmp = folder.getRoot().toPath();
        output = tmp.resolve("output");
        store = new JdbcJobStore(TestDatabases.fresh(), TickingClock.oneSecondTicks());
        registry = new StepRegistry();
        registry.register("Ok", new RecordingStep("Ok").factory());
        registry.register("Boom", new RecordingStep("Boom")
                .failingOnCall(1, new IllegalStateException("bad page")).factory());
        server = new TomcatTestServer(tmp)
                .listener(new Services(new ApiServices(store, output)))
                .servlet("jobs", new JobsServlet(), "/api/jobs/*")
                .servlet("documents", new DocumentsServlet(), "/api/documents/*")
                .start();
    }

    @After
    public void stopServer() throws Exception {
        server.close();
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.uri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<byte[]> getBytes(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.uri(path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** A document of job {@code jobId}; its work file is named after the job and sequence. */
    protected Document document(String jobId, int sequence, String queue, String accountNumber) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("accountNumber", accountNumber);
        properties.put("format", "pdf");
        if (queue != null) {
            properties.put("queue", queue);
        }
        return new Document(sequence, tmp.resolve(jobId + "-" + sequence + ".pdf"), properties,
                sequence);
    }

    /** A new job with {@code docs}, not yet run: it stays RECEIVED. Saved to the store. */
    protected Job savedJob(String id, Document... docs) throws Exception {
        Job job = newJob(id, docs);
        store.save(job);
        return job;
    }

    /** A job that runs one step that succeeds, so it ends COMPLETE. */
    protected Job completedJob(String id, Document... docs) throws Exception {
        return runJob(id, "Ok", docs);
    }

    /** A job whose second step fails, so it ends in ERROR. */
    protected Job failedJob(String id, Document... docs) throws Exception {
        return runJob(id, "Boom", docs);
    }

    private Job runJob(String id, String type, Document... docs) throws Exception {
        Job job = newJob(id, docs);
        new WorkflowEngine(TestWorkflows.of(TestWorkflows.step("a", type)), registry,
                tmp.resolve("error"), TickingClock.oneSecondTicks(), store).run(job);
        return job;
    }

    private Job newJob(String id, Document... docs) throws IOException {
        Path input = tmp.resolve(id + "-in.pdf");
        if (!Files.exists(input)) {
            Files.writeString(input, "x");
        }
        Job job = TestJobs.newJob(id, tmp, input);
        job.documents().addAll(List.of(docs));
        return job;
    }

    /** Puts the services where the servlets look for them. */
    private static final class Services implements ServletContextListener {

        private final ApiServices services;

        Services(ApiServices services) {
            this.services = services;
        }

        @Override
        public void contextInitialized(ServletContextEvent event) {
            event.getServletContext().setAttribute(ApiServices.ATTRIBUTE, services);
        }
    }
}
