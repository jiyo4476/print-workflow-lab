package dev.yjimmy.printlab.workflow.rules;

import java.util.Arrays;
import java.util.Optional;

/** The operators a {@link Rule} can use, by the name they have in workflow YAML. */
public enum Operator {
    EQUALS("equals"),
    NOT_EQUALS("notEquals"),
    MATCHES("matches"),
    NOT_MATCHES("notMatches"),
    GREATER_THAN("greaterThan"),
    LESS_THAN("lessThan"),
    MISSING("missing");

    private final String yamlName;

    Operator(String yamlName) {
        this.yamlName = yamlName;
    }

    public String yamlName() {
        return yamlName;
    }

    /** Every operator except {@code missing} compares the property against a {@code value}. */
    public boolean needsValue() {
        return this != MISSING;
    }

    public static Optional<Operator> fromYaml(String name) {
        return Arrays.stream(values()).filter(o -> o.yamlName.equals(name)).findFirst();
    }
}
