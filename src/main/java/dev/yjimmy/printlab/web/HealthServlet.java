package dev.yjimmy.printlab.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/health}: reports that the web application is up. The dashboard polls it to
 * show API status, and the Docker smoke test (PR 18) waits on it before dropping a file.
 *
 * <p>Other HTTP methods get Tomcat's default 405 from {@link HttpServlet}.
 */
@WebServlet(name = "health", urlPatterns = "/api/health")
public class HealthServlet extends HttpServlet {

    static final String SERVICE = "print-workflow-lab";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", SERVICE);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        JSON.writeValue(resp.getWriter(), body);
    }
}
