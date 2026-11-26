package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JsonServletTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TomcatTestServer server;

    /** A servlet that fails on {@code /boom} and refuses everything else as not found. */
    private static final class Probe extends JsonServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            if ("/boom".equals(req.getPathInfo())) {
                throw new IllegalStateException("secret detail");
            }
            throw ApiException.notFound("no such path");
        }
    }

    @After
    public void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> probe(String path) throws Exception {
        server = new TomcatTestServer(folder.getRoot().toPath())
                .servlet("probe", new Probe(), "/probe/*")
                .start();
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.uri("/probe" + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void uncaughtExceptionBecomes500JsonWithoutStackTrace() throws Exception {
        var response = probe("/boom");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith(
                "application/json");
        assertThat(response.body()).contains("\"error\":\"internal server error\"")
                .contains("\"status\":500")
                .doesNotContain("secret detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("\tat ");
    }

    @Test
    public void unknownSubPathReturns404Json() throws Exception {
        var response = probe("/nothing/here");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.body()).contains("\"error\":\"no such path\"");
    }

    @Test
    public void segmentsIgnoreEmptyParts() throws IOException {
        assertThat(JsonServlet.segments(null)).isEmpty();
        assertThat(JsonServlet.segments("/")).isEmpty();
        assertThat(JsonServlet.segments("/12//file")).containsExactly("12", "file");
    }
}
