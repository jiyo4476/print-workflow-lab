package dev.yjimmy.printlab.workflow.rules;

import java.util.List;
import java.util.Map;

/**
 * A test against a set of properties (a document's or a job's): the rules that connectors carry
 * in a workflow. Built from YAML by {@link RuleParser}, which checks everything when the workflow
 * loads, so evaluating a condition never fails on configuration.
 */
public sealed interface Condition permits Rule, Condition.All, Condition.Any {

    boolean test(Map<String, String> properties);

    /** True when every condition is; an empty {@code all} is true. */
    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = List.copyOf(conditions);
        }

        @Override
        public boolean test(Map<String, String> properties) {
            return conditions.stream().allMatch(c -> c.test(properties));
        }
    }

    /** True when at least one condition is; an empty {@code any} is false. */
    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = List.copyOf(conditions);
        }

        @Override
        public boolean test(Map<String, String> properties) {
            return conditions.stream().anyMatch(c -> c.test(properties));
        }
    }
}
