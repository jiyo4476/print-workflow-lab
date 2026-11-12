package dev.yjimmy.printlab.testsupport;

import static dev.yjimmy.printlab.testsupport.TestWorkflows.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestWorkflowsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void outputOverrideOnlyChangesWriteOutputStep() throws Exception {
        Path outputDir = folder.newFolder("out").toPath();
        WorkflowDefinition original =
                WorkflowDefinition.load(TestWorkflows.WORKFLOWS_DIR.resolve("statements.yaml"));

        WorkflowDefinition loaded = TestWorkflows.load("statements", outputDir);

        assertThat(loaded.name()).isEqualTo(original.name());
        assertThat(loaded.description()).isEqualTo(original.description());
        assertThat(loaded.steps()).extracting(StepDefinition::type).contains("WriteOutput");
        assertThat(loaded.steps()).hasSameSizeAs(original.steps());
        for (int i = 0; i < original.steps().size(); i++) {
            StepDefinition before = original.steps().get(i);
            StepDefinition after = loaded.steps().get(i);
            if (before.type().equals("WriteOutput")) {
                Map<String, Object> expected = new LinkedHashMap<>(before.params());
                expected.put("outputDir", outputDir.toString());
                assertThat(after.name()).isEqualTo(before.name());
                assertThat(after.type()).isEqualTo(before.type());
                assertThat(after.params()).as("WriteOutput params").isEqualTo(expected);
            } else {
                assertThat(after).as("step '%s' should be untouched", before.name())
                        .isEqualTo(before);
            }
        }
    }

    @Test
    public void loadNamesTheMissingWorkflow() {
        Path outputDir = folder.getRoot().toPath();

        assertThatThrownBy(() -> TestWorkflows.load("no-such-workflow", outputDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-workflow");
    }

    @Test
    public void ofKeepsStepsInTheOrderGiven() {
        WorkflowDefinition def = TestWorkflows.of(step("first", "A"), step("second", "B"));

        assertThat(def.steps()).extracting(StepDefinition::name).containsExactly("first", "second");
        assertThat(def.steps()).allSatisfy(s -> assertThat(s.params()).isEmpty());
    }
}
