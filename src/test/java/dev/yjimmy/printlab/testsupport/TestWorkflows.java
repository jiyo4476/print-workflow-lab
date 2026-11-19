package dev.yjimmy.printlab.testsupport;

import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds workflows in code, or loads the real YAML ones with output sent to a temp folder. */
public final class TestWorkflows {

    /** Relative to the project root, which is the working directory when Maven runs tests. */
    public static final Path WORKFLOWS_DIR = Path.of("workflows");

    private TestWorkflows() {}

    public static WorkflowDefinition of(StepDefinition... steps) {
        return new WorkflowDefinition("test", null, List.of(steps));
    }

    public static StepDefinition step(String name, String type) {
        return new StepDefinition(name, type, Map.of());
    }

    /**
     * Loads {@code workflows/<name>.yaml} and points every {@code WriteOutput} step's
     * {@code outputDir} at {@code outputDir}. Every other step, and every other param, is unchanged.
     */
    public static WorkflowDefinition load(String name, Path outputDir) throws IOException {
        Path file = WORKFLOWS_DIR.resolve(name + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    "No workflow '" + name + "' at " + file.toAbsolutePath());
        }
        WorkflowDefinition def = WorkflowDefinition.load(file);
        return new WorkflowDefinition(def.name(), def.description(), def.steps().stream()
                .map(s -> redirectOutput(s, outputDir))
                .toList());
    }

    private static StepDefinition redirectOutput(StepDefinition s, Path outputDir) {
        if (!"WriteOutput".equals(s.type())) {
            return s;
        }
        Map<String, Object> params = new LinkedHashMap<>(s.params());
        params.put("outputDir", outputDir.toString());
        return new StepDefinition(s.name(), s.type(), s.phase(), params, s.when());
    }
}
