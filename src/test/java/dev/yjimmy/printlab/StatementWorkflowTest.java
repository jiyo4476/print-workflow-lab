package dev.yjimmy.printlab;

import java.util.Comparator;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testdata.Statement;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.JobSummary;
import dev.yjimmy.printlab.workflow.JobSummaryWriter;
import dev.yjimmy.printlab.workflow.Phase;
import dev.yjimmy.printlab.workflow.StepOutcome;
import dev.yjimmy.printlab.workflow.StepRecord;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** End to end: generated print run -> the real statements.yaml workflow -> sorted output. */
public class StatementWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void splitsIndexesAndSortsAStatementRun() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(42).generate(25, input);
        Path outputDir = tmp.resolve("output");

        Job job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.load("statements", outputDir), StepRegistry.withBuiltIns(),
                tmp.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        List<Document> docs = job.documents();
        assertEquals(25, docs.size());

        int pages = 0;
        for (Document d : docs) {
            assertTrue(d.properties().toString(), d.property("accountNumber").matches("\\d{10}"));
            assertTrue(d.properties().toString(), d.property("zip").matches("\\d{5}"));
            assertTrue(d.property("cityStateZip").endsWith(d.property("zip")));
            assertTrue(!d.property("name").isBlank());
            pages += Integer.parseInt(d.property("pageCount"));
        }
        assertEquals(Integer.parseInt(job.properties().get("totalPages")), pages);

        for (int i = 1; i < docs.size(); i++) {
            assertTrue("documents should be presorted by zip",
                    docs.get(i - 1).property("zip").compareTo(docs.get(i).property("zip")) <= 0);
        }

        Path jobOut = outputDir.resolve(job.id());
        List<String> csv = Files.readAllLines(jobOut.resolve("documents.csv"));
        assertEquals(26, csv.size());
        // No bad addresses and no statement over 6 pages, so everything is in the standard queue.
        assertTrue(docs.stream().allMatch(d -> "standard".equals(d.property("queue"))));
        assertTrue(Files.exists(jobOut.resolve("standard/00025.pdf")));

        JobSummary summary = JobSummaryWriter.read(job.workDir().resolve(JobSummaryWriter.FILE_NAME));
        assertEquals(JobState.COMPLETE, summary.state());
        assertEquals(25, summary.documentCount());
        assertEquals(List.of("set-job-props", "split-and-index", "route", "presort",
                        "inserter-barcode", "write-output"),
                summary.steps().stream().map(StepRecord::name).toList());
        assertEquals("each step's phase", List.of(Phase.RECEIVE, Phase.PREPARE, Phase.PREPARE,
                Phase.PREPARE, Phase.PRINT, Phase.PRINT),
                summary.steps().stream().map(StepRecord::phase).toList());
        assertEquals("finished job's phase", Phase.COMPLETE, summary.phase());
    }

    @Test
    public void jobTicketSetsJobPropertiesAndCanTurnOffPresort() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(42).generate(10, input);
        Job job = TestJobs.newJob(tmp, input);
        Files.writeString(job.workDir().resolve("run.pdf.jobprops"),
                """
                # ticket for run.pdf
                customer=ACME Bank
                presort=false
                """);

        new WorkflowEngine(TestWorkflows.load("statements", tmp.resolve("output")),
                StepRegistry.withBuiltIns(), tmp.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        assertEquals("customer", "ACME Bank", job.properties().get("customer"));
        assertEquals("presort skipped", StepOutcome.SKIPPED, job.stepRecords().stream()
                .filter(r -> r.name().equals("presort")).findFirst().orElseThrow().outcome());
        assertEquals("documents stay in input order",
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                job.documents().stream().map(Document::sequence).toList());
    }

    @Test
    public void presortTicketValueIgnoresCaseAndAcceptsNoAndOff() throws Exception {
        Path tmp = folder.getRoot().toPath();
        WorkflowEngine engine = new WorkflowEngine(
                TestWorkflows.load("statements", tmp.resolve("output")),
                StepRegistry.withBuiltIns(), tmp.resolve("error"), TickingClock.oneSecondTicks());
        Map<String, StepOutcome> expected = new LinkedHashMap<>();
        expected.put("False", StepOutcome.SKIPPED);
        expected.put("OFF", StepOutcome.SKIPPED);
        expected.put("no", StepOutcome.SKIPPED);
        expected.put("true", StepOutcome.COMPLETED);
        int n = 0;
        for (Map.Entry<String, StepOutcome> e : expected.entrySet()) {
            String id = "job0000" + (++n);
            Path input = Files.createDirectories(tmp.resolve(id)).resolve("run.pdf");
            new StatementPdfGenerator(42).generate(3, input);
            Job job = TestJobs.newJob(id, tmp, input);
            Files.writeString(job.workDir().resolve("run.pdf.jobprops"), "presort=" + e.getKey());

            engine.run(job);

            assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
            assertEquals("presort=" + e.getKey(), e.getValue(), job.stepRecords().stream()
                    .filter(r -> r.name().equals("presort")).findFirst().orElseThrow().outcome());
        }
    }

    @Test
    public void twoStatementsInARowForTheSameAccountStaySeparate() throws Exception {
        Path tmp = folder.getRoot().toPath();
        List<Statement> drawn = new StatementDataGenerator(42).generate(3, 0);
        Statement first = drawn.get(0);
        // A second statement for the same customer (a corrected one, say), with other pages.
        Statement again = new Statement(first.accountNumber(), first.name(), first.street(),
                first.city(), first.state(), first.zip(), drawn.get(1).pages());
        List<Statement> run = List.of(first, again, drawn.get(2));
        Path input = tmp.resolve("same-account.pdf");
        StatementPdfGenerator.write(run, input);

        Job job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.load("statements", tmp.resolve("output")),
                StepRegistry.withBuiltIns(), tmp.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        List<Document> docs = job.documents().stream()
                .sorted(Comparator.comparingInt(Document::sequence)).toList();
        assertEquals("documents", 3, docs.size());
        for (int i = 0; i < run.size(); i++) {
            assertEquals("document " + (i + 1) + " account", run.get(i).accountNumber(),
                    docs.get(i).property("accountNumber"));
            assertEquals("document " + (i + 1) + " pages",
                    String.valueOf(run.get(i).pageCount()), docs.get(i).property("pageCount"));
        }
    }
}
