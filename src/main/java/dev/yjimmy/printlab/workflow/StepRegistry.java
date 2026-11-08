package dev.yjimmy.printlab.workflow;

import dev.yjimmy.printlab.steps.RunExternalProgramStep;
import dev.yjimmy.printlab.steps.SortDocumentsStep;
import dev.yjimmy.printlab.steps.SplitPdfStep;
import dev.yjimmy.printlab.steps.WriteOutputStep;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Maps a step {@code type} in workflow YAML to the code that builds it — the step templates. */
public final class StepRegistry {

    private final Map<String, Function<Map<String, Object>, Step>> factories = new HashMap<>();

    public static StepRegistry withBuiltIns() {
        StepRegistry registry = new StepRegistry();
        registry.register("SplitPdf", SplitPdfStep::new);
        registry.register("SortDocuments", SortDocumentsStep::new);
        registry.register("RunExternalProgram", RunExternalProgramStep::new);
        registry.register("WriteOutput", WriteOutputStep::new);
        return registry;
    }

    public void register(String type, Function<Map<String, Object>, Step> factory) {
        factories.put(type, factory);
    }

    public Step create(WorkflowDefinition.StepDefinition def) {
        Function<Map<String, Object>, Step> factory = factories.get(def.type());
        if (factory == null) {
            throw new IllegalArgumentException(
                    "Unknown step type '" + def.type() + "' in step '" + def.name() + "'");
        }
        return factory.apply(def.params());
    }
}
