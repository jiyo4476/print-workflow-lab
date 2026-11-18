package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ParamsTest {

    private static Params params(Object... keysAndValues) {
        Map<String, Object> raw = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new Params(raw);
    }

    @Test
    public void requiredStringNamesTheMissingParam() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> params().string("splitOn"));

        assertThat(e.getMessage()).contains("'splitOn'");
    }

    @Test
    public void stringsAreConvertedFromOtherYamlTypes() {
        assertEquals("number", "5", params("n", 5).string("n"));
        assertEquals("fallback unused", "x", params("s", "x").string("s", "dflt"));
        assertEquals("fallback", "dflt", params().string("s", "dflt"));
    }

    @Test
    public void integersAcceptNumbersAndNumericStrings() {
        assertEquals("YAML integer", 30, params("t", 30).integer("t", 60));
        assertEquals("quoted in YAML", 30, params("t", "30").integer("t", 60));
        assertEquals("fallback", 60, params().integer("t", 60));
    }

    @Test
    public void nonNumericIntegerFails() {
        assertThrows(NumberFormatException.class, () -> params("t", "soon").integer("t", 60));
    }

    @Test
    public void mapMustBeAMap() {
        Map<String, Object> regions = Map.of("a", List.of(1, 2, 3, 4));
        assertEquals("map", regions, params("regions", regions).map("regions"));

        for (Object notAMap : new Object[] {null, "a", List.of()}) {
            IllegalArgumentException e = assertThrows(String.valueOf(notAMap),
                    IllegalArgumentException.class, () -> params("regions", notAMap).map("regions"));

            assertThat(e.getMessage()).contains("'regions' must be a map");
        }
    }

    @Test
    public void stringListConvertsElementsToStrings() {
        assertThat(params("command", List.of("java", 1, true)).stringList("command"))
                .containsExactly("java", "1", "true");
    }
}
