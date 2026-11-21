package dev.yjimmy.printlab.input;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a set of input devices: one {@link HotFolderWatcher} per device, each on its own thread,
 * all handing jobs to one shared executor that runs them one at a time.
 *
 * <p>Every device's workflow is loaded and its {@link WorkflowEngine} built in the constructor,
 * so a bad workflow stops startup before any folder is watched. A file a device picks up is
 * claimed on the watcher's thread ({@link JobIntake}, which records the device as the
 * {@code inputDevice} job property) and then queued for the job executor.
 */
public final class InputDevices implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InputDevices.class);

    /** Loads a device's workflow file; tests swap in one that redirects output. */
    @FunctionalInterface
    public interface WorkflowLoader {
        WorkflowDefinition load(Path workflowFile) throws IOException;
    }

    private record Running(InputDevice device, WorkflowEngine engine) {}

    private final List<Running> devices = new ArrayList<>();
    private final JobIntake intake;
    private final Consumer<Job> onJobDone;
    private final Duration stableCheck;
    private final Duration rescanInterval;
    private final ExecutorService jobs = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "jobs"));
    private ExecutorService watchers;

    /**
     * @param onJobDone called on the job thread after each job finishes, complete or not
     */
    public InputDevices(List<InputDevice> devices, WorkflowLoader loader, StepRegistry registry,
            Path workRoot, Path errorDir, Clock clock, Consumer<Job> onJobDone)
            throws IOException {
        this(devices, loader, registry, workRoot, errorDir, clock, onJobDone,
                HotFolderWatcher.STABLE_CHECK, HotFolderWatcher.RESCAN_INTERVAL);
    }

    InputDevices(List<InputDevice> devices, WorkflowLoader loader, StepRegistry registry,
            Path workRoot, Path errorDir, Clock clock, Consumer<Job> onJobDone,
            Duration stableCheck, Duration rescanInterval) throws IOException {
        InputDeviceConfig.validate(devices);
        for (InputDevice device : devices) {
            WorkflowEngine engine;
            try {
                engine = new WorkflowEngine(loader.load(device.workflow()), registry, errorDir,
                        clock);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Input device '" + device.name()
                        + "', workflow " + device.workflow() + ": " + e.getMessage(), e);
            }
            this.devices.add(new Running(device, engine));
        }
        this.intake = new JobIntake(workRoot);
        this.onJobDone = onJobDone;
        this.stableCheck = stableCheck;
        this.rescanInterval = rescanInterval;
    }

    /** Starts watching every device's folder; returns at once. */
    public synchronized void start() {
        if (watchers != null) {
            throw new IllegalStateException("already started");
        }
        watchers = Executors.newFixedThreadPool(devices.size(),
                runnable -> new Thread(runnable, "input-device"));
        for (Running running : devices) {
            watchers.execute(() -> watch(running));
        }
    }

    private void watch(Running running) {
        InputDevice device = running.device();
        Thread.currentThread().setName("input-device-" + device.name());
        HotFolderWatcher watcher = new HotFolderWatcher(device.folder(), device.glob(), file -> {
            // Claim on the watcher's thread, before returning: if the move fails, the file is
            // still in the folder and the watcher retries it.
            Job job = intake.claim(file, device.name());
            log.info("{} received {} from input device '{}'", job.id(), file.getFileName(),
                    device.name());
            // execute, not submit: submit would park anything thrown in a Future nobody reads.
            jobs.execute(() -> process(running.engine(), job));
        }, stableCheck, rescanInterval);
        try {
            watcher.watch();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("Input device '{}' stopped: {}", device.name(), e.toString());
        }
    }

    private void process(WorkflowEngine engine, Job job) {
        try {
            engine.run(job);
        } catch (RuntimeException e) {
            // The engine fails jobs cleanly for step errors; this is for a bug in the engine.
            log.error("{} stopped unexpectedly; its files are in {}", job,
                    job.workDir().toAbsolutePath(), e);
        } catch (Error e) {
            // OutOfMemoryError and the like: say where the job's files are, then let it escape.
            // The executor starts a fresh thread for the next job.
            log.error("{} stopped unexpectedly; its files are in {}", job,
                    job.workDir().toAbsolutePath());
            throw e;
        } finally {
            onJobDone.accept(job);
        }
    }

    /** The devices, in config order. */
    public List<InputDevice> devices() {
        return devices.stream().map(Running::device).toList();
    }

    /**
     * Stops watching, then waits up to {@code timeout} for the claimed jobs to finish (they've
     * left the hot folder, so dropping them would strand them in {@code work/}). Jobs still
     * running after that are interrupted, which fails them into the error folder to be resubmitted.
     */
    public synchronized void close(Duration timeout) throws InterruptedException {
        if (watchers != null) {
            watchers.shutdownNow();
            watchers.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        jobs.shutdown();
        if (!jobs.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            jobs.shutdownNow();
            jobs.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** {@link #close(Duration)} with a one-minute timeout. */
    @Override
    public void close() throws InterruptedException {
        close(Duration.ofMinutes(1));
    }
}
