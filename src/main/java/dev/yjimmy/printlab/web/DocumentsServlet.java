package dev.yjimmy.printlab.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.yjimmy.printlab.store.DocumentPage;
import dev.yjimmy.printlab.store.DocumentSearch;
import dev.yjimmy.printlab.store.JdbcJobStore;
import dev.yjimmy.printlab.store.StoredDocument;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The documents endpoints (PRD-14):
 * <ul>
 *   <li>{@code GET /api/documents?property=&value=&queue=&limit=&offset=}: a search across jobs,
 *       newest job first, with {@code X-Total-Count}. Needs {@code property} (with its
 *       {@code value}) or {@code queue}.</li>
 *   <li>{@code GET /api/documents/{id}/file}: the document's PDF or AFP file</li>
 *   <li>{@code GET /api/documents/{id}/structure}: the file's structure as JSON</li>
 * </ul>
 */
@WebServlet(name = "documents", urlPatterns = "/api/documents/*")
public class DocumentsServlet extends JsonServlet {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JdbcJobStore jobs = ApiDatabase.require(getServletContext());
        String[] parts = segments(req.getPathInfo());
        if (parts.length == 0) {
            search(req, resp, jobs);
        } else if (parts.length == 2 && parts[1].equals("file")) {
            file(resp, jobs, outputRoot(), documentId(parts[0]));
        } else if (parts.length == 2 && parts[1].equals("structure")) {
            structure(resp, jobs, outputRoot(), documentId(parts[0]));
        } else {
            throw ApiException.notFound("there is no API resource at " + req.getPathInfo());
        }
    }

    /**
     * {@code POST /api/documents/reprint} with the JSON body {@code {"documentIds": [...]}}: a new
     * child job with just those documents, answered 201 with its id (PRD-15).
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ApiDatabase.require(getServletContext());
        String[] parts = segments(req.getPathInfo());
        if (parts.length != 1 || !parts[0].equals("reprint")) {
            throw ApiException.notFound("there is no API resource at " + req.getPathInfo());
        }
        ReprintService reprints = ApiServices.of(getServletContext()).reprints();
        if (reprints == null) {
            throw new ApiException(503, "reprinting needs the input devices running and a"
                    + " workflows/reprint.yaml");
        }
        ReprintService.Accepted accepted = reprints.reprint(documentIds(req));
        writeJson(resp, 201, Map.of("jobId", accepted.jobId(),
                "parentJobId", accepted.parentJobId(), "documents", accepted.documents()));
    }

    /** The {@code documentIds} array in the request's JSON body. */
    private static List<Long> documentIds(HttpServletRequest req) throws IOException {
        JsonNode body;
        try {
            body = ApiJson.MAPPER.readTree(req.getInputStream());
        } catch (IOException e) {
            throw ApiException.badRequest("the body must be JSON like {\"documentIds\": [1, 2]}");
        }
        JsonNode ids = body == null ? null : body.get("documentIds");
        if (ids == null || !ids.isArray()) {
            throw ApiException.badRequest("the body must be JSON like {\"documentIds\": [1, 2]}");
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isIntegralNumber() || !id.canConvertToLong()) {
                throw ApiException.badRequest("a document id is a whole number, not " + id);
            }
            result.add(id.asLong());
        }
        return result;
    }

    private void search(HttpServletRequest req, HttpServletResponse resp, JdbcJobStore jobs)
            throws IOException {
        String property = Params.text(req, "property");
        String value = req.getParameter("value");
        String queue = Params.text(req, "queue");
        if (property == null && queue == null) {
            throw ApiException.badRequest("search by property (with value) or by queue");
        }
        if (property != null && value == null) {
            throw ApiException.badRequest("property needs a value to match");
        }
        if (property == null && value != null) {
            throw ApiException.badRequest("value needs a property to match");
        }
        int limit = Params.integer(req, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int offset = Params.integer(req, "offset", 0, 0, Integer.MAX_VALUE);

        DocumentPage page = jobs.searchDocumentPage(
                new DocumentSearch(property, value, queue), limit, offset);
        resp.setHeader("X-Total-Count", String.valueOf(page.total()));
        writeJson(resp, 200, page.documents().stream().map(DocumentResponse::of).toList());
    }

    private static void file(HttpServletResponse resp, JdbcJobStore jobs, Path outputRoot,
            long id) throws IOException {
        Path file = locate(jobs, outputRoot, id);
        resp.setStatus(200);
        resp.setContentType(contentType(file));
        resp.setContentLengthLong(Files.size(file));
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        try (OutputStream out = resp.getOutputStream()) {
            Files.copy(file, out);
        }
    }

    private static void structure(HttpServletResponse resp, JdbcJobStore jobs, Path outputRoot,
            long id) throws IOException {
        StoredDocument doc = document(jobs, id);
        Path file = locate(jobs, outputRoot, id);
        String format = doc.document().property("format");
        if (format == null) {
            format = contentType(file).equals("application/pdf") ? "pdf"
                    : contentType(file).equals("application/afp") ? "afp" : "";
        }
        Object body = switch (format) {
            case "afp" -> DocumentStructure.afp(file);
            case "pdf" -> DocumentStructure.pdf(file);
            default -> throw new ApiException(422,
                    "there is no structure for a document of format '" + format + "'");
        };
        writeJson(resp, 200, body);
    }

    /** The document with this id, or a 404. */
    private static StoredDocument document(JdbcJobStore jobs, long id) throws IOException {
        return jobs.findDocument(id)
                .orElseThrow(() -> ApiException.notFound("there is no document with id " + id));
    }

    /** The document's file in the output folder, or a 404 if it isn't there. */
    private static Path locate(JdbcJobStore jobs, Path outputRoot, long id) throws IOException {
        StoredDocument doc = document(jobs, id);
        Optional<Path> file = OutputFiles.locate(outputRoot, doc,
                jobs.findStoredDocuments(doc.jobId()));
        return file.orElseThrow(() -> ApiException.notFound(
                "document " + id + " has no file in the output folder"));
    }

    private static long documentId(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("a document id is a number, not '" + text + "'");
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".afp")) {
            return "application/afp";
        }
        return "application/octet-stream";
    }

    private Path outputRoot() {
        return ApiServices.of(getServletContext()).outputRoot();
    }
}
