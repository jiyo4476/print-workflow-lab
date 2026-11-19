package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an external command as a workflow step. A non-zero exit code or a timeout fails the job.
 *
 * <p>Arguments may use {@code ${jobId}}, {@code ${workDir}} and {@code ${inputFile}}. The command
 * runs in the job's work directory; its output is written to {@code external-<step>.log} there.
 */
public final class RunExternalProgramStep implements Step {

    private static final Logger log = LoggerFactory.getLogger(RunExternalProgramStep.class);

    private final List<String> command;
    private final int timeoutSeconds;

    public RunExternalProgramStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.command = params.stringList("command");
        this.timeoutSeconds = params.integer("timeoutSeconds", 60);
    }

    @Override
    public void execute(Job job) throws Exception {
        List<String> resolved = command.stream()
                .map(arg -> arg
                        .replace("${jobId}", job.id())
                        .replace("${workDir}", job.workDir().toString())
                        .replace("${inputFile}", job.inputFile().toString()))
                .toList();

        Path logFile = job.workDir().resolve("external-" + job.currentStep() + ".log");
        Process process = new ProcessBuilder(resolved)
                .directory(job.workDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Don't leave the program running after the job has given up on it.
            stop(process);
            throw e;
        }
        if (!finished) {
            stop(process);
            throw new IllegalStateException("Timed out after " + timeoutSeconds + "s: " + resolved);
        }
        int exit = process.exitValue();
        log.info("{} external command exited {}", job.id(), exit);
        if (exit != 0) {
            throw new IllegalStateException("Exit code " + exit + " from " + resolved
                    + " (see " + logFile.getFileName() + ")");
        }
    }

    /**
     * Kills the program and anything it started (a shell's child, say): killing only the
     * process itself would leave its children running.
     */
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
