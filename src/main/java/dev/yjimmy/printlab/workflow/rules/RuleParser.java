package dev.yjimmy.printlab.workflow.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Turns a condition from workflow YAML into a {@link Condition}. A condition is one of:
 *
 * <pre>
 *   { property: zip, operator: notMatches, value: "\\d{5}" }
 *   { all: [ condition, ... ] }
 *   { any: [ condition, ... ] }
 * </pre>
 *
 * Every mistake (unknown operator or key, a comparison without {@code value}, a regex that
 * doesn't compile) throws {@link IllegalArgumentException} here, when the workflow loads, never
 * while a job runs. Callers add the step name to the message.
 */
public final class RuleParser {

    private static final Set<String> RULE_KEYS = Set.of("property", "operator", "value");

    private RuleParser() {}

    public static Condition parse(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("A condition must be a map, got: " + raw);
        }
        if (map.containsKey("all") || map.containsKey("any")) {
            if (map.size() != 1) {
                throw new IllegalArgumentException(
                        "'all' or 'any' must be the only key in its condition, got " + map.keySet());
            }
            String key = map.containsKey("all") ? "all" : "any";
            List<Condition> children = children(key, map.get(key));
            return key.equals("all") ? new Condition.All(children) : new Condition.Any(children);
        }
        return rule(map);
    }

    private static List<Condition> children(String key, Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + key + "' must be a list of conditions");
        }
        List<Condition> children = new ArrayList<>();
        for (Object child : list) {
            children.add(parse(child));
        }
        return children;
    }

    private static Rule rule(Map<?, ?> map) {
        Set<String> unknown = map.keySet().stream()
                .map(String::valueOf)
                .filter(k -> !RULE_KEYS.contains(k))
                .collect(Collectors.toCollection(TreeSet::new));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown condition key(s) " + unknown
                    + "; use property/operator/value, all or any");
        }
        Object property = map.get("property");
        if (property == null || property.toString().isBlank()) {
            throw new IllegalArgumentException("A rule needs a 'property'");
        }
        Object operatorName = map.get("operator");
        if (operatorName == null) {
            throw new IllegalArgumentException("Rule on '" + property + "' needs an 'operator'");
        }
        Operator operator = Operator.fromYaml(operatorName.toString())
                .orElseThrow(() -> new IllegalArgumentException("Unknown operator '"
                        + operatorName + "' in rule on '" + property + "'"));
        Object value = map.get("value");
        if (operator.needsValue() && value == null) {
            throw new IllegalArgumentException("Operator '" + operator.yamlName()
                    + "' in rule on '" + property + "' needs a 'value'");
        }
        if (!operator.needsValue() && value != null) {
            throw new IllegalArgumentException("Operator '" + operator.yamlName()
                    + "' in rule on '" + property + "' takes no 'value'");
        }
        try {
            return Rule.of(property.toString(), operator, value == null ? null : value.toString());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex '" + value + "' in rule on '"
                    + property + "': " + e.getDescription());
        }
    }
}
