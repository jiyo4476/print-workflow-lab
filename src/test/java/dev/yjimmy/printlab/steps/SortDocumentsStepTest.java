package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SortDocumentsStepTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void sequenceIsMailingOrderAfterPresort() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Job job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
        job.documents().add(new Document(1, tmp.resolve("1.pdf"), Map.of("zip", "80301")));
        job.documents().add(new Document(2, tmp.resolve("2.pdf"), Map.of("zip", "10001")));
        job.documents().add(new Document(3, tmp.resolve("3.pdf"), Map.of("zip", "60601")));

        new SortDocumentsStep(Map.of("by", "zip")).execute(job);

        assertThat(job.documents().stream().map(d -> d.property("zip")).toList())
                .containsExactly("10001", "60601", "80301");
        assertThat(job.documents().stream().map(Document::sequence).toList())
                .containsExactly(1, 2, 3);
        assertThat(job.documents().stream().map(Document::file).toList())
                .containsExactly(tmp.resolve("2.pdf"), tmp.resolve("3.pdf"), tmp.resolve("1.pdf"));
    }

    @Test
    public void documentsWithoutTheSortPropertyGoLast() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Job job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
        job.documents().add(new Document(1, tmp.resolve("a.pdf"), Map.of()));
        job.documents().add(new Document(2, tmp.resolve("b.pdf"), Map.of("zip", "10000")));

        new SortDocumentsStep(Map.of("by", "zip")).execute(job);

        assertThat(job.documents().stream().map(d -> d.file().getFileName().toString()).toList())
                .containsExactly("b.pdf", "a.pdf");
    }

    @Test
    public void tiesKeepInputOrderAndGetTheirNewNumbers() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Job job = TestJobs.newJob(tmp, tmp.resolve("in.pdf"));
        job.documents().add(new Document(1, tmp.resolve("a.pdf"), Map.of("zip", "90000")));
        job.documents().add(new Document(2, tmp.resolve("b.pdf"), Map.of("zip", "10000")));
        job.documents().add(new Document(3, tmp.resolve("c.pdf"), Map.of("zip", "90000")));

        new SortDocumentsStep(Map.of("by", "zip")).execute(job);

        assertThat(job.documents().stream().map(d -> d.file().getFileName().toString()).toList())
                .containsExactly("b.pdf", "a.pdf", "c.pdf");
        assertThat(job.documents().stream().map(Document::sequence).toList())
                .containsExactly(1, 2, 3);
    }
}
