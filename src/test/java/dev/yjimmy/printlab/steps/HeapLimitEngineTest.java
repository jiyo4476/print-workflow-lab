package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The engine lets {@code OutOfMemoryError} escape on purpose. A step that runs its work through
 * {@link HeapLimit#run} turns running out of heap into an ordinary failed job instead, so the job
 * goes to the error folder and the worker carries on.
 */
public class HeapLimitEngineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Job run(StepRegistry registry, String type) throws Exception {
        Path tmp = folder.getRoot().toPath();
        Path input = Files.write(tmp.resolve("input.pdf"), new byte[] {1, 2, 3});
        Job job = TestJobs.newJob(tmp, input);
        new WorkflowEngine(TestWorkflows.of(TestWorkflows.step("split", type)), registry,
                tmp.resolve("error"), TickingClock.oneSecondTicks()).run(job);
        return job;
    }

    @Test
    public void heapExhaustionInAStepFailsTheJobAndDoesNotEscapeTheEngine() throws Exception {
        StepRegistry registry = new StepRegistry();
        registry.register("Splitter", params -> job -> HeapLimit.run(job.inputFile(), "Splitter",
                () -> 64L * 1024 * 1024, () -> {
                    throw new OutOfMemoryError("Java heap space");
                }));

        Job job = run(registry, "Splitter"); // returns: nothing escaped

        assertThat(job.state()).isEqualTo(JobState.ERROR);
        assertThat(job.errorMessage()).contains("Splitter ran out of memory (Java heap space)")
                .contains("-Xmx");
        Path errorFolder = folder.getRoot().toPath().resolve("error").resolve(job.id());
        assertThat(errorFolder.resolve("error.txt")).exists();
        assertThat(Files.readString(errorFolder.resolve("error.txt")))
                .contains("ran out of memory");
        assertThat(errorFolder.resolve("input.pdf")).as("the input moves to the error folder")
                .exists();
    }

    @Test
    public void otherOutOfMemoryStillEscapesTheEngineAsBefore() {
        StepRegistry registry = new StepRegistry();
        registry.register("Splitter", params -> job -> HeapLimit.run(job.inputFile(), "Splitter",
                () -> 64L * 1024 * 1024, () -> {
                    throw new OutOfMemoryError("Metaspace");
                }));

        org.junit.Assert.assertThrows(OutOfMemoryError.class, () -> run(registry, "Splitter"));
    }
}
