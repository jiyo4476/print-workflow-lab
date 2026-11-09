package dev.yjimmy.printlab.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs {@link HealthServlet} in a real (embedded) Tomcat and calls it over HTTP. */
class HealthServletTest {

    @TempDir
    Path tmp;

    private TomcatTestServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startTomcat() throws Exception {
        server = new TomcatTestServer(tmp)
                .servlet("health", new HealthServlet(), "/api/health")
                .start();
    }

    @AfterEach
    void stopTomcat() throws Exception {
        server.close();
    }

    @Test
    void getReturnsUpAsJson() throws Exception {
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
    void postIsRejectedWith405() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(server.uri("/api/health"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, resp.statusCode());
    }
}
