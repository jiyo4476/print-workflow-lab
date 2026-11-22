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
import java.util.concurrent.RejectedExecutionException;
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
 * {@code inputDevice} job property) and then queued for the job executor. A watcher that fails
 * is restarted after the rescan interval.
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
    private volatile Consumer<InputDevice> onWatcherFailed = device -> { };

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
        if (devices.isEmpty()) {
            throw new IllegalArgumentException("There are no input devices to run");
        }
        InputDeviceConfig.validate(devices);
        for (InputDevice device : devices) {
            WorkflowEngine engine;
            try {
                engine = new WorkflowEngine(loader.load(device.workflow()), registry, errorDir,
                        clock);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where(device) + e.getMessage(), e);
            } catch (IOException e) {
                // A workflow that isn't valid YAML, or has an unknown key.
                throw new IOException(where(device) + e.getMessage(), e);
            }
            this.devices.add(new Running(device, engine));
        }
        this.intake = new JobIntake(workRoot);
        this.onJobDone = onJobDone;
        this.stableCheck = stableCheck;
        this.rescanInterval = rescanInterval;
    }

    private static String where(InputDevice device) {
        return "Input device '" + device.name() + "', workflow " + device.workflow() + ": ";
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

    /**
     * Watches one device's folder until the thread is interrupted. A watcher that fails (the
     * folder deleted, unreadable, or not creatable) is started again after the rescan interval,
     * so one bad folder never stops its device for good.
     */
    private void watch(Running running) {
        InputDevice device = running.device();
        Thread.currentThread().setName("input-device-" + device.name());
        HotFolderWatcher watcher = new HotFolderWatcher(device.folder(), device.glob(),
                file -> receive(running, file), stableCheck, rescanInterval);
        String lastProblem = null;
        while (true) {
            try {
                watcher.watch();
                return; // watch() only ends by throwing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                // Log a problem once, not on every retry; a different one is logged again.
                String problem = e.toString();
                if (!problem.equals(lastProblem)) {
                    log.error("Input device '{}' stopped watching {}; retrying every {}s: {}",
                            device.name(), device.folder().toAbsolutePath(),
                            rescanInterval.toSeconds(), problem);
                } else {
                    log.debug("Input device '{}' still can't watch: {}", device.name(), problem);
                }
                lastProblem = problem;
                onWatcherFailed.accept(device);
            }
            try {
                Thread.sleep(rescanInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The watcher's handler: claims {@code file} on the watcher's thread, before returning (if
     * the move fails, the file is still in the folder and the watcher retries it), then queues
     * the job. If the job queue has been closed (the app is stopping), the claim is undone so the
     * file is picked up on the next start instead of being stranded in {@code work/}.
     */
    private void receive(Running running, Path file) throws IOException {
        InputDevice device = running.device();
        Job job = intake.claim(file, device.name());
        log.info("{} received {} from input device '{}'", job.id(), file.getFileName(),
                device.name());
        try {
            // execute, not submit: submit would park anything thrown in a Future nobody reads.
            jobs.execute(() -> process(running.engine(), job));
        } catch (RejectedExecutionException stopping) {
            try {
                intake.unclaim(job, file.getParent());
                log.info("{}: stopping, so {} went back to {}", job.id(), file.getFileName(),
                        file.getParent());
            } catch (IOException e) {
                log.error("{}: stopping, and {} could not be put back; its files are in {}: {}",
                        job.id(), file.getFileName(), job.workDir().toAbsolutePath(),
                        e.toString());
            }
        }
    }

    /** {@link #receive(Running, Path)} for the named device, for tests. */
    void receive(String deviceName, Path file) throws IOException {
        receive(devices.stream().filter(r -> r.device().name().equals(deviceName)).findFirst()
                .orElseThrow(), file);
    }

    /** Tests: called on the watcher's thread each time a device's watcher fails. */
    void onWatcherFailed(Consumer<InputDevice> listener) {
        onWatcherFailed = listener;
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
     * Stops watching (a file claimed after this goes back to its folder), then waits for the
     * claimed jobs to finish (they've left the hot folder, so dropping them would strand them in
     * {@code work/}). Both waits share {@code timeout}. Jobs still running after that are
     * interrupted, which fails them into the error folder to be resubmitted; they get up to
     * {@link #INTERRUPT_GRACE} more to do that.
     */
    public synchronized void close(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (watchers != null) {
            watchers.shutdownNow();
            if (!watchers.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
                // A watcher still in a claim: once the job queue is closed, receive() puts its
                // file back in the folder instead of queueing it.
                log.warn("An input device is still claiming a file; closing the job queue anyway");
            }
        }
        jobs.shutdown();
        if (!jobs.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS)) {
            jobs.shutdownNow();
            jobs.awaitTermination(INTERRUPT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** How long an interrupted job gets to fail itself into the error folder. */
    static final Duration INTERRUPT_GRACE = Duration.ofSeconds(5);

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** {@link #close(Duration)} with a one-minute timeout. */
    @Override
    public void close() throws InterruptedException {
        close(Duration.ofMinutes(1));
    }
}
