package dev.yjimmy.printlab.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The phases every workflow moves a job through, in this order, as in Ricoh ProcessDirector.
 * Each step belongs to one phase, and a phase may have no steps. The job's current phase is what
 * an operator watches: it says how far along the job is without knowing the workflow's steps.
 */
public enum Phase {
    /** Accept the input: set job properties, detect what kind of data it is. */
    RECEIVE,
    /** Turn the input into documents: split, index, map properties, route, sort. */
    PREPARE,
    /** Build what will be printed from the documents (one print file per queue, say). */
    ASSEMBLE,
    /** Send the job to its printer; here, write the print files to the output folder. */
    PRINT,
    /** Inserting: reconcile what the inserter finished against what was printed. */
    INSERT,
    /** Finish up: notify, retain, clean up. A finished job is in this phase. */
    COMPLETE;

    /** The name in workflow YAML: {@code receive}, {@code prepare}, ... */
    public String yamlName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** For Jackson: {@code prepare} or {@code PREPARE} (job.json). */
    @JsonCreator
    static Phase parse(String name) {
        return fromYaml(name).orElseThrow(() -> new IllegalArgumentException(
                "unknown phase '" + name + "'; the phases are " + names()));
    }

    /** Looks up a YAML phase name, ignoring case. */
    public static Optional<Phase> fromYaml(String name) {
        return Arrays.stream(values()).filter(p -> p.yamlName().equalsIgnoreCase(name)).findFirst();
    }

    /** {@code receive, prepare, ...}, for error messages. */
    static String names() {
        return Arrays.stream(values()).map(Phase::yamlName).collect(Collectors.joining(", "));
    }
}
