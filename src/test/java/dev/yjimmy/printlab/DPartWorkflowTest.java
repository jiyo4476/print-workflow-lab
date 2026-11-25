package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** End to end: {@code statements-dpart.yaml} on a file whose DPM entries are incomplete. */
public class DPartWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void missingRequiredDpmKeyMarksIndexErrorAndRoutesToExceptions() throws Exception {
        // Three one-page statements: complete; no /Zip; a four-digit zip.
        Path input = Files.write(folder.getRoot().toPath().resolve("run.pdf"), new PdfBytes("1.7")
                .object(1, "<< /Type /Catalog /Pages 2 0 R /DPartRoot 10 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .object(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .object(5, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
                .object(10, "<< /Type /DPartRoot /DPartRootNode 11 0 R /RecordLevel 1 >>")
                .object(11, "<< /Type /DPart /Parent 10 0 R /DParts [[12 0 R 13 0 R 14 0 R]] >>")
                .object(12, "<< /Start 3 0 R /End 3 0 R /DPM << /AccountNumber (1111111111)"
                        + " /Name (AVERY) /Zip (80202) >> >>")
                .object(13, "<< /Start 4 0 R /End 4 0 R /DPM << /AccountNumber (2222222222)"
                        + " /Name (BLAKE) >> >>")
                .object(14, "<< /Start 5 0 R /End 5 0 R /DPM << /AccountNumber (3333333333)"
                        + " /Name (CASEY) /Zip (1234) >> >>")
                .build("/Root 1 0 R"));
        Job job = TestJobs.newJob("dpartjob01", folder.getRoot().toPath(), input);

        new WorkflowEngine(TestWorkflows.load("statements-dpart",
                folder.getRoot().toPath().resolve("output")), StepRegistry.withBuiltIns(),
                folder.getRoot().toPath().resolve("error"), TickingClock.oneSecondTicks()).run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        Map<String, String> queues = new LinkedHashMap<>();
        job.documents().forEach(d -> queues.put(d.property("accountNumber"), d.property("queue")));
        assertEquals(Map.of("1111111111", "standard", "2222222222", "exceptions",
                "3333333333", "exceptions"), queues);
        Document missingZip = job.documents().stream()
                .filter(d -> "2222222222".equals(d.property("accountNumber"))).findFirst()
                .orElseThrow();
        assertEquals("missing: zip", missingZip.property("indexError"));
        Document shortZip = job.documents().stream()
                .filter(d -> "3333333333".equals(d.property("accountNumber"))).findFirst()
                .orElseThrow();
        assertThat(shortZip.property("indexError")).isNull();
        Path out = folder.getRoot().toPath().resolve("output").resolve("dpartjob01");
        assertThat(out.resolve("exceptions")).isDirectoryContaining("glob:**.pdf");
        assertThat(Files.readAllLines(out.resolve("documents.csv"))).hasSize(4);
    }
}
