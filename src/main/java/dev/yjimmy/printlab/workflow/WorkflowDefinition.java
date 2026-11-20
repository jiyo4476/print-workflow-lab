package dev.yjimmy.printlab.workflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow as configuration: its steps, grouped into the fixed {@link Phase}s and run in order.
 * Loaded from YAML so workflows change without recompiling:
 * <pre>
 *   name: statements
 *   phases:
 *     receive: []                  # a phase may be empty, or left out
 *     prepare:
 *       - name: split-and-index
 *         type: SplitPdf
 *         params: { ... }
 *     print:
 *       - name: write-output
 *         type: WriteOutput
 * </pre>
 * Phases must appear in their order (receive, prepare, assemble, print, insert, complete), each
 * at most once. In code, {@link #steps()} is the flat list, each step carrying its phase.
 */
public record WorkflowDefinition(String name, String description, List<StepDefinition> steps) {

    /**
     * @param phase the phase the step belongs to
     * @param when optional condition on the job's properties; the step is skipped when it's false
     *     (see {@code rules.RuleParser} for the syntax), or {@code null} to always run
     */
    public record StepDefinition(String name, String type, Phase phase,
            Map<String, Object> params, Map<String, Object> when) {
        public StepDefinition {
            if (phase == null) {
                throw new IllegalArgumentException("Step '" + name + "' has no phase");
            }
            params = params == null ? Map.of() : params;
        }

        public StepDefinition(String name, String type, Phase phase, Map<String, Object> params) {
            this(name, type, phase, params, null);
        }

        /** A step in the {@link Phase#PREPARE} phase, for workflows built in code. */
        public StepDefinition(String name, String type, Map<String, Object> params) {
            this(name, type, Phase.PREPARE, params, null);
        }

        /** The same step in another phase. */
        public StepDefinition inPhase(Phase other) {
            return new StepDefinition(name, type, other, params, when);
        }
    }

    public WorkflowDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
        for (int i = 1; i < steps.size(); i++) {
            StepDefinition before = steps.get(i - 1);
            StepDefinition step = steps.get(i);
            if (step.phase().compareTo(before.phase()) < 0) {
                throw new IllegalArgumentException("Step '" + step.name() + "' ("
                        + step.phase().yamlName() + ") comes after step '" + before.name() + "' ("
                        + before.phase().yamlName() + "); phases run in the order "
                        + Phase.names());
            }
        }
    }

    /** The steps in {@code phase}, in order; empty if it has none. */
    public List<StepDefinition> steps(Phase phase) {
        return steps.stream().filter(s -> s.phase() == phase).toList();
    }

    /** A step as written in YAML, before its phase is known. */
    private record YamlStep(
            String name, String type, Map<String, Object> params, Map<String, Object> when) {}

    /** The file's shape. {@code steps} is only there to give old files a helpful error. */
    private record YamlWorkflow(String name, String description,
            LinkedHashMap<String, List<YamlStep>> phases, Object steps) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

    public static WorkflowDefinition load(Path file) throws IOException {
        YamlWorkflow yaml = YAML.readValue(file.toFile(), YamlWorkflow.class);
        if (yaml.steps() != null) {
            throw new IllegalArgumentException("Workflow '" + yaml.name() + "' lists 'steps:'"
                    + " directly; group them by phase under 'phases:' (" + Phase.names() + ")");
        }
        List<StepDefinition> steps = new ArrayList<>();
        Phase previous = null;
        Map<String, List<YamlStep>> phases = yaml.phases() == null ? Map.of() : yaml.phases();
        for (Map.Entry<String, List<YamlStep>> entry : phases.entrySet()) {
            Phase phase = Phase.fromYaml(entry.getKey()).orElseThrow(() ->
                    new IllegalArgumentException("Workflow '" + yaml.name() + "' has an unknown"
                            + " phase '" + entry.getKey() + "'; the phases are " + Phase.names()));
            if (previous != null && phase.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("Workflow '" + yaml.name() + "' lists phase '"
                        + phase.yamlName() + "' after '" + previous.yamlName() + "'; list them in"
                        + " the order " + Phase.names());
            }
            previous = phase;
            if (entry.getValue() != null) {
                for (YamlStep s : entry.getValue()) {
                    steps.add(new StepDefinition(s.name(), s.type(), phase, s.params(), s.when()));
                }
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Workflow '" + yaml.name() + "' has no steps");
        }
        return new WorkflowDefinition(yaml.name(), yaml.description(), steps);
    }
}
