package dev.yjimmy.printlab;

import dev.yjimmy.printlab.afp.AfpDump;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.input.HotFolderWatcher;
import dev.yjimmy.printlab.input.JobIntake;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point.
 *
 * <pre>
 *   run [workflow.yaml] [glob]   watch ./hotfolder and process each file matching glob (default
 *                                *.pdf, any case) that lands there
 *   generate &lt;count&gt; &lt;out.pdf&gt; [badAddresses] [seed]
 *                                write a fake statement print run for testing, optionally with
 *                                some bad addresses for the exception queue
 *   generate-afp &lt;count&gt; &lt;out.afp&gt; [badAddresses] [seed] [--tle-names legacy]
 *                                the same run as AFP, one page group with TLEs per statement;
 *                                the same seed gives the same statements as generate.
 *                                --tle-names legacy names the TLEs ACCT_NO, CUST_NAME and
 *                                ZIP_CODE (workflows/statements-afp-legacy.yaml maps them)
 *   dump-afp &lt;file.afp&gt; [codePage]
 *                                print an AFP file's structured fields and TLEs; exits 1 if
 *                                the file has structural problems
 * </pre>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "run";
        switch (command) {
            case "run" -> run(Path.of(args.length > 1 ? args[1] : "workflows/statements.yaml"),
                    args.length > 2 ? args[2] : "*.pdf");
            case "generate", "generate-afp" -> {
                StatementAfpGenerator.TleNames names = StatementAfpGenerator.TleNames.PROPERTY_NAMES;
                int flag = Arrays.asList(args).indexOf("--tle-names");
                if (flag >= 0) {
                    if (command.equals("generate") || flag + 1 >= args.length) {
                        usage();
                        return;
                    }
                    names = StatementAfpGenerator.TleNames.named(args[flag + 1]);
                    List<String> rest = new ArrayList<>(Arrays.asList(args));
                    rest.subList(flag, flag + 2).clear();
                    args = rest.toArray(String[]::new);
                }
                if (args.length < 3) {
                    usage();
                    return;
                }
                int count = Integer.parseInt(args[1]);
                Path out = Path.of(args[2]);
                int bad = args.length > 3 ? Integer.parseInt(args[3]) : 0;
                long seed = args.length > 4 ? Long.parseLong(args[4]) : System.currentTimeMillis();
                if (command.equals("generate")) {
                    new StatementPdfGenerator(seed).generate(count, bad, out);
                } else {
                    new StatementAfpGenerator(seed).generate(count, bad, names, out);
                }
                System.out.println("Wrote " + out.toAbsolutePath() + " (seed " + seed + ")");
            }
            case "dump-afp" -> {
                if (args.length < 2) {
                    usage();
                    return;
                }
                Charset codePage = args.length > 2 ? Charset.forName(args[2]) : Ebcdic.IBM037;
                int problems;
                try (InputStream in = Files.newInputStream(Path.of(args[1]))) {
                    problems = new AfpDump(codePage).dump(in, System.out);
                }
                if (problems > 0) {
                    System.exit(1);
                }
            }
            default -> usage();
        }
    }

    private static void run(Path workflowFile, String glob) throws Exception {
        WorkflowDefinition definition = WorkflowDefinition.load(workflowFile);
        WorkflowEngine engine = new WorkflowEngine(
                definition, StepRegistry.withBuiltIns(), Path.of("error"),
                // Millisecond ticks: Windows clocks report 100 ns digits nobody needs to read.
                Clock.tickMillis(ZoneOffset.UTC));
        JobIntake intake = new JobIntake(Path.of("work"));

        // One job at a time for now; the watcher thread never blocks on processing.
        ExecutorService jobs = Executors.newSingleThreadExecutor();
        new HotFolderWatcher(Path.of("hotfolder"), glob, file -> {
            // Claim the file on the watcher's thread, before returning: if the move fails, the
            // file is still in the hot folder and the watcher retries it.
            Job job = intake.claim(file);
            // execute, not submit: submit would park anything thrown in a Future nobody reads,
            // and the job would vanish without even a log line.
            jobs.execute(() -> process(engine, job));
        }).watch();
    }

    private static void process(WorkflowEngine engine, Job job) {
        // The engine fails jobs cleanly for step errors; these are for whatever it lets escape
        // (OutOfMemoryError, a bug in the engine itself).
        try {
            engine.run(job);
        } catch (Error err) {
            System.err.println(job + " stopped unexpectedly; its files are in "
                    + job.workDir().toAbsolutePath());
            // The worker thread's uncaught-exception handler prints the stack trace once; the
            // executor then starts a fresh thread for the next job.
            throw err;
        } catch (RuntimeException e) {
            System.err.println(job + " stopped unexpectedly; its files are in "
                    + job.workDir().toAbsolutePath());
            e.printStackTrace();
        }
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  run [workflow.yaml] [glob]   watch ./hotfolder and process files matching
                                               glob (default *.pdf, any case)
                  generate <count> <out.pdf> [badAddresses] [seed]
                                               write a fake statement print run, optionally
                                               with some bad addresses
                  generate-afp <count> <out.afp> [badAddresses] [seed] [--tle-names legacy]
                                               the same run as AFP (same seed, same
                                               statements); legacy names the TLEs ACCT_NO,
                                               CUST_NAME and ZIP_CODE
                  dump-afp <file.afp> [codePage]
                                               print an AFP file's structured fields and TLEs;
                                               code page defaults to IBM037""");
    }
}
