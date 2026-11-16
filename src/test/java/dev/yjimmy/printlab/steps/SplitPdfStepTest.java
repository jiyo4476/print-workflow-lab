package dev.yjimmy.printlab.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Config validation. Splitting real PDFs is covered end to end by {@code StatementWorkflowTest}.
 */
public class SplitPdfStepTest {

    private static final List<Integer> RECT = List.of(0, 0, 100, 20);

    @Test
    public void validConfigIsAccepted() {
        new SplitPdfStep(Map.of("splitOn", "account",
                "regions", Map.of("account", RECT, "zip", List.of("1.5", "2", "3", "4"))));
    }

    @Test
    public void splitOnMustBeOneOfTheRegions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("splitOn", "account",
                        "regions", Map.of("zip", RECT))));

        assertThat(e.getMessage()).contains("splitOn 'account' must also be a region");
    }

    @Test
    public void regionMustBeFourNumbers() {
        for (Object bad : new Object[] {List.of(0, 0, 100), List.of(0, 0, 100, 20, 5), "0,0,1,1"}) {
            IllegalArgumentException e = assertThrows(String.valueOf(bad),
                    IllegalArgumentException.class, () -> new SplitPdfStep(Map.of(
                            "splitOn", "account", "regions", Map.of("account", bad))));

            assertThat(e.getMessage()).contains("Region 'account' must be [x, y, width, height]");
        }
    }

    @Test
    public void missingParamsAreNamed() {
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("regions", Map.of("a", RECT)))).getMessage())
                .contains("'splitOn'");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new SplitPdfStep(Map.of("splitOn", "a"))).getMessage())
                .contains("'regions'");
    }
}
