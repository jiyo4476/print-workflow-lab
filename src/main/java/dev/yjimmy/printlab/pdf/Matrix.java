package dev.yjimmy.printlab.pdf;

/**
 * A PDF transformation matrix {@code [a b c d e f]} (ISO 32000-1, 8.3.3), standing for
 *
 * <pre>
 *   | a b 0 |
 *   | c d 0 |
 *   | e f 1 |
 * </pre>
 *
 * <p>PDF writes points as row vectors, {@code [x y 1] × M}, so in {@code A.multiply(B)} the
 * transformation {@code A} is applied first and {@code B} after it. That is why {@code cm}
 * computes {@code new.multiply(ctm)}: the new matrix works in the coordinates the old CTM maps.
 */
public record Matrix(double a, double b, double c, double d, double e, double f) {

    public static final Matrix IDENTITY = new Matrix(1, 0, 0, 1, 0, 0);

    public static Matrix translate(double tx, double ty) {
        return new Matrix(1, 0, 0, 1, tx, ty);
    }

    public static Matrix scale(double sx, double sy) {
        return new Matrix(sx, 0, 0, sy, 0, 0);
    }

    /** {@code this × other}: this transformation first, then {@code other}. */
    public Matrix multiply(Matrix o) {
        return new Matrix(
                a * o.a + b * o.c,
                a * o.b + b * o.d,
                c * o.a + d * o.c,
                c * o.b + d * o.d,
                e * o.a + f * o.c + o.e,
                e * o.b + f * o.d + o.f);
    }

    /** The point {@code (x, y)} transformed: {@code [x' y']}. */
    public double[] transform(double x, double y) {
        return new double[] {x * a + y * c + e, x * b + y * d + f};
    }

    /** How long a unit step along the y axis becomes: the vertical scale, rotation ignored. */
    public double yScale() {
        return Math.hypot(c, d);
    }

    /** How long a unit step along the x axis becomes. */
    public double xScale() {
        return Math.hypot(a, b);
    }
}
