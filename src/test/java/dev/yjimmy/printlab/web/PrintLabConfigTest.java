package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

public class PrintLabConfigTest {

    @Test
    public void environmentWinsOverContextParameter() {
        Map<String, String> env = Map.of(PrintLabConfig.DB_URL, "jdbc:postgresql://env/pwl");
        Map<String, String> context = Map.of(
                PrintLabConfig.DB_URL, "jdbc:postgresql://context/pwl",
                PrintLabConfig.DB_USER, "context-user");

        PrintLabConfig config = PrintLabConfig.from(env::get, context::get);

        assertThat(config.databaseUrl()).isEqualTo("jdbc:postgresql://env/pwl");
        assertThat(config.databaseUser()).isEqualTo("context-user");
        assertThat(config.hasDatabase()).isTrue();
    }

    @Test
    public void blankValuesFallBackAndDefaultsAreTheWorkingDirectory() {
        Map<String, String> env = Map.of(PrintLabConfig.DB_URL, " ");

        PrintLabConfig config = PrintLabConfig.from(env::get, name -> null);

        assertThat(config.hasDatabase()).isFalse();
        assertThat(config.home()).isEqualTo(Path.of("").toAbsolutePath().normalize());
        assertThat(config.outputRoot()).isEqualTo(config.home().resolve("output"));
        assertThat(config.devicesFile())
                .isEqualTo(config.home().resolve("config").resolve("input-devices.yaml"));
    }

    @Test
    public void homeFromEnvironmentSetsTheFolders() {
        PrintLabConfig config = PrintLabConfig.from(
                Map.of(PrintLabConfig.HOME, "/srv/printlab")::get, name -> null);

        assertThat(config.home()).isEqualTo(Path.of("/srv/printlab").toAbsolutePath().normalize());
        assertThat(config.workRoot()).isEqualTo(config.home().resolve("work"));
        assertThat(config.errorDir()).isEqualTo(config.home().resolve("error"));
    }
}
