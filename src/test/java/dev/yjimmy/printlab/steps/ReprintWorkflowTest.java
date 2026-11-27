package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The real {@code workflows/reprint.yaml}, run on documents from an earlier run (PRD-15). */
public class ReprintWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void reprintedPdfHasReprintMarkerOnEveryPage() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path output = tmp.resolve("output");
        StepRegistry registry = StepRegistry.withBuiltIns();
        registry.register("WriteOutput",
                params -> new WriteOutputStep(Map.of("outputDir", output.toString())));
        WorkflowDefinition workflow = WorkflowDefinition.load(
                Path.of("workflows", "reprint.yaml"));
        Job job = TestJobs.newJob(tmp, tmp.resolve("reprint.request"));
        Map<String, String> props = Map.of("format", "pdf", "queue", "statements");
        job.documents().add(new Document(1,
                MarkReprintStepTest.pdf(tmp.resolve("a.pdf"), 3), props, 1));
        job.documents().add(new Document(2,
                MarkReprintStepTest.pdf(tmp.resolve("b.pdf"), 2), props, 2));

        new WorkflowEngine(workflow, registry, tmp.resolve("error"),
                TickingClock.oneSecondTicks()).run(job);

        assertThat(job.state()).isEqualTo(JobState.COMPLETE);
        Path statements = output.resolve(job.id()).resolve("statements");
        for (String file : new String[] {"00001.pdf", "00002.pdf"}) {
            for (String page : MarkReprintStepTest.pageTexts(statements.resolve(file))) {
                assertThat(page).as(file).containsOnlyOnce("REPRINT");
                assertThat(page).as(file).contains("statement body");
            }
        }
    }
}
