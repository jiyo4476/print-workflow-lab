package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.TestJobs;
import dev.yjimmy.printlab.testsupport.TestWorkflows;
import dev.yjimmy.printlab.testsupport.TickingClock;
import dev.yjimmy.printlab.workflow.Document;
import dev.yjimmy.printlab.workflow.Job;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import dev.yjimmy.printlab.workflow.WorkflowEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MapPropertiesStepTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void renamesListedProperties() throws Exception {
        Map<String, String> props = map(
                Map.of("ACCT_NO", "accountNumber", "ZIP_CODE", "zip"),
                "ACCT_NO", "123", "ZIP_CODE", "80202");

        assertEquals(List.of(Map.entry("accountNumber", "123"), Map.entry("zip", "80202")),
                List.copyOf(props.entrySet()));
    }

    @Test
    public void keepsUnlistedProperties() throws Exception {
        Map<String, String> props = map(Map.of("ACCT_NO", "accountNumber"),
                "pageCount", "2", "ACCT_NO", "123", "format", "afp");

        // The renamed property keeps its place.
        assertEquals(List.of(Map.entry("pageCount", "2"), Map.entry("accountNumber", "123"),
                Map.entry("format", "afp")), List.copyOf(props.entrySet()));
    }

    @Test
    public void missingOldNameIsSkipped() throws Exception {
        Map<String, String> props = map(
                Map.of("ACCT_NO", "accountNumber", "CUST_NAME", "name"), "ACCT_NO", "123");

        assertEquals(Map.of("accountNumber", "123"), props);
    }

    @Test
    public void conflictingValueMarksIndexErrorAndKeepsBoth() throws Exception {
        Map<String, String> props = map(
                Map.of("ACCT_NO", "accountNumber", "ZIP_CODE", "zip"),
                "ACCT_NO", "123", "accountNumber", "999", "ZIP_CODE", "80202");

        assertEquals(Map.of("ACCT_NO", "123", "accountNumber", "999", "zip", "80202",
                IndexErrors.PROPERTY, "rename conflict: ACCT_NO -> accountNumber"), props);
    }

    @Test
    public void sameValueUnderBothNamesIsNoConflict() throws Exception {
        Map<String, String> props = map(Map.of("ACCT_NO", "accountNumber"),
                "accountNumber", "123", "ACCT_NO", "123");

        assertEquals(Map.of("accountNumber", "123"), props);
    }

    @Test
    public void conflictThatBlocksAnotherRenameMarksBoth() throws Exception {
        // a -> b conflicts, so a stays, and x -> a then lands on a's different value.
        Map<String, Object> rename = new LinkedHashMap<>();
        rename.put("a", "b");
        rename.put("x", "a");
        Map<String, String> props = map(rename, "x", "1", "a", "2", "b", "3");

        assertEquals(Map.of("x", "1", "a", "2", "b", "3", IndexErrors.PROPERTY,
                "rename conflict: a -> b; rename conflict: x -> a"), props);
    }

    @Test
    public void requiredPropertiesAreCheckedAfterRenaming() throws Exception {
        Map<String, Object> params = Map.of(
                "rename", Map.of("ACCT_NO", "accountNumber", "CUST_NAME", "name"),
                "requiredProperties", List.of("accountNumber", "name", "zip"));
        Map<String, String> props = run(params, "ACCT_NO", "123", "CUST_NAME", " ");

        assertEquals("indexError", "missing: name, zip", props.get(IndexErrors.PROPERTY));
        assertEquals("accountNumber", "123", props.get("accountNumber"));
    }

    @Test
    public void indexErrorFromAnEarlierStepIsKept() throws Exception {
        Map<String, Object> params = Map.of("rename", Map.of("ACCT_NO", "accountNumber"),
                "requiredProperties", List.of("zip"));
        Map<String, String> props = run(params, "ACCT_NO", "1", IndexErrors.PROPERTY, "earlier");

        assertEquals("indexError", "earlier; missing: zip", props.get(IndexErrors.PROPERTY));
    }

    @Test
    public void renamesApplyTogetherSoOrderDoesNotMatter() throws Exception {
        Map<String, Object> swap = new LinkedHashMap<>();
        swap.put("a", "b");
        swap.put("b", "a");
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("b", "c");
        chain.put("a", "b");

        assertEquals("swap", Map.of("a", "2", "b", "1"), map(swap, "a", "1", "b", "2"));
        assertEquals("chain", Map.of("b", "1", "c", "2"), map(chain, "a", "1", "b", "2"));
    }

    @Test
    public void twoOldNamesToOneNewNameFailsAtLoad() {
        Map<String, Object> rename = new LinkedHashMap<>();
        rename.put("ACCT_NO", "accountNumber");
        rename.put("ACCOUNT", "accountNumber");
        StepDefinition step = new StepDefinition("map", "MapProperties", Map.of("rename", rename));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowEngine(TestWorkflows.of(step), StepRegistry.withBuiltIns(),
                        tmp.getRoot().toPath().resolve("error"), TickingClock.oneSecondTicks()));

        assertEquals("Step 'map' (MapProperties): 'rename' maps both 'ACCT_NO' and 'ACCOUNT' to"
                + " 'accountNumber'; a document with both would have two values for it",
                e.getMessage());
    }

    @Test
    public void badRenameParamsFailAtLoad() {
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new MapPropertiesStep(Map.of())).getMessage()).contains("'rename'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new MapPropertiesStep(Map.of("rename", Map.of()))).getMessage())
                .contains("at least one");
        Map<String, Object> noNewName = new LinkedHashMap<>();
        noNewName.put("ACCT_NO", null);
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new MapPropertiesStep(Map.of("rename", noNewName))).getMessage())
                .contains("no new name for 'ACCT_NO'");
    }

    @Test
    public void mapsArbitraryNamesFromWorkflowConfig() throws Exception {
        Path yaml = tmp.newFile("custom.yaml").toPath();
        Files.writeString(yaml, """
                name: custom
                phases:
                  prepare:
                    - name: map
                      type: MapProperties
                      params:
                        rename:
                          "Policy Holder": holder
                          polNo: policy
                          x.y-z: renamed
                        requiredProperties: [policy]
                """);
        Path root = tmp.getRoot().toPath();
        Job job = TestJobs.newJob(root, root.resolve("in.afp"));
        job.documents().add(new Document(1, root.resolve("d.afp"),
                Map.of("Policy Holder", "Sam", "polNo", "P-1", "x.y-z", "v", "other", "o")));

        new WorkflowEngine(WorkflowDefinition.load(yaml),
                StepRegistry.withBuiltIns(), root.resolve("error"), TickingClock.oneSecondTicks())
                .run(job);

        assertEquals(Map.of("holder", "Sam", "policy", "P-1", "renamed", "v", "other", "o"),
                job.documents().get(0).properties());
    }

    /** Runs the step with just a rename map on one document with {@code keyValues}. */
    private Map<String, String> map(Map<String, Object> rename, String... keyValues)
            throws Exception {
        return run(Map.of("rename", rename), keyValues);
    }

    private Map<String, String> run(Map<String, Object> params, String... keyValues)
            throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.put(keyValues[i], keyValues[i + 1]);
        }
        Path root = tmp.getRoot().toPath();
        Job job = TestJobs.newJob(root, root.resolve("in.afp"));
        job.documents().add(new Document(1, root.resolve("d.afp"), props));
        new MapPropertiesStep(params).execute(job);
        return job.documents().get(0).properties();
    }
}
