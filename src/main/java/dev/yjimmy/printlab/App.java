package dev.yjimmy.printlab;

import dev.yjimmy.printlab.input.HotFolderWatcher;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point.
 *
 * <pre>
 *   run [workflow.yaml]          watch ./hotfolder and process each PDF that lands there
 *   generate &lt;count&gt; &lt;out.pdf&gt; [badAddresses]
 *                                write a fake statement print run for testing, optionally with
 *                                some bad addresses for the exception queue
 * </pre>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "run";
        switch (command) {
            case "run" -> run(Path.of(args.length > 1 ? args[1] : "workflows/statements.yaml"));
            case "generate" -> {
                if (args.length < 3) {
                    usage();
                    return;
                }
                Path out = Path.of(args[2]);
                int bad = args.length > 3 ? Integer.parseInt(args[3]) : 0;
                new StatementPdfGenerator(System.currentTimeMillis())
                        .generate(Integer.parseInt(args[1]), bad, out);
                System.out.println("Wrote " + out.toAbsolutePath());
            }
            default -> usage();
        }
    }

    private static void run(Path workflowFile) throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.load(workflowFile);
        WorkflowEngine engine = new WorkflowEngine(
                definition, StepRegistry.withBuiltIns(), Path.of("error"),
                // Millisecond ticks: Windows clocks report 100 ns digits nobody needs to read.
                Clock.tickMillis(ZoneOffset.UTC));
        Path workRoot = Path.of("work");

        // One job at a time for now; the watcher thread never blocks on processing.
        ExecutorService jobs = Executors.newSingleThreadExecutor();
        // execute, not submit: submit would park anything thrown in a Future nobody reads, and the
        // job would vanish without even a log line.
        new HotFolderWatcher(Path.of("hotfolder"), "*.pdf", file -> jobs.execute(() -> {
            Job job;
            try {
                // Move the file out of the hot folder first so it is never picked up twice.
                String id = Job.newId();
                Path workDir = Files.createDirectories(workRoot.resolve(id));
                Path input = Files.move(file, workDir.resolve(file.getFileName()));
                job = new Job(id, input, workDir);
            } catch (Exception e) {
                System.err.println("Could not start job for " + file + ": " + e.getMessage());
                return;
            }
            // The engine fails jobs cleanly for step errors; these are for whatever it lets
            // escape (OutOfMemoryError, a bug in the engine itself).
            try {
                engine.run(job);
            } catch (Error err) {
                System.err.println(job + " stopped unexpectedly; its files are in "
                        + job.workDir().toAbsolutePath());
                // The worker thread's uncaught-exception handler prints the stack trace once;
                // the executor then starts a fresh thread for the next job.
                throw err;
            } catch (RuntimeException e) {
                System.err.println(job + " stopped unexpectedly; its files are in "
                        + job.workDir().toAbsolutePath());
                e.printStackTrace();
            }
        })).watch();
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  run [workflow.yaml]          watch ./hotfolder and process PDFs
                  generate <count> <out.pdf> [badAddresses]
                                               write a fake statement print run, optionally
                                               with some bad addresses""");
    }
}
