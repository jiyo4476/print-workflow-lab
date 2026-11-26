package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.store.JdbcJobStore;
import jakarta.servlet.ServletContext;

/** The database the API reads, or a 503 when the app runs without one. */
final class ApiDatabase {

    private ApiDatabase() {}

    static JdbcJobStore require(ServletContext context) {
        JdbcJobStore jobs = ApiServices.of(context).jobs();
        if (jobs == null) {
            throw new ApiException(503, "the API needs a database: set PRINTLAB_DB_URL");
        }
        return jobs;
    }
}
