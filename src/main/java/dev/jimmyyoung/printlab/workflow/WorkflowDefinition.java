package dev.jimmyyoung.printlab.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A workflow as configuration: an ordered list of steps, each naming a step type and its params.
 * Loaded from YAML so workflows change without recompiling.
 */
public record WorkflowDefinition(String name, String description, List<StepDefinition> steps) {

    public record StepDefinition(String name, String type, Map<String, Object> params) {
        public StepDefinition {
            params = params == null ? Map.of() : params;
        }
    }

    public WorkflowDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public static WorkflowDefinition load(Path file) throws IOException {
        WorkflowDefinition def = YAML.readValue(file.toFile(), WorkflowDefinition.class);
        if (def.steps().isEmpty()) {
            throw new IllegalArgumentException("Workflow '" + def.name() + "' has no steps");
        }
        return def;
    }
}
