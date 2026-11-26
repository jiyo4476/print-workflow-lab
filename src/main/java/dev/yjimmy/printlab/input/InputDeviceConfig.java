package dev.yjimmy.printlab.input;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the input devices from YAML ({@code config/input-devices.yaml}):
 * <pre>
 *   devices:
 *     - name: pdf-statements
 *       folder: hotfolder/pdf
 *       glob: "*.pdf"
 *       workflow: workflows/statements.yaml
 * </pre>
 * Relative paths are resolved against the working directory, like the rest of the app's paths,
 * unless the file is loaded with a base directory (the web app does, from {@code PRINTLAB_HOME}).
 * Everything is checked here, before any device starts: every field is present, names are
 * unique, no two devices share a folder, each glob is valid, and each workflow file exists.
 * Unknown keys fail too, so a typo doesn't silently drop a setting.
 */
public final class InputDeviceConfig {

    private InputDeviceConfig() {
    }

    private record YamlDevice(String name, String folder, String glob, String workflow) {}

    private record YamlConfig(List<YamlDevice> devices) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public static List<InputDevice> load(Path file) throws IOException {
        return load(file, Path.of(""));
    }

    /**
     * Loads the devices file, resolving each relative folder and workflow against {@code base}.
     *
     * @param base the directory the file's relative paths are relative to
     */
    public static List<InputDevice> load(Path file, Path base) throws IOException {
        YamlConfig config = YAML.readValue(file.toFile(), YamlConfig.class);
        if (config == null || config.devices() == null || config.devices().isEmpty()) {
            throw new IllegalArgumentException(file + " lists no input devices under 'devices:'");
        }
        List<InputDevice> devices = new ArrayList<>();
        for (int i = 0; i < config.devices().size(); i++) {
            devices.add(toDevice(i + 1, config.devices().get(i), base));
        }
        validate(devices);
        return devices;
    }

    private static InputDevice toDevice(int number, YamlDevice d, Path base) {
        if (d == null) {
            throw new IllegalArgumentException("Input device " + number + " is empty");
        }
        String label = d.name() == null || d.name().isBlank()
                ? "Input device " + number : "Input device '" + d.name() + "'";
        require(label, "name", d.name());
        require(label, "folder", d.folder());
        require(label, "glob", d.glob());
        require(label, "workflow", d.workflow());
        return new InputDevice(d.name().strip(), base.resolve(d.folder()), d.glob(),
                base.resolve(d.workflow()));
    }

    private static void require(String label, String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " needs a '" + key + "'");
        }
    }

    /**
     * Checks a device list however it was built: unique names, one device per folder, valid
     * globs, existing workflow files.
     */
    public static void validate(List<InputDevice> devices) {
        Map<String, InputDevice> byName = new HashMap<>();
        Map<Path, InputDevice> byFolder = new HashMap<>();
        for (InputDevice device : devices) {
            InputDevice sameName = byName.putIfAbsent(device.name(), device);
            if (sameName != null) {
                throw new IllegalArgumentException("Two input devices are named '"
                        + device.name() + "'");
            }
            InputDevice sameFolder = byFolder.putIfAbsent(folderKey(device.folder()), device);
            if (sameFolder != null) {
                throw new IllegalArgumentException("Input devices '" + sameFolder.name()
                        + "' and '" + device.name() + "' both use the folder "
                        + device.folder() + "; each device needs a folder of its own");
            }
            try {
                FileSystems.getDefault().getPathMatcher("glob:" + device.glob());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Input device '" + device.name()
                        + "' has an invalid glob '" + device.glob() + "': " + e.getMessage(), e);
            }
            if (!Files.isRegularFile(device.workflow())) {
                throw new IllegalArgumentException("Input device '" + device.name()
                        + "' names a workflow that doesn't exist: "
                        + device.workflow().toAbsolutePath());
            }
        }
    }

    /**
     * The same folder written two ways ({@code hotfolder/pdf}, {@code ./hotfolder/pdf/..\pdf})
     * gives the same key. Windows ignores case in paths, so there the key does too.
     */
    static Path folderKey(Path folder) {
        Path normal = folder.toAbsolutePath().normalize();
        if (FileSystems.getDefault().getSeparator().equals("\\")) {
            return Path.of(normal.toString().toLowerCase(Locale.ROOT));
        }
        return normal;
    }
}
