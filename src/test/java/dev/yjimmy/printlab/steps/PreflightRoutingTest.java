package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Preflight results routed by RouteDocuments (PRD-11 AC-3): failing documents go to exceptions,
 * with their issues in their properties for documents.csv.
 */
public class PreflightRoutingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Job job;

    @Before
    public void setUp() throws Exception {
        Path tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    private void addDocument(byte[] bytes) throws Exception {
        int sequence = job.documents().size() + 1;
        Path file = folder.newFile("doc-" + sequence + ".pdf").toPath();
        Files.write(file, bytes);
        job.documents().add(new Document(sequence, file, Map.of("format", "pdf"), sequence));
    }

    private RouteDocumentsStep routeFailuresToExceptions() {
        Map<String, Object> failed = new HashMap<>();
        failed.put("queue", "exceptions");
        failed.put("when", Map.of("property", "preflight", "operator", "matches", "value", "fail"));
        return new RouteDocumentsStep(Map.of("routes", List.of(failed), "default", "standard"));
    }

    @Test
    public void failingDocumentsGoToExceptionsWithTheirIssues() throws Exception {
        addDocument(PreflightPdfStepTest.onePage("1.4", "[0 0 612 792]", "").build("/Root 1 0 R"));
        addDocument(PreflightPdfStepTest.onePage("1.4", "[0 0 595 842]", "").build("/Root 1 0 R"));
        addDocument(PreflightPdfStepTest.onePage("1.4", "[0 0 612 792]", "").build("/Root 1 0 R"));

        new PreflightPdfStep(Map.of()).execute(job);
        routeFailuresToExceptions().execute(job);

        assertThat(job.documents().get(0).property("queue")).isEqualTo("standard");
        assertThat(job.documents().get(1).property("queue")).isEqualTo("exceptions");
        assertThat(job.documents().get(1).property("preflightIssues"))
                .isEqualTo("page 1: page size 595.0 x 842.0 pt is not allowed (letter)");
        assertThat(job.documents().get(2).property("queue")).isEqualTo("standard");
    }

    @Test
    public void routedDocumentsKeepThePreflightPropertiesForTheIndex() throws Exception {
        addDocument(PreflightPdfStepTest.onePage("2.0", "[0 0 612 792]", "").build("/Root 1 0 R"));

        new PreflightPdfStep(Map.of()).execute(job);
        routeFailuresToExceptions().execute(job);

        Document document = job.documents().get(0);
        assertThat(document.property("preflight")).isEqualTo("fail");
        assertThat(document.property("preflightIssues")).isEqualTo("PDF version 2.0 is above the allowed 1.7");
        assertThat(document.property("queue")).isEqualTo("exceptions");
    }
}
