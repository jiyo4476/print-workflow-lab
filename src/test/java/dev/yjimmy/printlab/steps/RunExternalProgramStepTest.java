package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.FakeProgram;
import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.JobState;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.charset.StandardCharsets;
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
 * Runs the step through the engine, as a workflow would, so the job's current step (which names
 * the log file) is set. The external program is {@link FakeProgram} on the current JVM.
 */
public class RunExternalProgramStepTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tmp;
    private Job job;

    @Before
    public void setUp() throws Exception {
        tmp = folder.getRoot().toPath();
        Path input = Files.writeString(tmp.resolve("in.pdf"), "pdf");
        job = TestJobs.newJob(tmp, input);
    }

    @Test
    public void substitutesJobVariablesAndRunsInTheWorkDir() throws Exception {
        run(params(FakeProgram.command("echo", "${jobId}", "${workDir}", "${inputFile}",
                "id-${jobId}-x")));

        assertEquals("state", JobState.COMPLETE, job.state());
        assertThat(log()).containsExactly(
                "cwd=" + job.workDir().toAbsolutePath(),
                "arg=" + job.id(),
                "arg=" + job.workDir(),
                "arg=" + job.inputFile(),
                "arg=id-" + job.id() + "-x");
    }

    @Test
    public void nonZeroExitFailsTheJob() throws Exception {
        run(params(FakeProgram.command("exit", "3")));

        assertEquals("state", JobState.ERROR, job.state());
        assertThat(job.errorMessage()).contains("Exit code 3").contains("external-ext.log");
        assertThat(log()).contains("exiting 3");
    }

    @Test
    public void timeoutFailsTheJob() {
        Map<String, Object> params = params(FakeProgram.command("sleep", "60000"));
        params.put("timeoutSeconds", 1);

        run(params);

        assertEquals("state", JobState.ERROR, job.state());
        assertThat(job.errorMessage()).startsWith("Timed out after 1s");
    }

    @Test
    public void programThatCannotStartFailsTheJob() {
        run(params(List.of(tmp.resolve("no-such-program").toString())));

        assertEquals("state", JobState.ERROR, job.state());
        assertThat(job.errorMessage()).contains("no-such-program");
    }

    @Test
    public void commandIsRequiredAndMustBeANonEmptyList() {
        for (Object command : new Object[] {null, List.of(), "echo hi"}) {
            Map<String, Object> params = new HashMap<>();
            params.put("command", command);

            IllegalArgumentException e = assertThrows("command " + command,
                    IllegalArgumentException.class, () -> new RunExternalProgramStep(params));

            assertThat(e.getMessage()).contains("'command'").contains("non-empty list");
        }
    }

    private static Map<String, Object> params(List<String> command) {
        Map<String, Object> params = new HashMap<>();
        params.put("command", command);
        return params;
    }

    private void run(Map<String, Object> params) {
        new WorkflowEngine(
                TestWorkflows.of(new StepDefinition("ext", "RunExternalProgram", params)),
                StepRegistry.withBuiltIns(), tmp.resolve("errors"),
                TickingClock.oneSecondTicks()).run(job);
    }

    private List<String> log() throws Exception {
        return Files.readAllLines(job.workDir().resolve("external-ext.log"),
                StandardCharsets.UTF_8);
    }
}
