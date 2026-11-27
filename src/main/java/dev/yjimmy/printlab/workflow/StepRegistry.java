package dev.yjimmy.printlab.workflow;

import dev.yjimmy.printlab.steps.AddBarcodeStep;
import dev.yjimmy.printlab.steps.MapPropertiesStep;
import dev.yjimmy.printlab.steps.MarkReprintStep;
import dev.yjimmy.printlab.steps.PreflightPdfStep;
import dev.yjimmy.printlab.steps.RouteDocumentsStep;
import dev.yjimmy.printlab.steps.RunExternalProgramStep;
import dev.yjimmy.printlab.steps.SetJobPropsFromTextFileStep;
import dev.yjimmy.printlab.steps.SortDocumentsStep;
import dev.yjimmy.printlab.steps.SplitAfpStep;
import dev.yjimmy.printlab.steps.SplitPdfByDPartStep;
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
        registry.register("SetJobPropsFromTextFile", SetJobPropsFromTextFileStep::new);
        registry.register("SplitPdf", SplitPdfStep::new);
        registry.register("SplitPdfByDPart", SplitPdfByDPartStep::new);
        registry.register("SplitAfp", SplitAfpStep::new);
        registry.register("PreflightPdf", PreflightPdfStep::new);
        registry.register("MapProperties", MapPropertiesStep::new);
        registry.register("SortDocuments", SortDocumentsStep::new);
        registry.register("RouteDocuments", RouteDocumentsStep::new);
        registry.register("RunExternalProgram", RunExternalProgramStep::new);
        registry.register("AddBarcode", AddBarcodeStep::new);
        registry.register("WriteOutput", WriteOutputStep::new);
        registry.register("MarkReprint", MarkReprintStep::new);
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
        try {
            return factory.apply(def.params());
        } catch (IllegalArgumentException e) {
            // Steps validate their own params; this says which step in the YAML is wrong.
            throw new IllegalArgumentException(
                    "Step '" + def.name() + "' (" + def.type() + "): " + e.getMessage(), e);
        }
    }
}
