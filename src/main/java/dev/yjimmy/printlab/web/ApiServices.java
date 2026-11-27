package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.JdbcJobStore;
import jakarta.servlet.ServletContext;
import java.nio.file.Path;

/**
 * What the servlets read, put in the {@link ServletContext} by {@link WorkflowContextListener}.
 *
 * @param jobs the database the API reads, or {@code null} when no database is configured; the
 *     job and document endpoints then answer 503
 * @param outputRoot the output folder the document files are read from
 * @param retries the retry action, or {@code null} when no input devices are running to retry on
 * @param reprints the reprint action, or {@code null} when it can't run (no input devices, or no
 *     {@code reprint} workflow)
 */
record ApiServices(JdbcJobStore jobs, Path outputRoot, RetryService retries,
        ReprintService reprints) {

    /** The attribute name the listener stores this under. */
    static final String ATTRIBUTE = "dev.yjimmy.printlab.api";

    static ApiServices of(ServletContext context) {
        Object services = context.getAttribute(ATTRIBUTE);
        if (!(services instanceof ApiServices api)) {
            throw new IllegalStateException("the API isn't set up: " + ATTRIBUTE + " is missing");
        }
        return api;
    }
}
