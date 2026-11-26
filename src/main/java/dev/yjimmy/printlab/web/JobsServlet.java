package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.JdbcJobStore;
import dev.yjimmy.printlab.store.StoredDocument;
import dev.yjimmy.printlab.store.StoredJob;
import dev.yjimmy.printlab.workflow.JobState;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The jobs endpoints (PRD-14):
 * <ul>
 *   <li>{@code GET /api/jobs?state=&limit=}: the newest jobs first, {@code limit} capped at 200</li>
 *   <li>{@code GET /api/jobs/{id}}: one job, with its properties and step history</li>
 *   <li>{@code GET /api/jobs/{id}/documents?queue=}: the job's documents, optionally in one queue</li>
 * </ul>
 */
@WebServlet(name = "jobs", urlPatterns = "/api/jobs/*")
public class JobsServlet extends JsonServlet {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JdbcJobStore jobs = ApiDatabase.require(getServletContext());
        String[] parts = segments(req.getPathInfo());
        if (parts.length == 0) {
            list(req, resp, jobs);
        } else if (parts.length == 1) {
            one(resp, jobs, parts[0]);
        } else if (parts.length == 2 && parts[1].equals("documents")) {
            documents(req, resp, jobs, parts[0]);
        } else {
            throw ApiException.notFound("there is no API resource at " + req.getPathInfo());
        }
    }

    private static void list(HttpServletRequest req, HttpServletResponse resp, JdbcJobStore jobs)
            throws IOException {
        int limit = Params.integer(req, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        String state = Params.text(req, "state");
        List<StoredJob> found = state == null
                ? jobs.listNewestFirst(limit, 0)
                : jobs.listByState(parseState(state), limit, 0);
        writeJson(resp, 200, found.stream().map(JobResponse::of).toList());
    }

    private static void one(HttpServletResponse resp, JdbcJobStore jobs, String id)
            throws IOException {
        StoredJob job = jobs.findJob(id)
                .orElseThrow(() -> ApiException.notFound("there is no job with id " + id));
        writeJson(resp, 200, JobResponse.of(job));
    }

    private static void documents(HttpServletRequest req, HttpServletResponse resp,
            JdbcJobStore jobs, String id) throws IOException {
        if (jobs.findJob(id).isEmpty()) {
            throw ApiException.notFound("there is no job with id " + id);
        }
        String queue = Params.text(req, "queue");
        List<StoredDocument> found = jobs.findStoredDocuments(id).stream()
                .filter(d -> queue == null || Objects.equals(queue, d.document().property("queue")))
                .toList();
        writeJson(resp, 200, found.stream().map(DocumentResponse::of).toList());
    }

    private static JobState parseState(String state) {
        try {
            return JobState.valueOf(state.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("state must be one of " + List.of(JobState.values())
                    + ", not '" + state + "'");
        }
    }
}
