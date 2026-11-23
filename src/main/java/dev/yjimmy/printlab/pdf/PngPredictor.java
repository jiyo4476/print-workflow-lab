package dev.yjimmy.printlab.pdf;

import java.io.ByteArrayOutputStream;

/**
 * Undoes the PNG predictors a Flate stream may use ({@code /Predictor} 10 to 15, ISO 32000-1,
 * 7.4.4.4 and Table 8; the filters themselves are PNG's, RFC 2083 section 6). Cross-reference
 * streams almost always use one, typically Up, because it turns rows of similar offsets into
 * mostly zeros that compress well.
 *
 * <p>The data is rows of {@code columns} samples, each row preceded by a filter-type byte:
 * <ul>
 *   <li>0 None: the byte as it is</li>
 *   <li>1 Sub: plus the byte one pixel to the left</li>
 *   <li>2 Up: plus the byte above, in the previous row</li>
 *   <li>3 Average: plus the floor of the mean of left and above</li>
 *   <li>4 Paeth: plus whichever of left, above and upper-left is nearest to left + above -
 *       upper-left</li>
 * </ul>
 * "Left" is one pixel back ({@code colors * bitsPerComponent / 8} bytes, at least 1); before the
 * first pixel and above the first row it's 0. All arithmetic is modulo 256. With predictors 10 to
 * 15 the value only hints at the filter; each row's own type byte decides.
 */
final class PngPredictor {

    private PngPredictor() {
    }

    /**
     * Decodes {@code data}. A last row shorter than the others is decoded as far as it goes.
     *
     * @throws IllegalArgumentException for a row filter type outside 0 to 4, or parameters that
     *     give no valid row length
     */
    static byte[] decode(byte[] data, int colors, int bitsPerComponent, int columns) {
        long bitsPerPixel = (long) colors * bitsPerComponent;
        int pixel = (int) Math.max(1, (bitsPerPixel + 7) / 8);
        long rowLength = (bitsPerPixel * columns + 7) / 8;
        if (colors < 1 || bitsPerComponent < 1 || columns < 1 || rowLength > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("no valid row length for /Colors " + colors
                    + " /BitsPerComponent " + bitsPerComponent + " /Columns " + columns);
        }
        int length = (int) rowLength;
        byte[] previous = new byte[length];
        byte[] row = new byte[length];
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int position = 0;
        for (int number = 1; position < data.length; number++) {
            int type = data[position++] & 0xFF;
            int n = Math.min(length, data.length - position);
            for (int i = 0; i < n; i++) {
                int raw = data[position + i] & 0xFF;
                int left = i >= pixel ? row[i - pixel] & 0xFF : 0;
                int up = previous[i] & 0xFF;
                int upLeft = i >= pixel ? previous[i - pixel] & 0xFF : 0;
                row[i] = (byte) (raw + switch (type) {
                    case 0 -> 0;
                    case 1 -> left;
                    case 2 -> up;
                    case 3 -> (left + up) >>> 1;
                    case 4 -> paeth(left, up, upLeft);
                    default -> throw new IllegalArgumentException("row " + number
                            + " has PNG filter type " + type + "; only 0 to 4 exist");
                });
            }
            position += n;
            out.write(row, 0, n);
            byte[] swap = previous;
            previous = row;
            row = swap;
        }
        return out.toByteArray();
    }

    /** Whichever of {@code a} (left), {@code b} (up) and {@code c} (upper left) is nearest a + b - c; ties go in that order. */
    static int paeth(int a, int b, int c) {
        int estimate = a + b - c;
        int toA = Math.abs(estimate - a);
        int toB = Math.abs(estimate - b);
        int toC = Math.abs(estimate - c);
        if (toA <= toB && toA <= toC) {
            return a;
        }
        return toB <= toC ? b : c;
    }
}
