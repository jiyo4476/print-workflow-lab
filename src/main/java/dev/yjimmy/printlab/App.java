package dev.yjimmy.printlab;

import dev.yjimmy.printlab.afp.AfpDump;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.input.InputDevice;
import dev.yjimmy.printlab.input.InputDeviceConfig;
import dev.yjimmy.printlab.input.InputDevices;
import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfDump;
import dev.yjimmy.printlab.pdf.PdfFormatException;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point.
 *
 * <pre>
 *   run                          start the input devices in config/input-devices.yaml: one hot
 *                                folder per device, each feeding its own workflow
 *   run --devices &lt;config.yaml&gt;  the same, from another devices file
 *   run &lt;workflow.yaml&gt; [glob]    one device, "default": watch ./hotfolder and run the workflow
 *                                on each file matching glob (default *.pdf, any case)
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
 *   dump-pdf [--xref] &lt;file.pdf&gt; [objectNumber]
 *                                print a PDF's version, trailer and pages (MediaBox, fonts,
 *                                content streams), or one object with its decoded stream;
 *                                --xref lists each cross-reference section (one per revision)
 *                                and what each incremental update changed;
 *                                exits 1 if the file can't be read or the object isn't there
 *   dump-content &lt;file.pdf&gt; &lt;page&gt;
 *                                print a page's content-stream operators, indented inside
 *                                q/Q, BT/ET and marked content, each with what it does;
 *                                exits 1 if there's no such page or its content can't be read
 *   dump-text &lt;file.pdf&gt; &lt;page&gt;
 *                                print each glyph on a page: its text, x and y (points from the
 *                                top-left of the crop box), width and font size; exits 1 if
 *                                there's no such page or its text can't be read (a CID font,
 *                                a rotated page)
 * </pre>
 */
public final class App {

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "run";
        switch (command) {
            case "run" -> run(devices(args));
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
            case "dump-pdf" -> {
                if (args.length > 1 && args[1].equals("--xref")) {
                    if (args.length != 3) {
                        usage();
                        return;
                    }
                    if (!dumpPdf(Path.of(args[2]), null, true)) {
                        System.exit(1);
                    }
                    return;
                }
                if (args.length < 2) {
                    usage();
                    return;
                }
                Integer objectNumber = null;
                if (args.length > 2) {
                    try {
                        objectNumber = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        System.err.println("dump-pdf: the object number must be a number, not '"
                                + args[2] + "'");
                        System.exit(1);
                    }
                }
                if (!dumpPdf(Path.of(args[1]), objectNumber, false)) {
                    System.exit(1);
                }
            }
            case "dump-content", "dump-text" -> {
                if (args.length != 3) {
                    usage();
                    return;
                }
                int page;
                try {
                    page = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    System.err.println(command + ": the page must be a number, not '" + args[2] + "'");
                    System.exit(1);
                    return;
                }
                if (!dumpPage(command, Path.of(args[1]), page)) {
                    System.exit(1);
                }
            }
            default -> usage();
        }
    }

    /**
     * {@code dump-content} or {@code dump-text} for one page. Returns false if the file can't be
     * read, the page isn't there or its content (or text) can't be read.
     */
    private static boolean dumpPage(String command, Path file, int page) {
        StringBuilder out = new StringBuilder();
        try {
            PdfDocumentReader pdf = PdfDocumentReader.open(file);
            boolean ok = command.equals("dump-text")
                    ? new PdfDump().dumpText(pdf, page, out)
                    : new PdfDump().dumpContent(pdf, page, out);
            System.out.print(out);
            return ok;
        } catch (IOException e) {
            System.err.println(command + ": can't read " + file + ": " + e);
            return false;
        } catch (PdfFormatException e) {
            System.out.print(out);
            System.err.println(command + ": " + file + ": " + e.getMessage());
            return false;
        }
    }

    /** Returns false if the file can't be read as a PDF or the object isn't in it. */
    private static boolean dumpPdf(Path file, Integer objectNumber, boolean xref) {
        StringBuilder out = new StringBuilder();
        PdfDocumentReader pdf;
        try {
            pdf = PdfDocumentReader.open(file);
        } catch (IOException e) {
            System.err.println("dump-pdf: can't read " + file + ": " + e);
            return false;
        } catch (PdfFormatException e) {
            System.err.println("dump-pdf: " + file + ": " + e.getMessage());
            return false;
        }
        try {
            boolean found = true;
            if (xref) {
                new PdfDump().dumpXref(pdf, out);
            } else if (objectNumber == null) {
                new PdfDump().dump(pdf, out);
            } else {
                found = new PdfDump().dumpObject(pdf, objectNumber, out);
            }
            System.out.print(out);
            return found;
        } catch (PdfFormatException e) {
            System.out.print(out);
            System.err.println("dump-pdf: " + file + ": " + e.getMessage());
            return false;
        }
    }

    /** The default devices file for {@code run} with no arguments. */
    static final Path DEVICES_FILE = Path.of("config/input-devices.yaml");

    /**
     * {@code run} → the devices file; {@code run --devices <file>} → that file;
     * {@code run <workflow.yaml> [glob]} → one device named "default" on ./hotfolder.
     */
    static List<InputDevice> devices(String[] args) throws Exception {
        if (args.length <= 1) { // "run", or no arguments at all
            return InputDeviceConfig.load(DEVICES_FILE);
        }
        if (args[1].equals("--devices")) {
            if (args.length < 3) {
                throw new IllegalArgumentException("run --devices needs a devices file");
            }
            return InputDeviceConfig.load(Path.of(args[2]));
        }
        return List.of(new InputDevice("default", Path.of("hotfolder"),
                args.length > 2 ? args[2] : "*.pdf", Path.of(args[1])));
    }

    private static void run(List<InputDevice> devices) throws Exception {
        InputDevices running = new InputDevices(devices, WorkflowDefinition::load,
                StepRegistry.withBuiltIns(), Path.of("work"), Path.of("error"),
                // Millisecond ticks: Windows clocks report 100 ns digits nobody needs to read.
                Clock.tickMillis(ZoneOffset.UTC), job -> { });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                running.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown"));
        running.start();
        for (InputDevice device : devices) {
            System.out.println("Input device '" + device.name() + "': " + device.glob() + " in "
                    + device.folder().toAbsolutePath() + " -> " + device.workflow());
        }
        // The devices run on their own threads; keep the program alive until it's stopped.
        Thread.currentThread().join();
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  run                          start the input devices in
                                               config/input-devices.yaml
                  run --devices <config.yaml>  the same, from another devices file
                  run <workflow.yaml> [glob]   one device: watch ./hotfolder and run the
                                               workflow on files matching glob (default *.pdf)
                  generate <count> <out.pdf> [badAddresses] [seed]
                                               write a fake statement print run, optionally
                                               with some bad addresses
                  generate-afp <count> <out.afp> [badAddresses] [seed] [--tle-names legacy]
                                               the same run as AFP (same seed, same
                                               statements); legacy names the TLEs ACCT_NO,
                                               CUST_NAME and ZIP_CODE
                  dump-afp <file.afp> [codePage]
                                               print an AFP file's structured fields and TLEs;
                                               code page defaults to IBM037
                  dump-pdf <file.pdf> [objectNumber]
                                               print a PDF's version, trailer and pages, or
                                               one object with its decoded stream
                  dump-pdf --xref <file.pdf>   list the cross-reference sections and what
                                               each incremental update changed
                  dump-content <file.pdf> <page>
                                               print a page's content-stream operators
                  dump-text <file.pdf> <page>  print each glyph on a page: text, x, y (from
                                               the top-left), width and font size""");
    }
}
