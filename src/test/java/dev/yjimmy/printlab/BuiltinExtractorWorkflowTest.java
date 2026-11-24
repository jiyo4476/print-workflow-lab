package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The real statements.yaml, run once with each extractor: the same documents.csv. */
public class BuiltinExtractorWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Runs statements.yaml on {@code input} with {@code extractor: <extractor>}; returns documents.csv. */
    private String run(Path input, String extractor) throws Exception {
        Path tmp = folder.newFolder(extractor).toPath();
        Path outputDir = tmp.resolve("output");
        WorkflowDefinition loaded = TestWorkflows.load("statements", outputDir);
        WorkflowDefinition workflow = new WorkflowDefinition(loaded.name(), loaded.description(), loaded.steps().stream()
                .map(s -> s.type().equals("SplitPdf") ? withExtractor(s, extractor) : s)
                .toList());

        Job job = TestJobs.newJob(tmp, Files.copy(input, tmp.resolve("run.pdf")));
        new WorkflowEngine(workflow, StepRegistry.withBuiltIns(), tmp.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(extractor + ": " + job.errorMessage(), JobState.COMPLETE, job.state());
        return Files.readString(outputDir.resolve(job.id()).resolve("documents.csv"));
    }

    private static StepDefinition withExtractor(StepDefinition step, String extractor) {
        Map<String, Object> params = new LinkedHashMap<>(step.params());
        params.put("extractor", extractor);
        return new StepDefinition(step.name(), step.type(), step.phase(), params, step.when());
    }

    @Test
    public void documentsCsvIsIdenticalWithEitherExtractor() throws Exception {
        Path input = folder.getRoot().toPath().resolve("statements.pdf");
        // Bad addresses too, so the exceptions queue is in the comparison.
        new StatementPdfGenerator(7).generate(40, 4, input);

        String pdfBox = run(input, "pdfbox");
        String builtin = run(input, "builtin");

        assertThat(pdfBox.lines().count()).as("header and 40 documents").isEqualTo(41);
        assertEquals("documents.csv", pdfBox, builtin);
    }
}
