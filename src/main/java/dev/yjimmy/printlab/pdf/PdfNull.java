package dev.yjimmy.printlab.pdf;

/** The {@code null} object. A dictionary entry whose value is null is the same as no entry. */
public enum PdfNull implements PdfObject {
    INSTANCE;

    @Override
    public String toString() {
        return "null";
    }
}
