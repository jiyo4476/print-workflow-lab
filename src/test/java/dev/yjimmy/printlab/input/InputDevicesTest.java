package dev.yjimmy.printlab.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real input devices on temp folders, running the real workflows with output redirected. */
public class InputDevicesTest {

    private static final long WAIT_SECONDS = 30;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private final BlockingQueue<Job> done = new LinkedBlockingQueue<>();
    private InputDevices devices;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath();
    }

    @After
    public void tearDown() throws Exception {
        if (devices != null) {
            devices.close(Duration.ofSeconds(WAIT_SECONDS));
        }
    }

    private InputDevice device(String name, String glob, String workflow) {
        return new InputDevice(name, root.resolve("hot").resolve(name), glob,
                Path.of("workflows", workflow + ".yaml"));
    }

    private void start(InputDevice... list) throws Exception {
        // Each workflow file is loaded with its WriteOutput redirected into the temp folder.
        devices = new InputDevices(List.of(list), file -> TestWorkflows.load(
                file.getFileName().toString().replace(".yaml", ""), root.resolve("output")),
                StepRegistry.withBuiltIns(), root.resolve("work"), root.resolve("error"),
                TickingClock.oneSecondTicks(), done::add,
                Duration.ofMillis(10), Duration.ofMillis(100));
        devices.start();
    }

    /** A path in a staging folder, where an input is written before it's dropped. */
    private Path staged(String name) throws Exception {
        return Files.createDirectories(root.resolve("staging")).resolve(name);
    }

    /** Moves a finished file into a hot folder in one step, as a copy tool would rename it. */
    private static void drop(Path file, Path folder) throws Exception {
        Files.move(file, Files.createDirectories(folder).resolve(file.getFileName()),
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Waits for {@code count} jobs to finish, keyed by input file name. */
    private Map<String, Job> awaitJobs(int count) throws InterruptedException {
        Map<String, Job> jobs = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Job job = done.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("job " + (i + 1) + " of " + count + " finished in time", job);
            jobs.put(job.inputFile().getFileName().toString(), job);
        }
        return jobs;
    }

    @Test
    public void pdfAndAfpDroppedInTheirFoldersRunTheirWorkflows() throws Exception {
        InputDevice pdf = device("pdf-statements", "*.pdf", "statements");
        InputDevice afp = device("afp-statements", "*.afp", "statements-afp");
        start(pdf, afp);

        Path pdfFile = staged("run.pdf");
        new StatementPdfGenerator(42).generate(5, pdfFile);
        drop(pdfFile, pdf.folder());
        Path afpFile = staged("run.afp");
        new StatementAfpGenerator(42).generate(4, afpFile);
        drop(afpFile, afp.folder());

        Map<String, Job> jobs = awaitJobs(2);
        Job pdfJob = jobs.get("run.pdf");
        Job afpJob = jobs.get("run.afp");
        assertEquals(pdfJob.errorMessage(), JobState.COMPLETE, pdfJob.state());
        assertEquals(afpJob.errorMessage(), JobState.COMPLETE, afpJob.state());
        assertEquals("pdf documents", 5, pdfJob.documents().size());
        assertEquals("afp documents", 4, afpJob.documents().size());
        assertThat(pdfJob.documents()).allSatisfy(d -> assertEquals("pdf", d.property("format")));
        assertThat(afpJob.documents()).allSatisfy(d -> assertEquals("afp", d.property("format")));
    }

    @Test
    public void jobRecordsItsInputDevice() throws Exception {
        InputDevice pdf = device("pdf-statements", "*.pdf", "statements");
        start(pdf);

        Path file = staged("run.pdf");
        new StatementPdfGenerator(42).generate(2, file);
        drop(file, pdf.folder());

        Job job = awaitJobs(1).get("run.pdf");
        assertEquals("inputDevice", "pdf-statements", job.properties().get("inputDevice"));
    }

    @Test
    public void jobTicketTravelsWithItsInputOnAnyDevice() throws Exception {
        InputDevice afp = device("afp-statements", "*.afp", "statements-afp");
        start(afp);
        Path folder = Files.createDirectories(afp.folder());

        // The ticket goes in first; it tries to change the device, which a ticket can't.
        Files.writeString(folder.resolve("run.afp.jobprops"),
                "customer=ACME Bank\ninputDevice=somewhere-else\n");
        Path file = staged("run.afp");
        new StatementAfpGenerator(42).generate(3, file);
        drop(file, folder);

        Job job = awaitJobs(1).get("run.afp");
        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        assertEquals("customer", "ACME Bank", job.properties().get("customer"));
        assertEquals("inputDevice", "afp-statements", job.properties().get("inputDevice"));
        assertThat(folder.resolve("run.afp.jobprops")).as("ticket left the hot folder")
                .doesNotExist();
    }

    @Test
    public void badWorkflowFailsAtStartupNamingTheDevice() throws Exception {
        Path broken = Files.writeString(root.resolve("broken.yaml"), """
                name: broken
                phases:
                  prepare:
                    - { name: x, type: NoSuchStep }
                """);
        InputDevice device = new InputDevice("bad", root.resolve("hot/bad"), "*.pdf", broken);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new InputDevices(List.of(device),
                        WorkflowDefinition::load,
                        StepRegistry.withBuiltIns(), root.resolve("work"), root.resolve("error"),
                        TickingClock.oneSecondTicks(), job -> { }));

        assertThat(e.getMessage()).startsWith("Input device 'bad', workflow ")
                .contains("Unknown step type 'NoSuchStep'");
    }

    @Test
    public void devicesSharingAFolderFailAtStartup() {
        InputDevice a = new InputDevice("a", root.resolve("hot"), "*.pdf",
                Path.of("workflows/statements.yaml"));
        InputDevice b = new InputDevice("b", root.resolve("hot"), "*.afp",
                Path.of("workflows/statements-afp.yaml"));

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new InputDevices(List.of(a, b),
                        WorkflowDefinition::load,
                        StepRegistry.withBuiltIns(), root.resolve("work"), root.resolve("error"),
                        TickingClock.oneSecondTicks(), job -> { })).getMessage())
                .contains("both use the folder");
    }
}
