package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator.TleNames;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.AfpBytes;
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
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** End to end: the real AFP workflows on generated AFP, compared with the PDF workflow. */
public class AfpStatementWorkflowTest {

    private static final long SEED = 7;
    private static final int STATEMENTS = 30;
    private static final int BAD = 4;
    /** The index both formats carry, in output order. */
    private static final List<String> INDEX = List.of(
            "accountNumber", "name", "zip", "pageCount", "queue");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void afpAndPdfWorkflowsProduceSameIndexForSameSeed() throws Exception {
        Path pdf = tmp().resolve("run.pdf");
        new StatementPdfGenerator(SEED).generate(STATEMENTS, BAD, pdf);
        Path afp = tmp().resolve("run.afp");
        new StatementAfpGenerator(SEED).generate(STATEMENTS, BAD, afp);

        Job pdfJob = run("statements", "pdfjob01", pdf);
        Job afpJob = run("statements-afp", "afpjob01", afp);

        assertThat(afpJob.documents()).hasSize(STATEMENTS);
        assertEquals("index in zip order", index(pdfJob), index(afpJob));
        assertEquals("totalPages", pdfJob.properties().get("totalPages"),
                afpJob.properties().get("totalPages"));
        assertThat(afpJob.documents()).filteredOn(d -> "exceptions".equals(d.property("queue")))
                .hasSize(BAD);
        assertThat(afpJob.documents()).allSatisfy(d -> assertThat(d.property("format"))
                .isEqualTo("afp"));
        Path out = tmp().resolve("output").resolve("afpjob01");
        assertThat(out.resolve("standard/00001.afp")).exists();
        assertThat(Files.readAllLines(out.resolve("documents.csv"))).hasSize(STATEMENTS + 1);
    }

    @Test
    public void missingTleRoutesToExceptions() throws Exception {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        statement(afp, "S1", "1111111111", "AVERY", "80202");
        statement(afp, "S2", "2222222222", "BLAKE", null);
        statement(afp, "S3", "3333333333", "CASEY", "10001");
        afp.end(FieldType.EDT);
        Path input = Files.write(tmp().resolve("missing-zip.afp"), afp.bytes());

        Job job = run("statements-afp", "afpjob02", input);

        Map<String, String> queues = new LinkedHashMap<>();
        job.documents().forEach(d -> queues.put(d.property("accountNumber"), d.property("queue")));
        assertEquals(Map.of("1111111111", "standard", "2222222222", "exceptions",
                "3333333333", "standard"), queues);
        Document missing = job.documents().stream()
                .filter(d -> "2222222222".equals(d.property("accountNumber"))).findFirst()
                .orElseThrow();
        assertEquals("indexError", "missing: zip", missing.property("indexError"));
    }

    @Test
    public void legacyTleNamesGiveSameIndexAfterMapping() throws Exception {
        Path plain = tmp().resolve("plain.afp");
        new StatementAfpGenerator(SEED).generate(STATEMENTS, BAD, plain);
        Path legacy = tmp().resolve("legacy.afp");
        new StatementAfpGenerator(SEED).generate(STATEMENTS, BAD, TleNames.LEGACY, legacy);

        Job plainJob = run("statements-afp", "afpjob03", plain);
        Job legacyJob = run("statements-afp-legacy", "afpjob04", legacy);

        assertThat(legacyJob.documents()).hasSize(STATEMENTS);
        assertEquals("index in zip order", index(plainJob), index(legacyJob));
        assertThat(legacyJob.documents()).allSatisfy(d -> assertThat(d.properties())
                .doesNotContainKeys("ACCT_NO", "CUST_NAME", "ZIP_CODE"));
    }

    private Path tmp() {
        return folder.getRoot().toPath();
    }

    private Job run(String workflow, String jobId, Path input) throws Exception {
        Job job = TestJobs.newJob(jobId, tmp(), input);
        new WorkflowEngine(TestWorkflows.load(workflow, tmp().resolve("output")),
                StepRegistry.withBuiltIns(), tmp().resolve("error"), TickingClock.oneSecondTicks())
                .run(job);
        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        return job;
    }

    private static List<List<String>> index(Job job) {
        return job.documents().stream()
                .map(d -> INDEX.stream().map(d::property).toList())
                .toList();
    }

    private static void statement(AfpBytes afp, String group, String account, String name,
            String zip) {
        afp.begin(FieldType.BNG, group).tle("accountNumber", account).tle("name", name);
        if (zip != null) {
            afp.tle("zip", zip);
        }
        afp.begin(FieldType.BPG, group + "P1").end(FieldType.EPG).end(FieldType.ENG);
    }
}
