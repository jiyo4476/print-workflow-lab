package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.afp.AfpReader;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.StructuredField;
import dev.yjimmy.printlab.afp.Tle;
import dev.yjimmy.printlab.afp.TleDecoder;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The real {@code workflows/reprint.yaml}, run on documents from an earlier run (PRD-15). */
public class ReprintWorkflowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void reprintedAfpHasReprintTleAndMarkOnEveryPage() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path output = tmp.resolve("output");
        StepRegistry registry = StepRegistry.withBuiltIns();
        registry.register("WriteOutput",
                params -> new WriteOutputStep(Map.of("outputDir", output.toString())));
        WorkflowDefinition workflow = WorkflowDefinition.load(
                Path.of("workflows", "reprint.yaml"));
        Job job = TestJobs.newJob(tmp, tmp.resolve("reprint.request"));
        job.properties().put("parentJobId", "job-parent");
        Path afp = tmp.resolve("a.afp");
        try (java.io.OutputStream out = Files.newOutputStream(afp)) {
            StatementAfpGenerator.write(new StatementDataGenerator(5).generate(2, 0), out);
        }
        job.documents().add(new Document(1, afp, Map.of("format", "afp", "queue", "statements"),
                1));

        new WorkflowEngine(workflow, registry, tmp.resolve("error"),
                TickingClock.oneSecondTicks()).run(job);

        assertThat(job.state()).isEqualTo(JobState.COMPLETE);
        Path written = output.resolve(job.id()).resolve("statements").resolve("00001.afp");
        List<StructuredField> fields = new ArrayList<>();
        try (AfpReader reader = new AfpReader(Files.newInputStream(written))) {
            reader.forEachRemaining(fields::add);
        }
        long pages = fields.stream().filter(f -> f.type() == FieldType.BPG).count();
        byte[] mark = Ebcdic.encodeStrict("REPRINT", Ebcdic.IBM037);
        assertThat(fields.stream().filter(f -> f.type() == FieldType.PTX)
                .filter(f -> contains(f.data(), mark)).count()).isEqualTo(pages);
        assertThat(fields.stream().filter(f -> f.type() == FieldType.TLE)
                .map(TleDecoder::decode).filter(t -> t.name().equals("Reprint"))
                .map(Tle::value)).containsExactly("job-parent");
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (java.util.Arrays.equals(haystack, i, i + needle.length, needle, 0,
                    needle.length)) {
                return true;
            }
        }
        return false;
    }

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
