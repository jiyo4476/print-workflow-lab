package dev.yjimmy.printlab.web;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Where the web app keeps its folders, and which database it uses. Each setting comes from an
 * environment variable first and a context parameter of the same name second, so the same WAR runs
 * locally, under Cargo, and in Docker.
 *
 * @param home the base for {@code hotfolder/}, {@code work/}, {@code output/} and {@code error/},
 *     and for {@code config/input-devices.yaml}; {@code PRINTLAB_HOME}, default the working
 *     directory
 * @param databaseUrl the JDBC URL, or {@code null} when {@code PRINTLAB_DB_URL} isn't set
 * @param databaseUser the database user
 * @param databasePassword the database password
 */
record PrintLabConfig(Path home, String databaseUrl, String databaseUser,
        String databasePassword) {

    static final String HOME = "PRINTLAB_HOME";
    static final String DB_URL = "PRINTLAB_DB_URL";
    static final String DB_USER = "PRINTLAB_DB_USER";
    static final String DB_PASSWORD = "PRINTLAB_DB_PASSWORD";

    /**
     * @param env reads environment variables (returns {@code null} for an unset one)
     * @param contextParam reads context parameters the same way
     */
    static PrintLabConfig from(Function<String, String> env, Function<String, String> contextParam) {
        Path home = Path.of(setting(HOME, env, contextParam, "")).toAbsolutePath().normalize();
        String url = setting(DB_URL, env, contextParam, null);
        String user = setting(DB_USER, env, contextParam, "");
        String password = setting(DB_PASSWORD, env, contextParam, "");
        return new PrintLabConfig(home, url, user, password);
    }

    private static String setting(String name, Function<String, String> env,
            Function<String, String> contextParam, String fallback) {
        String value = env.apply(name);
        if (blank(value)) {
            value = contextParam.apply(name);
        }
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    boolean hasDatabase() {
        return databaseUrl != null;
    }

    Path workRoot() {
        return home.resolve("work");
    }

    Path errorDir() {
        return home.resolve("error");
    }

    Path outputRoot() {
        return home.resolve("output");
    }

    Path devicesFile() {
        return home.resolve("config").resolve("input-devices.yaml");
    }
}
