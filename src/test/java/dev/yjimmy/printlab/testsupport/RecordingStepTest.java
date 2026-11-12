package dev.yjimmy.printlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.yjimmy.printlab.workflow.Job;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RecordingStepTest {

    private static Job job(String id) {
        return new Job(id, Path.of(id + ".pdf"), Path.of("unused"));
    }

    @Test
    public void recordsEachCallWithItsNumberAndJob() throws Exception {
        RecordingStep step = new RecordingStep("A");

        step.execute(job("j1"));
        step.execute(job("j2"));

        assertThat(step.calls()).containsExactly(
                new RecordingStep.Call(1, "j1"), new RecordingStep.Call(2, "j2"));
    }

    @Test
    public void sharedTraceShowsOrderAcrossSteps() throws Exception {
        List<String> trace = new ArrayList<>();
        RecordingStep a = new RecordingStep("A", trace);
        RecordingStep b = new RecordingStep("B", trace);

        b.execute(job("j1"));
        a.execute(job("j1"));

        assertThat(trace).containsExactly("B", "A");
    }

    @Test
    public void throwsOnTheConfiguredCallAndStillRecordsIt() throws Exception {
        RecordingStep step = new RecordingStep("Boom")
                .failingOnCall(2, new IllegalStateException("bad page"));

        step.execute(job("j1"));
        assertThatThrownBy(() -> step.execute(job("j2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bad page");
        step.execute(job("j3"));

        assertThat(step.calls()).extracting(RecordingStep.Call::jobId)
                .containsExactly("j1", "j2", "j3");
    }

    @Test
    public void rejectsACallNumberBelowOne() {
        RecordingStep step = new RecordingStep("A");

        assertThatThrownBy(() -> step.failingOnCall(0, new IllegalStateException()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
