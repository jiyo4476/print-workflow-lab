package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class PngPredictorTest {

    /** The row above every vector's second row. */
    private static final int[] ABOVE = {10, 20, 30, 40};

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    /**
     * Each filter type on a row of 4 one-byte pixels below {@link #ABOVE}, worked by hand from
     * the PNG specification's definitions.
     */
    @RunWith(Parameterized.class)
    public static class KnownVectors {

        @Parameters(name = "{0}")
        public static List<Object[]> vectors() {
            return List.of(
                    new Object[] {"None", 0, new int[] {1, 2, 3, 4}, new int[] {1, 2, 3, 4}},
                    new Object[] {"Sub", 1, new int[] {5, 3, 3, 3}, new int[] {5, 8, 11, 14}},
                    new Object[] {"Sub wraps modulo 256", 1, new int[] {200, 100, 0, 0}, new int[] {200, 44, 44, 44}},
                    new Object[] {"Up", 2, new int[] {1, 1, 1, 1}, new int[] {11, 21, 31, 41}},
                    // 2 + floor((left + up) / 2): 2 + 5, 2 + 13, 2 + 22, 2 + 32
                    new Object[] {"Average", 3, new int[] {2, 2, 2, 2}, new int[] {7, 15, 24, 34}},
                    // First byte: left 0, up 10, upper-left 0, so up is nearest; then left wins.
                    new Object[] {"Paeth", 4, new int[] {100, 0, 0, 0}, new int[] {110, 110, 110, 110}});
        }

        @Parameter(0)
        public String name;

        @Parameter(1)
        public int type;

        @Parameter(2)
        public int[] raw;

        @Parameter(3)
        public int[] expected;

        @Test
        public void secondRowDecodesAsWorkedByHand() {
            byte[] data = new byte[10];
            data[0] = 0;
            System.arraycopy(bytes(ABOVE), 0, data, 1, 4);
            data[5] = (byte) type;
            System.arraycopy(bytes(raw), 0, data, 6, 4);

            byte[] decoded = PngPredictor.decode(data, 1, 8, 4);

            assertThat(Arrays.copyOfRange(decoded, 4, 8)).as(name).isEqualTo(bytes(expected));
            assertThat(Arrays.copyOfRange(decoded, 0, 4)).as("first row, None").isEqualTo(bytes(ABOVE));
        }
    }

    public static class Behavior {

        @Test
        public void paethPicksTheNearestOfLeftUpAndUpperLeftWithTiesInThatOrder() {
            assertEquals("left exact", 5, PngPredictor.paeth(5, 10, 10));
            assertEquals("up nearest", 10, PngPredictor.paeth(0, 10, 0));
            assertEquals("upper left nearest", 25, PngPredictor.paeth(20, 30, 25));
            assertEquals("all equal: left", 7, PngPredictor.paeth(7, 7, 7));
            // estimate 4 + 22 - 10 = 16: left is 12 away, up and upper left both 6.
            assertEquals("up and upper left tie: up", 22, PngPredictor.paeth(4, 22, 10));
        }

        @Test
        public void leftIsOnePixelBackNotOneByte() {
            // Two pixels of 3 bytes (RGB): Sub adds the byte 3 back, not the one just before.
            byte[] data = bytes(1, 10, 20, 30, 1, 2, 3);

            assertThat(PngPredictor.decode(data, 3, 8, 2)).containsExactly(10, 20, 30, 11, 22, 33);
        }

        @Test
        public void aShortLastRowDecodesAsFarAsItGoes() {
            byte[] data = bytes(0, 1, 2, 3, 2, 1);

            assertThat(PngPredictor.decode(data, 1, 8, 3)).containsExactly(1, 2, 3, 2);
        }

        @Test
        public void unknownRowTypeAndBadParametersFail() {
            assertThat(assertThrows(IllegalArgumentException.class,
                    () -> PngPredictor.decode(bytes(0, 1, 5, 1), 1, 8, 1)).getMessage())
                    .isEqualTo("row 2 has PNG filter type 5; only 0 to 4 exist");
            assertThat(assertThrows(IllegalArgumentException.class,
                    () -> PngPredictor.decode(bytes(0), 0, 8, 1)).getMessage())
                    .contains("no valid row length for /Colors 0");
            assertThrows(IllegalArgumentException.class,
                    () -> PngPredictor.decode(bytes(0), 32, 16, Integer.MAX_VALUE));
        }

        /** Random rows filtered with random types decode back, for several pixel sizes. */
        @Test
        public void randomRowsRoundTripForEveryFilterAndPixelSize() {
            Random random = new Random(42);
            int[][] layouts = {{1, 8, 7}, {3, 8, 5}, {1, 16, 4}, {1, 1, 20}, {4, 8, 3}};
            for (int[] layout : layouts) {
                int colors = layout[0];
                int bits = layout[1];
                int columns = layout[2];
                int pixel = Math.max(1, colors * bits / 8);
                int rowLength = (colors * bits * columns + 7) / 8;
                for (int trial = 0; trial < 50; trial++) {
                    byte[][] rows = new byte[6][rowLength];
                    for (byte[] row : rows) {
                        random.nextBytes(row);
                    }
                    byte[] encoded = new byte[rows.length * (rowLength + 1)];
                    byte[] previous = new byte[rowLength];
                    int at = 0;
                    for (byte[] row : rows) {
                        int type = random.nextInt(5);
                        encoded[at++] = (byte) type;
                        for (int i = 0; i < rowLength; i++) {
                            int left = i >= pixel ? row[i - pixel] & 0xFF : 0;
                            int up = previous[i] & 0xFF;
                            int upLeft = i >= pixel ? previous[i - pixel] & 0xFF : 0;
                            int prediction = switch (type) {
                                case 1 -> left;
                                case 2 -> up;
                                case 3 -> (left + up) / 2;
                                case 4 -> PngPredictor.paeth(left, up, upLeft);
                                default -> 0;
                            };
                            encoded[at++] = (byte) ((row[i] & 0xFF) - prediction);
                        }
                        previous = row;
                    }
                    byte[] expected = new byte[rows.length * rowLength];
                    for (int r = 0; r < rows.length; r++) {
                        System.arraycopy(rows[r], 0, expected, r * rowLength, rowLength);
                    }

                    assertThat(PngPredictor.decode(encoded, colors, bits, columns))
                            .as("colors %d, bits %d, columns %d, trial %d", colors, bits, columns, trial)
                            .isEqualTo(expected);
                }
            }
        }
    }
}
