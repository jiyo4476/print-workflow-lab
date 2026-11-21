package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.input.InputDevice;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** Which input devices {@code App run} starts, for each way of calling it. */
public class AppDevicesTest {

    @Test
    public void noArgumentsAtAllLoadTheDevicesFile() throws Exception {
        // ./mvnw exec:java passes no arguments: the command defaults to run.
        assertThat(App.devices(new String[0])).extracting(InputDevice::name)
                .containsExactly("pdf-statements", "afp-statements", "afp-legacy");
    }

    @Test
    public void runAloneLoadsTheDevicesFile() throws Exception {
        assertThat(App.devices(new String[] {"run"})).hasSize(3);
    }

    @Test
    public void runWithDevicesLoadsThatFile() throws Exception {
        assertThat(App.devices(new String[] {"run", "--devices", "config/input-devices.yaml"}))
                .hasSize(3);
    }

    @Test
    public void runWithAWorkflowIsOneDefaultDeviceOnTheHotFolder() throws Exception {
        List<InputDevice> devices = App.devices(
                new String[] {"run", "workflows/statements-afp.yaml", "*.afp"});

        assertEquals(List.of(new InputDevice("default", Path.of("hotfolder"), "*.afp",
                Path.of("workflows/statements-afp.yaml"))), devices);
    }

    @Test
    public void runWithAWorkflowDefaultsToPdfFiles() throws Exception {
        assertEquals("*.pdf",
                App.devices(new String[] {"run", "workflows/statements.yaml"}).get(0).glob());
    }
}
