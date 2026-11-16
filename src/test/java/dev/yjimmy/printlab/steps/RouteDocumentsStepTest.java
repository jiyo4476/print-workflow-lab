package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RouteDocumentsStepTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Job job;

    @Before
    public void setUp() throws Exception {
        Path tmp = folder.getRoot().toPath();
        job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
    }

    private void addDocument(String zip, String pageCount) {
        int sequence = job.documents().size() + 1;
        job.documents().add(new Document(sequence, Path.of("doc-" + sequence + ".pdf"),
                Map.of("zip", zip, "pageCount", pageCount)));
    }

    private static Map<String, Object> route(String queue, Map<String, Object> when) {
        return Map.of("queue", queue, "when", when);
    }

    private static final Map<String, Object> BAD_ZIP =
            Map.of("property", "zip", "operator", "notMatches", "value", "\\d{5}");
    private static final Map<String, Object> THICK =
            Map.of("property", "pageCount", "operator", "greaterThan", "value", 6);

    @SafeVarargs
    private static RouteDocumentsStep step(String defaultQueue, Map<String, Object>... routes) {
        Map<String, Object> params = new HashMap<>();
        params.put("routes", List.of(routes));
        if (defaultQueue != null) {
            params.put("default", defaultQueue);
        }
        return new RouteDocumentsStep(params);
    }

    private List<String> queues() {
        return job.documents().stream().map(d -> d.property(RouteDocumentsStep.QUEUE)).toList();
    }

    @Test
    public void firstMatchingRouteWins() {
        addDocument("8020", "9"); // matches both routes
        addDocument("80202", "9"); // matches only the second

        step("standard", route("exceptions", BAD_ZIP), route("large-envelope", THICK))
                .execute(job);

        assertThat(queues()).containsExactly("exceptions", "large-envelope");
    }

    @Test
    public void unmatchedDocumentsGoToDefaultQueue() {
        addDocument("80202", "1");
        addDocument("8020", "1");

        step("standard", route("exceptions", BAD_ZIP)).execute(job);

        assertThat(queues()).containsExactly("standard", "exceptions");
    }

    @Test
    public void noDefaultAndNoMatchFailsTheJob() {
        addDocument("8020", "1");
        addDocument("80202", "1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> step(null, route("exceptions", BAD_ZIP)).execute(job));

        assertThat(e).hasMessage("Document 2 matches no route and there is no default queue");
    }

    @Test
    public void unsafeQueueNameFailsAtLoad() {
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> step("standard", route("../escape", BAD_ZIP))))
                .hasMessageContaining("Queue name '../escape'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> step("a/b", route("exceptions", BAD_ZIP))))
                .hasMessageContaining("Queue name 'a/b'");
    }

    @Test
    public void routesMustBeANonEmptyListOfQueueAndWhen() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteDocumentsStep(Map.of("default", "standard")));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteDocumentsStep(Map.of("routes", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteDocumentsStep(Map.of("routes", List.of("exceptions"))));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteDocumentsStep(Map.of("routes", List.of(Map.of("when", BAD_ZIP)))));
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new RouteDocumentsStep(Map.of("routes",
                        List.of(Map.of("queue", "exceptions"))))))
                .hasMessageContaining("needs a 'when'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new RouteDocumentsStep(Map.of("routes", List.of(
                        Map.of("queue", "exceptions", "when", BAD_ZIP, "wehn", BAD_ZIP))))))
                .hasMessageContaining("takes only 'queue' and 'when'");
    }
}
