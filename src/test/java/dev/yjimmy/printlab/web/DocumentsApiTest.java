package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.workflow.Document;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

/** The documents endpoints over HTTP: search, the file, and the structure. */
public class DocumentsApiTest extends ApiTestBase {

    @Test
    public void searchByAccountNumberReturnsMatch() throws Exception {
        completedJob("job-a", document("job-a", 1, "statements", "111"),
                document("job-a", 2, "statements", "222"));

        var response = get("/api/documents?property=accountNumber&value=111");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Total-Count")).hasValue("1");
        assertThat(response.body()).contains("\"accountNumber\":\"111\"")
                .doesNotContain("\"accountNumber\":\"222\"");
    }

    @Test
    public void searchWithoutAnyFilterReturns400() throws Exception {
        assertThat(get("/api/documents").statusCode()).isEqualTo(400);
        assertThat(get("/api/documents?limit=5").statusCode()).isEqualTo(400);
    }

    @Test
    public void propertyNeedsValueAndValueNeedsProperty() throws Exception {
        assertThat(get("/api/documents?property=accountNumber").statusCode()).isEqualTo(400);
        assertThat(get("/api/documents?value=111").statusCode()).isEqualTo(400);
    }

    @Test
    public void searchByPropertyAndQueueTogether() throws Exception {
        completedJob("job-a", document("job-a", 1, "statements", "111"),
                document("job-a", 2, "exceptions", "111"));
        completedJob("job-b", document("job-b", 1, "statements", "111"));

        var response = get("/api/documents?property=accountNumber&value=111&queue=exceptions");

        assertThat(response.headers().firstValue("X-Total-Count")).hasValue("1");
        assertThat(response.body()).contains("\"jobId\":\"job-a\"");
    }

    @Test
    public void filtersByQueueAcrossJobs() throws Exception {
        completedJob("job-a", document("job-a", 1, "statements", "111"));
        completedJob("job-b", document("job-b", 1, "statements", "222"),
                document("job-b", 2, "exceptions", "333"));

        var response = get("/api/documents?queue=statements");

        assertThat(response.headers().firstValue("X-Total-Count")).hasValue("2");
        assertThat(jobIds(response.body())).containsExactly("job-b", "job-a");
    }

    @Test
    public void limitAndOffsetPageResultsWithTotalCount() throws Exception {
        completedJob("job-a", document("job-a", 1, "statements", "1"),
                document("job-a", 2, "statements", "2"),
                document("job-a", 3, "statements", "3"));

        var page = get("/api/documents?queue=statements&limit=2&offset=1");

        assertThat(page.headers().firstValue("X-Total-Count")).hasValue("3");
        assertThat(page.body()).contains("\"accountNumber\"").doesNotContain("\"sequence\":1,");
        assertThat(jobIds(page.body())).hasSize(2);
        assertThat(get("/api/documents?queue=statements&limit=500&offset=10").body())
                .isEqualTo("[]");
    }

    @Test
    public void invalidLimitOrOffsetReturns400() throws Exception {
        assertThat(get("/api/documents?queue=a&limit=-1").statusCode()).isEqualTo(400);
        assertThat(get("/api/documents?queue=a&limit=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/documents?queue=a&offset=-5").statusCode()).isEqualTo(400);
        assertThat(get("/api/documents?queue=a&offset=abc").statusCode()).isEqualTo(400);
    }

    @Test
    public void fileEndpointStreamsPdfWithCorrectContentType() throws Exception {
        Document doc = document("job-a", 1, "statements", "111");
        completedJob("job-a", doc);
        byte[] pdf = writePdf("job-a", "statements", 1);
        long id = documentId("job-a");

        var response = getBytes("/api/documents/" + id + "/file");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.body()).isEqualTo(pdf);
    }

    @Test
    public void fileEndpointRefusesSymlinkThatPointsOutsideJobFolder() throws Exception {
        completedJob("job-a", document("job-a", 1, "linked", "111"));
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Files.write(outside.resolve("00001.pdf"), new byte[] {1, 2, 3});
        Path queue = output.resolve("job-a").resolve("linked");
        Files.createDirectories(output.resolve("job-a"));
        try {
            Files.createSymbolicLink(queue, outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows only lets a process make symlinks when it is elevated or Developer Mode is
            // on. An elevated process always can, so a failure there is a real failure. Otherwise
            // the test is skipped, and the reason is reported with it.
            if (isAdministrator()) {
                throw e;
            }
            Assume.assumeNoException("skipped: this process is not Administrator and cannot "
                    + "create symlinks (turn on Developer Mode or run elevated)", e);
        }

        var response = get("/api/documents/" + documentId("job-a") + "/file");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("has no file in the output folder");
    }

    @Test
    public void fileEndpointDoesNotServePathsOutsideOutputDir() throws Exception {
        // A queue name that climbs out of the job's folder: a file exists where it points.
        completedJob("job-a", document("job-a", 1, "../escape", "111"));
        Path outside = output.resolve("escape").resolve("00001.pdf");
        Files.createDirectories(outside.getParent());
        Files.write(outside, new byte[] {1, 2, 3});

        var response = get("/api/documents/" + documentId("job-a") + "/file");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("has no file in the output folder");
    }

    @Test
    public void fileOfUnknownDocumentReturns404() throws Exception {
        assertThat(get("/api/documents/9999/file").statusCode()).isEqualTo(404);
        assertThat(get("/api/documents/abc/file").statusCode()).isEqualTo(400);
    }

    @Test
    public void structureEndpointReturnsFieldsForAfpAndObjectsForPdf() throws Exception {
        Document afp = new Document(1, tmp.resolve("afp-1.afp"),
                Map.of("accountNumber", "111", "queue", "statements", "format", "afp"), 1);
        completedJob("job-afp", afp);
        Path afpFile = output.resolve("job-afp").resolve("statements").resolve("00001.afp");
        Files.createDirectories(afpFile.getParent());
        new StatementAfpGenerator(1).generate(2, 0, afpFile);

        JsonNode fields = ApiJson.MAPPER.readTree(
                get("/api/documents/" + documentId("job-afp") + "/structure").body());

        assertThat(fields.get("format").asText()).isEqualTo("afp");
        assertThat(fields.get("truncated").asBoolean()).isFalse();
        assertThat(fields.get("fields").size()).isGreaterThan(10);
        assertThat(fields.get("fields").get(0).get("type").asText()).isEqualTo("BDT");

        Document pdf = document("job-pdf", 1, "statements", "222");
        completedJob("job-pdf", pdf);
        Path pdfFile = output.resolve("job-pdf").resolve("statements").resolve("00001.pdf");
        Files.createDirectories(pdfFile.getParent());
        new StatementPdfGenerator(1).generate(1, 0, false,
                StatementPdfGenerator.Defects.NONE, pdfFile);

        JsonNode pages = ApiJson.MAPPER.readTree(
                get("/api/documents/" + documentId("job-pdf") + "/structure").body());

        assertThat(pages.get("format").asText()).isEqualTo("pdf");
        assertThat(pages.get("pages").get(0).get("operators").size()).isGreaterThan(0);
    }

    @Test
    public void structureOfCorruptFileReturns422WithOffset() throws Exception {
        Document afp = new Document(1, tmp.resolve("bad-1.afp"),
                Map.of("accountNumber", "111", "queue", "statements", "format", "afp"), 1);
        completedJob("job-bad", afp);
        Path file = output.resolve("job-bad").resolve("statements").resolve("00001.afp");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "this is not an AFP file");

        var response = get("/api/documents/" + documentId("job-bad") + "/structure");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"status\":422").contains("\"offset\":");
    }

    /**
     * Whether this process is elevated. On Windows, {@code net session} succeeds only for an
     * administrator, so its exit code tells. Other systems are not checked and return false.
     */
    static boolean isAdministrator() throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        Process net = new ProcessBuilder("net", "session")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!net.waitFor(10, TimeUnit.SECONDS)) {
            net.destroyForcibly();
            return false;
        }
        return net.exitValue() == 0;
    }

    /** The id of the job's first document, as the API numbers it. */
    private long documentId(String jobId) throws Exception {
        JsonNode docs = ApiJson.MAPPER.readTree(get("/api/jobs/" + jobId + "/documents").body());
        return docs.get(0).get("id").asLong();
    }

    /** Writes a PDF where the output folder keeps the job's document, and returns its bytes. */
    private byte[] writePdf(String jobId, String queue, int position) throws Exception {
        Path file = output.resolve(jobId).resolve(queue).resolve(
                String.format("%05d.pdf", position));
        Files.createDirectories(file.getParent());
        Files.write(file, "%PDF-1.4 test bytes".getBytes());
        return Files.readAllBytes(file);
    }

    private static java.util.List<String> jobIds(String json) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode doc : ApiJson.MAPPER.readTree(json)) {
            ids.add(doc.get("jobId").asText());
        }
        return ids;
    }
}
