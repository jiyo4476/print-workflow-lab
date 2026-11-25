package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.afp.AfpFraming;
import dev.yjimmy.printlab.testdata.AfpFramer;
import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * End to end: {@code statements-afp.yaml} on files written the way other tools write AFP (line
 * endings and padding, record descriptor words, blocks), and on files joined end to end.
 */
public class AfpFramedWorkflowTest {

    private static final int STATEMENTS = 40;
    private static final int BAD = 3;
    private static final List<String> INDEX = List.of(
            "accountNumber", "name", "zip", "pageCount", "queue");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void everyFramingGivesTheSameIndexAsTheStrictFile() throws Exception {
        byte[] strict = generate(7);
        Job expected = run(Files.write(tmp().resolve("strict.afp"), strict), "strict01", "strict");

        for (AfpFraming framing : List.of(AfpFraming.PADDED, AfpFraming.RDW, AfpFraming.BDW)) {
            String name = framing.name().toLowerCase(Locale.ROOT);
            Path file = Files.write(tmp().resolve(name + ".afp"), AfpFramer.frame(strict, framing));

            Job named = run(file, name + "01", name);
            Job auto = run(file, name + "02", "auto");

            assertEquals(name + " named", index(expected), index(named));
            assertEquals(name + " auto", index(expected), index(auto));
            assertEquals(name + " totalPages", expected.properties().get("totalPages"),
                    named.properties().get("totalPages"));
        }
    }

    @Test
    public void filesJoinedEndToEndGiveTheDocumentsOfBoth() throws Exception {
        byte[] first = generate(7);
        byte[] second = generate(8);
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);

        Job one = run(Files.write(tmp().resolve("one.afp"), first), "one01", "strict");
        Job two = run(Files.write(tmp().resolve("two.afp"), second), "two01", "strict");
        Job both = run(Files.write(tmp().resolve("both.afp"), joined), "both01", "strict");

        assertThat(both.documents()).hasSize(2 * STATEMENTS);
        assertEquals("documentCount", String.valueOf(2 * STATEMENTS),
                both.properties().get("documentCount"));
        assertEquals("totalPages", String.valueOf(
                Integer.parseInt(one.properties().get("totalPages"))
                        + Integer.parseInt(two.properties().get("totalPages"))),
                both.properties().get("totalPages"));
        // Presort puts both runs' statements in one zip order; compare as sets of rows.
        List<List<String>> expectedRows = new ArrayList<>(index(one));
        expectedRows.addAll(index(two));
        assertThat(index(both)).containsExactlyInAnyOrderElementsOf(expectedRows);
    }

    private byte[] generate(long seed) throws Exception {
        Path file = tmp().resolve("gen-" + seed + ".afp");
        new StatementAfpGenerator(seed).generate(STATEMENTS, BAD, file);
        return Files.readAllBytes(file);
    }

    private Path tmp() {
        return folder.getRoot().toPath();
    }

    /** Runs statements-afp.yaml with the SplitAfp step's {@code framing} set. */
    private Job run(Path input, String jobId, String framing) throws Exception {
        WorkflowDefinition def = TestWorkflows.load("statements-afp", tmp().resolve("output"));
        List<StepDefinition> steps = def.steps().stream().map(step -> {
            if (!"SplitAfp".equals(step.type())) {
                return step;
            }
            Map<String, Object> params = new LinkedHashMap<>(step.params());
            params.put("framing", framing);
            return new StepDefinition(step.name(), step.type(), step.phase(), params, step.when());
        }).toList();
        Job job = TestJobs.newJob(jobId, tmp(), input);
        new WorkflowEngine(new WorkflowDefinition(def.name(), def.description(), steps),
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
}
