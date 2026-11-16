package dev.yjimmy.printlab.workflow.rules;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One comparison: {@code property operator value}.
 *
 * <ul>
 *   <li>{@code equals} / {@code notEquals} compare text exactly. An absent property equals
 *       nothing, so {@code notEquals} is true for it.</li>
 *   <li>{@code matches} / {@code notMatches} test the <b>whole</b> value against a regex, so
 *       {@code \d{5}} means "exactly five digits". An absent property matches nothing.</li>
 *   <li>{@code greaterThan} / {@code lessThan} compare as numbers when both sides parse as
 *       numbers ({@code "10" > "9"}), otherwise as text. An absent property is neither.</li>
 *   <li>{@code missing} is true when the property is absent or blank.</li>
 * </ul>
 *
 * @param pattern the compiled {@code value}, for {@code matches} / {@code notMatches} only
 */
public record Rule(String property, Operator operator, String value, Pattern pattern)
        implements Condition {

    /** Builds a rule, compiling {@code value} once if the operator is a regex one. */
    public static Rule of(String property, Operator operator, String value) {
        boolean regex = operator == Operator.MATCHES || operator == Operator.NOT_MATCHES;
        return new Rule(property, operator, value, regex ? Pattern.compile(value) : null);
    }

    @Override
    public boolean test(Map<String, String> properties) {
        String actual = properties.get(property);
        return switch (operator) {
            case EQUALS -> value.equals(actual);
            case NOT_EQUALS -> !value.equals(actual);
            case MATCHES -> actual != null && pattern.matcher(actual).matches();
            case NOT_MATCHES -> actual == null || !pattern.matcher(actual).matches();
            case GREATER_THAN -> actual != null && compare(actual, value) > 0;
            case LESS_THAN -> actual != null && compare(actual, value) < 0;
            case MISSING -> actual == null || actual.isBlank();
        };
    }

    private static int compare(String left, String right) {
        BigDecimal l = number(left);
        BigDecimal r = number(right);
        return l != null && r != null ? l.compareTo(r) : left.compareTo(right);
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
