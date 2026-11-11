package dev.yjimmy.printlab.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Runs {@link HealthServlet} in a real (embedded) Tomcat and calls it over HTTP. */
public class HealthServletTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TomcatTestServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @Before
    public void startTomcat() throws Exception {
        server = new TomcatTestServer(folder.getRoot().toPath())
                .servlet("health", new HealthServlet(), "/api/health")
                .start();
    }

    @After
    public void stopTomcat() throws Exception {
        server.close();
    }

    @Test
    public void getReturnsUpAsJson() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(server.uri("/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json"));
        assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElse(""));

        JsonNode body = new ObjectMapper().readTree(resp.body());
        assertEquals("UP", body.get("status").asText());
        assertEquals(HealthServlet.SERVICE, body.get("service").asText());
    }

    @Test
    public void postIsRejectedWith405() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(server.uri("/api/health"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, resp.statusCode());
    }
}
