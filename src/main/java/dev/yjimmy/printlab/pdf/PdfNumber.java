package dev.yjimmy.printlab.pdf;

import java.math.BigDecimal;

/**
 * An integer ({@code 42}, {@code -3}, {@code +7}) or a real ({@code 3.14}, {@code -.5},
 * {@code 4.}). PDF has no exponent form. {@code real} records which one was written: {@code 4.}
 * is a real with the value 4.
 */
public record PdfNumber(BigDecimal value, boolean real) implements PdfObject {

    public PdfNumber {
        if (value == null) {
            throw new IllegalArgumentException("value is null");
        }
    }

    public static PdfNumber of(long value) {
        return new PdfNumber(BigDecimal.valueOf(value), false);
    }

    /** True for an integer that fits in an {@code int}. */
    public boolean isInt() {
        if (real) {
            return false;
        }
        try {
            value.intValueExact();
            return true;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    /**
     * The value as an {@code int}.
     *
     * @throws ArithmeticException if it's a real, or an integer too big for an {@code int}
     */
    public int intValue() {
        if (real) {
            throw new ArithmeticException("not an integer: " + this);
        }
        return value.intValueExact();
    }

    public double doubleValue() {
        return value.doubleValue();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
