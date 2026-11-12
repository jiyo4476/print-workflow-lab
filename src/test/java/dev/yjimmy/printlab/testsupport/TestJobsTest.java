package dev.yjimmy.printlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestJobsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void newJobCreatesItsWorkDirUnderTheTempDir() throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = tmp.resolve("run.pdf");

        Job job = TestJobs.newJob(tmp, input);

        assertThat(job.id()).isEqualTo(TestJobs.DEFAULT_ID);
        assertThat(job.inputFile()).isEqualTo(input);
        assertThat(job.workDir()).startsWith(tmp).isDirectory();
        assertThat(job.state()).isEqualTo(JobState.RECEIVED);
    }

    @Test
    public void jobsWithDifferentIdsGetSeparateWorkDirs() throws Exception {
        Path tmp = folder.getRoot().toPath();

        Job a = TestJobs.newJob("a", tmp, tmp.resolve("a.pdf"));
        Job b = TestJobs.newJob("b", tmp, tmp.resolve("b.pdf"));

        assertThat(a.workDir()).isNotEqualTo(b.workDir());
    }
}
