package dev.jimmyyoung.printlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jimmyyoung.printlab.testdata.StatementPdfGenerator;
import dev.jimmyyoung.printlab.workflow.Document;
import dev.jimmyyoung.printlab.workflow.Job;
import dev.jimmyyoung.printlab.workflow.JobState;
import dev.jimmyyoung.printlab.workflow.StepRegistry;
import dev.jimmyyoung.printlab.workflow.WorkflowDefinition;
import dev.jimmyyoung.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end: generated print run -> the real statements.yaml workflow -> sorted output. */
class StatementWorkflowTest {

    @TempDir
    Path tmp;

    @Test
    void splitsIndexesAndSortsAStatementRun() throws Exception {
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(42).generate(25, input);

        WorkflowDefinition sample = WorkflowDefinition.load(Path.of("workflows/statements.yaml"));
        Path outputDir = tmp.resolve("output");
        WorkflowDefinition def = withOutputDir(sample, outputDir);

        Job job = new Job("test0001", input, tmp);
        new WorkflowEngine(def, StepRegistry.withBuiltIns()).run(job);

        assertEquals(JobState.COMPLETE, job.state(), job.errorMessage());
        List<Document> docs = job.documents();
        assertEquals(25, docs.size());

        int pages = 0;
        for (Document d : docs) {
            assertTrue(d.property("accountNumber").matches("\\d{10}"), d.properties().toString());
            assertTrue(d.property("zip").matches("\\d{5}"), d.properties().toString());
            assertTrue(d.property("cityStateZip").endsWith(d.property("zip")));
            assertTrue(!d.property("name").isBlank());
            pages += Integer.parseInt(d.property("pageCount"));
        }
        assertEquals(Integer.parseInt(job.properties().get("totalPages")), pages);

        for (int i = 1; i < docs.size(); i++) {
            assertTrue(docs.get(i - 1).property("zip").compareTo(docs.get(i).property("zip")) <= 0,
                    "documents should be presorted by zip");
        }

        Path jobOut = outputDir.resolve("test0001");
        List<String> csv = Files.readAllLines(jobOut.resolve("documents.csv"));
        assertEquals(26, csv.size());
        assertTrue(Files.exists(jobOut.resolve("00025.pdf")));
    }

    private static WorkflowDefinition withOutputDir(WorkflowDefinition def, Path outputDir) {
        return new WorkflowDefinition(def.name(), def.description(), def.steps().stream()
                .map(s -> s.type().equals("WriteOutput")
                        ? new WorkflowDefinition.StepDefinition(s.name(), s.type(),
                                Map.of("outputDir", outputDir.toString()))
                        : s)
                .toList());
    }
}
