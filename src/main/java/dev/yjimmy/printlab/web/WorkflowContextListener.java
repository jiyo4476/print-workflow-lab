package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.input.InputDevice;
import dev.yjimmy.printlab.input.InputDeviceConfig;
import dev.yjimmy.printlab.input.InputDevices;
import dev.yjimmy.printlab.steps.WriteOutputStep;
import dev.yjimmy.printlab.store.Database;
import dev.yjimmy.printlab.store.JdbcJobStore;
import dev.yjimmy.printlab.workflow.FileJobStore;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobStore;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the workflow engine and the hot folders when the web app starts, and stops them when it
 * stops (PRD-14 FR-8), so a redeploy leaves no watcher threads behind.
 *
 * <p>It also opens the database the API reads, and puts the API's services in the
 * {@link ServletContext} for the servlets. Without {@code PRINTLAB_DB_URL} the jobs are kept in
 * their {@code job.json} files, and the API answers 503.
 *
 * <p>The hot folders come from {@code config/input-devices.yaml} under {@code PRINTLAB_HOME}. The
 * app starts without that file, but then no files are picked up. Relative paths in it, in the
 * workflows, and in a {@code WriteOutput} step's {@code outputDir} are taken from
 * {@code PRINTLAB_HOME}, not the working directory.
 */
@WebListener
public final class WorkflowContextListener implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowContextListener.class);

    private final Function<String, String> env;
    private final Consumer<Job> onJobDone;
    private InputDevices running;

    /** The container's constructor: reads the settings from the environment and the WAR. */
    public WorkflowContextListener() {
        this(System::getenv, job -> { });
    }

    /**
     * @param env reads environment variables
     * @param onJobDone called on the job's thread after each job finishes (tests wait on it)
     */
    WorkflowContextListener(Function<String, String> env, Consumer<Job> onJobDone) {
        this.env = env;
        this.onJobDone = onJobDone;
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        PrintLabConfig config = PrintLabConfig.from(env, context::getInitParameter);
        Clock clock = Clock.tickMillis(ZoneOffset.UTC);

        JdbcJobStore jobs = null;
        JobStore store = new FileJobStore();
        if (config.hasDatabase()) {
            jobs = openDatabase(config, clock);
            store = jobs;
        } else {
            log.warn("PRINTLAB_DB_URL is not set: jobs are kept in job.json files and the API"
                    + " answers 503");
        }
        InputDevices devices = startDevices(config, clock, store);
        RetryService retries = devices == null || jobs == null ? null
                : new RetryService(jobs, runnerFor(devices), config.workRoot(), config.errorDir());
        context.setAttribute(ApiServices.ATTRIBUTE,
                new ApiServices(jobs, config.outputRoot(), retries));
    }

    /** Retries run on the input devices' job thread, like every other job. */
    private static RetryRunner runnerFor(InputDevices devices) {
        return new RetryRunner() {
            @Override
            public void check(String device, String step) {
                devices.checkRetry(device, step);
            }

            @Override
            public void start(Job job, String device, String step, Runnable done) {
                devices.retry(job, device, step, done);
            }
        };
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (running == null) {
            return;
        }
        try {
            running.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = null;
    }

    private static JdbcJobStore openDatabase(PrintLabConfig config, Clock clock) {
        try {
            Database db = Database.open(config.databaseUrl(), config.databaseUser(),
                    config.databasePassword());
            log.info("Saving jobs to {}", db.url());
            return new JdbcJobStore(db, clock);
        } catch (SQLException e) {
            // Fails the deployment: a configured database that can't be reached is an error.
            throw new IllegalStateException("could not open the database at "
                    + config.databaseUrl() + ": " + e.getMessage(), e);
        }
    }

    /** Starts the input devices, or returns {@code null} when there is no devices file. */
    private InputDevices startDevices(PrintLabConfig config, Clock clock, JobStore store) {
        if (!Files.exists(config.devicesFile())) {
            log.warn("No input devices file at {}: no hot folders are watched",
                    config.devicesFile());
            return null;
        }
        List<InputDevice> devices = devices(config);
        StepRegistry registry = StepRegistry.withBuiltIns();
        registry.register("WriteOutput",
                params -> new WriteOutputStep(relativeTo(config, params)));
        try {
            running = new InputDevices(devices, WorkflowDefinition::load, registry,
                    config.workRoot(), config.errorDir(), clock, onJobDone, store);
        } catch (IOException e) {
            throw new IllegalStateException("could not load the input devices: " + e.getMessage(),
                    e);
        }
        running.start();
        log.info("Watching {} input device(s) under {}", devices.size(), config.home());
        return running;
    }

    /** The devices from the file, with their folders and workflows under {@code PRINTLAB_HOME}. */
    private static List<InputDevice> devices(PrintLabConfig config) {
        try {
            return InputDeviceConfig.load(config.devicesFile(), config.home());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + config.devicesFile() + ": "
                    + e.getMessage(), e);
        }
    }

    /** The step parameters with a relative {@code outputDir} taken from {@code PRINTLAB_HOME}. */
    private static Map<String, Object> relativeTo(PrintLabConfig config,
            Map<String, Object> params) {
        Map<String, Object> resolved = new HashMap<>(params);
        Object dir = params.getOrDefault("outputDir", "output");
        resolved.put("outputDir", config.home().resolve(String.valueOf(dir)).toString());
        return resolved;
    }
}
