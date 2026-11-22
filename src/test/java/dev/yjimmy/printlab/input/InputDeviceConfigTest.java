package dev.yjimmy.printlab.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InputDeviceConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void loadsDevicesFromYaml() throws Exception {
        // The config the app ships with; its workflow paths are relative to the project root.
        List<InputDevice> devices = InputDeviceConfig.load(Path.of("config/input-devices.yaml"));

        assertThat(devices).extracting(InputDevice::name)
                .containsExactly("pdf-statements", "afp-statements", "afp-legacy");
        assertEquals(new InputDevice("pdf-statements", Path.of("hotfolder/pdf"), "*.pdf",
                Path.of("workflows/statements.yaml")), devices.get(0));
    }

    @Test
    public void missingWorkflowFileFailsAtStartup() throws Exception {
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot/pdf, glob: "*.pdf", workflow: workflows/nope.yaml }
                """);

        assertThat(fail(config)).startsWith("Input device 'pdf' names a workflow that doesn't"
                + " exist: ").contains("nope.yaml");
    }

    @Test
    public void duplicateDeviceNamesFail() throws Exception {
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot/a, glob: "*.pdf", workflow: workflows/statements.yaml }
                  - { name: pdf, folder: hot/b, glob: "*.pdf", workflow: workflows/statements.yaml }
                """);

        assertEquals("Two input devices are named 'pdf'", fail(config));
    }

    @Test
    public void twoDevicesOnTheSameFolderFail() throws Exception {
        // The same folder written another way, with another glob: still one folder.
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot/in, glob: "*.pdf", workflow: workflows/statements.yaml }
                  - { name: afp, folder: hot/x/../in/, glob: "*.afp",
                      workflow: workflows/statements-afp.yaml }
                """);

        assertThat(fail(config)).startsWith("Input devices 'pdf' and 'afp' both use the folder")
                .endsWith("each device needs a folder of its own");
    }

    @Test
    public void nestedFoldersAreDifferentFolders() throws Exception {
        // Watching isn't recursive, so hot/ and hot/afp/ don't overlap.
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot, glob: "*.pdf", workflow: workflows/statements.yaml }
                  - { name: afp, folder: hot/afp, glob: "*.afp",
                      workflow: workflows/statements-afp.yaml }
                """);

        assertThat(InputDeviceConfig.load(config)).hasSize(2);
    }

    @Test
    public void invalidGlobFails() throws Exception {
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot, glob: "*.{pdf", workflow: workflows/statements.yaml }
                """);

        assertThat(fail(config)).startsWith("Input device 'pdf' has an invalid glob '*.{pdf'");
    }

    @Test
    public void missingFieldFailsNamingIt() throws Exception {
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot, workflow: workflows/statements.yaml }
                """);

        assertEquals("Input device 'pdf' needs a 'glob'", fail(config));
    }

    @Test
    public void emptyDeviceListFails() throws Exception {
        assertThat(fail(config("devices: []\n"))).contains("lists no input devices");
    }

    @Test
    public void unknownKeyFails() throws Exception {
        Path config = config("""
                devices:
                  - { name: pdf, folder: hot, glob: "*.pdf", workflow: workflows/statements.yaml,
                      wrokflow: typo.yaml }
                """);

        assertThat(assertThrows(Exception.class, () -> InputDeviceConfig.load(config)))
                .hasMessageContaining("wrokflow");
    }

    /** A config file whose relative workflow paths point at the project's real workflows. */
    private Path config(String yaml) throws Exception {
        return Files.writeString(tmp.getRoot().toPath().resolve("devices.yaml"), yaml);
    }

    private static String fail(Path config) {
        return assertThrows(IllegalArgumentException.class, () -> InputDeviceConfig.load(config))
                .getMessage();
    }
}
