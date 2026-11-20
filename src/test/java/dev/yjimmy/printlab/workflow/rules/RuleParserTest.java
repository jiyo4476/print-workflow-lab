package dev.yjimmy.printlab.workflow.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.yjimmy.printlab.workflow.StepRegistry;
import dev.yjimmy.printlab.workflow.WorkflowDefinition;
import dev.yjimmy.printlab.workflow.WorkflowDefinition.StepDefinition;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RuleParserTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The RouteDocuments example from docs/PR_PLAN.md, verbatim. */
    private static final String PLAN_EXAMPLE = """
            - name: route
              type: RouteDocuments
              params:
                routes:
                  - queue: exceptions
                    when: { any: [ { property: zip, operator: notMatches, value: "\\\\d{5}" },
                                   { property: name, operator: missing } ] }
                  - queue: large-envelope
                    when: { property: pageCount, operator: greaterThan, value: 6 }
                default: standard
            """;

    private static Object yaml(String text) throws Exception {
        return YAML.readValue(text, Object.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void parsesThePlanExample() throws Exception {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) yaml(PLAN_EXAMPLE);
        Map<String, Object> params = (Map<String, Object>) steps.get(0).get("params");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) params.get("routes");

        Condition exceptions = RuleParser.parse(routes.get(0).get("when"));
        Condition large = RuleParser.parse(routes.get(1).get("when"));

        assertThat(exceptions).isInstanceOf(Condition.Any.class);
        List<Condition> any = ((Condition.Any) exceptions).conditions();
        assertThat(any).hasSize(2);
        Rule zip = (Rule) any.get(0);
        assertThat(zip.property()).isEqualTo("zip");
        assertThat(zip.operator()).isEqualTo(Operator.NOT_MATCHES);
        assertThat(zip.pattern().pattern()).isEqualTo("\\d{5}");
        assertThat(any.get(1)).isEqualTo(Rule.of("name", Operator.MISSING, null));
        assertThat(large).isEqualTo(Rule.of("pageCount", Operator.GREATER_THAN, "6"));

        assertThat(exceptions.test(Map.of("zip", "8020", "name", "A"))).isTrue();
        assertThat(exceptions.test(Map.of("zip", "80202", "name", ""))).isTrue();
        assertThat(exceptions.test(Map.of("zip", "80202", "name", "A"))).isFalse();
        assertThat(large.test(Map.of("pageCount", "7"))).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void planExampleBuildsARouteDocumentsStep() throws Exception {
        Map<String, Object> step = new java.util.LinkedHashMap<>(
                (Map<String, Object>) ((List<?>) yaml(PLAN_EXAMPLE)).get(0));
        step.put("phase", "prepare");
        StepDefinition def = YAML.convertValue(step, StepDefinition.class);
        StepRegistry.withBuiltIns().create(def);
    }

    @Test
    public void unknownOperatorFailsWithStepName() throws Exception {
        assertThat(routeStepFailure("{ property: zip, operator: startsWith, value: '8' }"))
                .contains("Step 'route'")
                .contains("Unknown operator 'startsWith'");
    }

    @Test
    public void invalidRegexFailsAtLoadNotAtRuntime() throws Exception {
        assertThat(routeStepFailure("{ property: zip, operator: matches, value: '[0-9' }"))
                .contains("Step 'route'")
                .contains("Invalid regex '[0-9'");
    }

    @Test
    public void missingValueForComparisonOperatorFails() throws Exception {
        assertThat(routeStepFailure("{ property: pageCount, operator: greaterThan }"))
                .contains("Step 'route'")
                .contains("'greaterThan' in rule on 'pageCount' needs a 'value'");
    }

    @Test
    public void missingOperatorTakesNoValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("property", "name", "operator", "missing",
                        "value", "x")));
        assertThat(e).hasMessageContaining("takes no 'value'");
    }

    @Test
    public void unknownConditionKeyFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("property", "zip", "operator", "missing",
                        "vaule", "x")));
        assertThat(e).hasMessageContaining("[vaule]");
    }

    @Test
    public void allOrAnyMustBeAListAndTheOnlyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("all", "nope")));
        assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("all", List.of(), "any", List.of())));
        assertThrows(IllegalArgumentException.class, () -> RuleParser.parse("zip"));
    }

    @Test
    public void ruleNeedsPropertyAndOperator() {
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("operator", "missing"))))
                .hasMessageContaining("needs a 'property'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("property", "  ", "operator", "missing"))))
                .hasMessageContaining("needs a 'property'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> RuleParser.parse(Map.of("property", "zip"))))
                .hasMessageContaining("needs an 'operator'");
    }

    @Test
    public void nestedConditionsParse() {
        Condition c = RuleParser.parse(Map.of("all", List.of(
                Map.of("any", List.of(Map.of("property", "a", "operator", "missing"))),
                Map.of("property", "b", "operator", "equals", "value", 1))));
        assertThat(c.test(Map.of("b", "1"))).isTrue();
        assertThat(c.test(Map.of("a", "x", "b", "1"))).isFalse();
    }

    /** Builds a one-route RouteDocuments step from YAML and returns the error message. */
    private static String routeStepFailure(String when) throws Exception {
        WorkflowDefinition.StepDefinition def = YAML.convertValue(yaml("""
                name: route
                type: RouteDocuments
                phase: prepare
                params:
                  routes:
                    - queue: exceptions
                      when: %s
                  default: standard
                """.formatted(when)), WorkflowDefinition.StepDefinition.class);
        return assertThrows(IllegalArgumentException.class,
                () -> StepRegistry.withBuiltIns().create(def)).getMessage();
    }
}
