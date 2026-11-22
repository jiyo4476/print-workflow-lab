package dev.yjimmy.printlab.pdf;

/** {@code true} or {@code false}. */
public record PdfBoolean(boolean value) implements PdfObject {

    public static final PdfBoolean TRUE = new PdfBoolean(true);
    public static final PdfBoolean FALSE = new PdfBoolean(false);

    public static PdfBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
