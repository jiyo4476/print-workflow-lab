package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.workflow.Job;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SetJobPropsFromTextFileStepTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Job job;

    @Before
    public void newJob() throws Exception {
        Path root = tmp.getRoot().toPath();
        job = TestJobs.newJob(root, root.resolve("run.pdf"));
    }

    @Test
    public void setsJobPropertiesFromTheInputsTicket() throws Exception {
        ticket("run.pdf.jobprops", "customer=ACME Bank", "mailClass=first");

        new SetJobPropsFromTextFileStep(Map.of()).execute(job);

        assertEquals(List.of(Map.entry("inputFileName", "run.pdf"),
                Map.entry("customer", "ACME Bank"), Map.entry("mailClass", "first")),
                List.copyOf(job.properties().entrySet()));
    }

    @Test
    public void commentsBlankLinesAndSpacesAreIgnored() throws Exception {
        ticket("run.pdf.jobprops", "﻿# job ticket", "", "   customer =  ACME  ",
                "  # indented comment", "query=a=b");

        new SetJobPropsFromTextFileStep(Map.of()).execute(job);

        assertEquals("customer", "ACME", job.properties().get("customer"));
        assertEquals("value keeps its '='", "a=b", job.properties().get("query"));
        assertThat(job.properties()).doesNotContainKeys("# job ticket", "");
    }

    @Test
    public void laterLineWinsAndOverridesExistingProperties() throws Exception {
        job.properties().put("priority", "normal");
        ticket("run.pdf.jobprops", "priority=rush", "copies=1", "copies=2");

        new SetJobPropsFromTextFileStep(Map.of()).execute(job);

        assertEquals("priority", "rush", job.properties().get("priority"));
        assertEquals("copies", "2", job.properties().get("copies"));
    }

    @Test
    public void ticketCannotChangeProtectedProperties() throws Exception {
        ticket("run.pdf.jobprops", "inputFileName=other.pdf", "customer=ACME");

        new SetJobPropsFromTextFileStep(Map.of()).execute(job);

        assertEquals("inputFileName kept", "run.pdf", job.properties().get("inputFileName"));
        assertEquals("other lines still apply", "ACME", job.properties().get("customer"));
    }

    @Test
    public void missingTicketDoesNothing() throws Exception {
        Map<String, String> before = Map.copyOf(job.properties());

        new SetJobPropsFromTextFileStep(Map.of()).execute(job);

        assertEquals(before, job.properties());
    }

    @Test
    public void missingRequiredTicketFailsTheJob() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SetJobPropsFromTextFileStep(Map.of("required", true)).execute(job));

        assertThat(e.getMessage()).contains("No job properties file 'run.pdf.jobprops'")
                .contains("before the input file");
    }

    @Test
    public void namedFileIsReadInsteadOfTheDefault() throws Exception {
        ticket("run.pdf.jobprops", "from=default");
        ticket("ticket.txt", "from=named");

        new SetJobPropsFromTextFileStep(Map.of("fileName", "ticket.txt")).execute(job);

        assertEquals("from", "named", job.properties().get("from"));
    }

    @Test
    public void lineWithoutEqualsFailsWithItsLineNumber() throws Exception {
        ticket("run.pdf.jobprops", "# ticket", "customer=ACME", "just some text");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SetJobPropsFromTextFileStep(Map.of()).execute(job));

        assertEquals("run.pdf.jobprops line 3 isn't name=value: 'just some text'", e.getMessage());
    }

    @Test
    public void emptyNameFailsWithItsLineNumber() throws Exception {
        ticket("run.pdf.jobprops", " =value");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SetJobPropsFromTextFileStep(Map.of()).execute(job));

        assertEquals("run.pdf.jobprops line 1 has no property name before '='", e.getMessage());
    }

    @Test
    public void badParamsFailAtLoad() {
        for (String name : new String[] {"../escape.txt", "sub/ticket.txt", "..", " "}) {
            assertThat(assertThrows(name, IllegalArgumentException.class,
                    () -> new SetJobPropsFromTextFileStep(Map.of("fileName", name))).getMessage())
                    .contains("'fileName' must be a file name");
        }
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new SetJobPropsFromTextFileStep(Map.of("required", "yes"))).getMessage())
                .contains("'required' must be true or false");
    }

    private void ticket(String name, String... lines) throws Exception {
        Files.write(job.workDir().resolve(name), List.of(lines), StandardCharsets.UTF_8);
    }
}
