package dev.yjimmy.printlab.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The base of the API servlets. Every response is JSON with {@code Cache-Control: no-store}. A
 * refused request becomes {@code {"error": "...", "status": N}}, and an uncaught exception a 500
 * whose body has no stack trace (the trace goes to the log).
 */
public abstract class JsonServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(JsonServlet.class);

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            super.service(req, resp);
        } catch (ApiException e) {
            writeError(resp, e.status(), e.getMessage(), e.offset());
        } catch (IOException | RuntimeException e) {
            log.error("{} {} failed", req.getMethod(), req.getRequestURI(), e);
            if (!resp.isCommitted()) {
                resp.resetBuffer();
                writeError(resp, 500, "internal server error", null);
            }
        }
    }

    /** The path segments after the servlet's mapping: {@code /12/file} gives [12, file]. */
    static String[] segments(String pathInfo) {
        if (pathInfo == null) {
            return new String[0];
        }
        return Arrays.stream(pathInfo.split("/"))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    static void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        ApiJson.MAPPER.writeValue(resp.getWriter(), body);
    }

    static void writeError(HttpServletResponse resp, int status, String message, Long offset)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status);
        if (offset != null) {
            body.put("offset", offset);
        }
        writeJson(resp, status, body);
    }
}
