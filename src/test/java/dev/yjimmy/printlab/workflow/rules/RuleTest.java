package dev.yjimmy.printlab.workflow.rules;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** One row per case: a condition, the properties it's tested against, and the expected result. */
@RunWith(Parameterized.class)
public class RuleTest {

    private static final Map<String, String> DOC = Map.of(
            "zip", "80202",
            "shortZip", "8020",
            "name", "Avery Nguyen",
            "blank", "   ",
            "pageCount", "10");

    @Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            {"equals true", rule("zip", Operator.EQUALS, "80202"), true},
            {"equals false", rule("zip", Operator.EQUALS, "80203"), false},
            {"equals absent is false", rule("nope", Operator.EQUALS, "x"), false},
            {"notEquals true", rule("zip", Operator.NOT_EQUALS, "80203"), true},
            {"notEquals false", rule("zip", Operator.NOT_EQUALS, "80202"), false},
            {"notEquals absent is true", rule("nope", Operator.NOT_EQUALS, "x"), true},
            {"matches true", rule("zip", Operator.MATCHES, "\\d{5}"), true},
            {"matches false", rule("shortZip", Operator.MATCHES, "\\d{5}"), false},
            {"matches tests the whole value", rule("zip", Operator.MATCHES, "\\d{4}"), false},
            {"matches absent is false", rule("nope", Operator.MATCHES, ".*"), false},
            {"notMatches true", rule("shortZip", Operator.NOT_MATCHES, "\\d{5}"), true},
            {"notMatches false", rule("zip", Operator.NOT_MATCHES, "\\d{5}"), false},
            {"notMatches absent is true", rule("nope", Operator.NOT_MATCHES, "\\d{5}"), true},
            {"greaterThan true", rule("pageCount", Operator.GREATER_THAN, "6"), true},
            {"greaterThan false", rule("pageCount", Operator.GREATER_THAN, "10"), false},
            {"greaterThan is numeric: 10 > 9", rule("pageCount", Operator.GREATER_THAN, "9"), true},
            {"greaterThan absent is false", rule("nope", Operator.GREATER_THAN, "0"), false},
            {"greaterThan falls back to text", rule("name", Operator.GREATER_THAN, "Aaron"), true},
            {"lessThan true", rule("pageCount", Operator.LESS_THAN, "11"), true},
            {"lessThan false", rule("pageCount", Operator.LESS_THAN, "10"), false},
            {"lessThan is numeric: 10 < 9 is false", rule("pageCount", Operator.LESS_THAN, "9"),
                false},
            {"lessThan absent is false", rule("nope", Operator.LESS_THAN, "100"), false},
            {"missing absent is true", rule("nope", Operator.MISSING, null), true},
            {"missing blank is true", rule("blank", Operator.MISSING, null), true},
            {"missing present is false", rule("name", Operator.MISSING, null), false},
            {"empty all is true", new Condition.All(List.of()), true},
            {"empty any is false", new Condition.Any(List.of()), false},
            {"all true when every one is", all(TRUE, TRUE), true},
            {"all false when one isn't", all(TRUE, FALSE), false},
            {"any true when one is", any(FALSE, TRUE), true},
            {"any false when none is", any(FALSE, FALSE), false},
            {"nested two levels deep, true", all(any(FALSE, all(TRUE, TRUE)), TRUE), true},
            {"nested two levels deep, false", all(any(FALSE, all(TRUE, FALSE)), TRUE), false},
        });
    }

    private static final Condition TRUE = rule("zip", Operator.EQUALS, "80202");
    private static final Condition FALSE = rule("zip", Operator.EQUALS, "nope");

    private static Rule rule(String property, Operator operator, String value) {
        return Rule.of(property, operator, value);
    }

    private static Condition all(Condition... c) {
        return new Condition.All(List.of(c));
    }

    private static Condition any(Condition... c) {
        return new Condition.Any(List.of(c));
    }

    private final String description;
    private final Condition condition;
    private final boolean expected;

    public RuleTest(String description, Condition condition, boolean expected) {
        this.description = description;
        this.condition = condition;
        this.expected = expected;
    }

    @Test
    public void conditionGivesExpectedResult() {
        assertEquals(description, expected, condition.test(DOC));
    }
}
