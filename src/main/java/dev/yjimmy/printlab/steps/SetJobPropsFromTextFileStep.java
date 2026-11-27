package dev.yjimmy.printlab.steps;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.Step;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets job properties from a text file that came with the input, the way ProcessDirector's
 * {@code SetJobPropsFromTextFile} step does at the start of most workflows. The file is a job
 * ticket: whoever submits the print file says how to treat it (customer, due date, mail class),
 * and later steps use those properties in {@code when:} conditions and routing.
 *
 * <pre>
 *   params:
 *     fileName: run.jobprops      # optional; default: the input's name + ".jobprops"
 *     required: false             # optional; true fails the job when the file is missing
 * </pre>
 *
 * <p>The file is looked for in the job's work folder, where {@code JobIntake} moves an input's
 * {@code <input>.jobprops} sidecar along with it. It's UTF-8, one {@code name=value} per line:
 * <pre>
 *   # Job ticket for run.pdf
 *   customer=ACME Bank
 *   mailClass=first
 *   presort=false
 * </pre>
 * Names and values are trimmed, and a value may contain {@code =}. Lines for {@link #PROTECTED}
 * properties are ignored, with a warning. Blank lines and lines starting with {@code #} are
 * ignored. A later line for the same name wins, and a line overrides any job
 * property already set. A line with no {@code =}, or an empty name, fails the job with its line
 * number. With no file, the step does nothing (unless {@code required}).
 */
public final class SetJobPropsFromTextFileStep implements Step {

    /**
     * Job properties the app sets at intake, which a ticket can't change: the input's file name
     * and the input device it came through. A ticket line for one is ignored, with a warning.
     */
    public static final Set<String> PROTECTED = Set.of("inputFileName", "inputDevice");

    /** Appended to the input file's name to find its job ticket. */
    public static final String EXTENSION = ".jobprops";

    private static final Logger log = LoggerFactory.getLogger(SetJobPropsFromTextFileStep.class);

    /** The job ticket that goes with {@code input}: {@code <input>.jobprops} next to it. */
    public static Path ticketFor(Path input) {
        return input.resolveSibling(input.getFileName() + EXTENSION);
    }

    private final String fileName;
    private final boolean required;

    public SetJobPropsFromTextFileStep(Map<String, Object> rawParams) {
        Params params = new Params(rawParams);
        this.fileName = params.string("fileName", null);
        if (fileName != null && !isPlainFileName(fileName)) {
            throw new IllegalArgumentException("Step param 'fileName' must be a file name in the"
                    + " job's work folder, not a path, got '" + fileName + "'");
        }
        String flag = params.string("required", "false");
        if (!flag.equals("true") && !flag.equals("false")) {
            throw new IllegalArgumentException("Step param 'required' must be true or false, got '"
                    + flag + "'");
        }
        this.required = flag.equals("true");
    }

    /**
     * True for a name that resolves inside the work folder: one path element, no root. A root
     * check is needed on Windows, where {@code C:ticket.txt} is one element relative to drive C.
     */
    private static boolean isPlainFileName(String name) {
        if (name.isBlank() || name.equals("..") || name.equals(".")) {
            return false;
        }
        Path path = Path.of(name);
        return path.getNameCount() == 1 && path.getRoot() == null;
    }

    /** Reads the ticket from the work folder, which the claim left it in. */
    @Override
    public boolean resumable() {
        return true;
    }

    @Override
    public void execute(Job job) throws IOException {
        String name = fileName != null ? fileName : job.inputFile().getFileName() + EXTENSION;
        Path file = job.workDir().resolve(name);
        if (!Files.isRegularFile(file)) {
            if (required) {
                throw new IllegalStateException("No job properties file '" + name + "' in "
                        + job.workDir() + "; put it in the hot folder before the input file");
            }
            log.info("{} has no job properties file '{}'", job.id(), name);
            return;
        }
        Map<String, String> read = parse(Files.readAllLines(file, StandardCharsets.UTF_8), name);
        for (String key : PROTECTED) {
            if (read.remove(key) != null) {
                log.warn("{}: {} tries to set '{}', which the app sets itself; ignored",
                        job.id(), name, key);
            }
        }
        job.properties().putAll(read);
        log.info("{} set {} job properties from {}", job.id(), read.size(), name);
    }

    /** Parses {@code name=value} lines; {@code source} names the file in error messages. */
    static Map<String, String> parse(List<String> lines, String source) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (i == 0 && line.startsWith("﻿")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalStateException(source + " line " + (i + 1)
                        + " isn't name=value: '" + line + "'");
            }
            String key = line.substring(0, eq).strip();
            if (key.isEmpty()) {
                throw new IllegalStateException(source + " line " + (i + 1)
                        + " has no property name before '='");
            }
            properties.remove(key);
            properties.put(key, line.substring(eq + 1).strip());
        }
        return properties;
    }
}
