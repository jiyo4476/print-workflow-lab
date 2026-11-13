package dev.yjimmy.printlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The real statements.yaml workflow on a generated run of 50 statements, 5 of them with bad
 * addresses. The run is processed once and every test checks a different part of the result.
 */
public class RoutingWorkflowTest {

    private static final int STATEMENTS = 50;
    private static final int BAD = 5;

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    private static Job job;
    private static Path jobOut;

    @BeforeClass
    public static void runWorkflow() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");
        new StatementPdfGenerator(7).generate(STATEMENTS, BAD, input);
        Path outputDir = tmp.resolve("output");

        job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.load("statements", outputDir), StepRegistry.withBuiltIns(),
                tmp.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);
        assertEquals(job.errorMessage(), JobState.COMPLETE, job.state());
        jobOut = outputDir.resolve(job.id());
    }

    private static Map<String, List<Document>> byQueue() {
        return job.documents().stream().collect(Collectors.groupingBy(d -> d.property("queue")));
    }

    @Test
    public void badAddressesLandInExceptions() {
        List<Document> exceptions = byQueue().get("exceptions");

        assertThat(exceptions).hasSize(BAD);
        assertThat(exceptions).allSatisfy(d -> assertThat(
                d.property("name").isBlank() || !d.property("zip").matches("\\d{5}")).isTrue());
        // Both kinds of bad address are in the run.
        assertThat(exceptions).anySatisfy(d -> assertThat(d.property("name")).isBlank());
        assertThat(exceptions).anySatisfy(d -> assertThat(d.property("zip")).hasSize(4));
    }

    @Test
    public void everyDocumentIsInExactlyOneQueue() {
        assertThat(job.documents()).hasSize(STATEMENTS);
        assertThat(job.documents()).allSatisfy(d -> assertThat(d.property("queue")).isNotBlank());
        assertThat(byQueue().values().stream().mapToInt(List::size).sum()).isEqualTo(STATEMENTS);
        assertThat(job.documents().stream().map(Document::sequence).distinct()).hasSize(STATEMENTS);
    }

    @Test
    public void outputHasOneFolderPerQueue() throws Exception {
        try (Stream<Path> entries = Files.list(jobOut)) {
            List<String> folders = entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(folders).isEqualTo(byQueue().keySet().stream().sorted().toList());
        }
        for (Map.Entry<String, List<Document>> queue : byQueue().entrySet()) {
            try (Stream<Path> files = Files.list(jobOut.resolve(queue.getKey()))) {
                assertThat(files.count()).as(queue.getKey()).isEqualTo(queue.getValue().size());
            }
        }
        assertThat(jobOut.resolve("standard/00001.pdf")).exists();
        assertThat(jobOut.resolve("exceptions/0000" + BAD + ".pdf")).exists();
    }

    @Test
    public void documentsCsvListsEveryDocumentWithItsQueue() throws Exception {
        List<String> lines = Files.readAllLines(jobOut.resolve("documents.csv"));
        List<String> header = csvRow(lines.get(0));
        int sequence = header.indexOf("sequence");
        int queue = header.indexOf("queue");
        int file = header.indexOf("file");
        assertThat(queue).as("queue column").isNotNegative();
        assertThat(lines).hasSize(STATEMENTS + 1);

        Map<Integer, String> csvQueues = lines.stream().skip(1)
                .map(RoutingWorkflowTest::csvRow)
                .peek(row -> assertThat(row.get(file)).startsWith(row.get(queue) + "/"))
                .collect(Collectors.toMap(
                        row -> Integer.parseInt(row.get(sequence)), row -> row.get(queue)));
        Map<Integer, String> jobQueues = job.documents().stream()
                .collect(Collectors.toMap(Document::sequence, d -> d.property("queue")));
        assertThat(csvQueues).isEqualTo(jobQueues);
    }

    /** Splits one CSV line, honouring double-quoted fields ("Denver, CO 80202"). */
    private static List<String> csvRow(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                field.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
