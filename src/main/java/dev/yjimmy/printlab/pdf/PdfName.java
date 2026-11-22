package dev.yjimmy.printlab.pdf;

/**
 * A name, such as {@code /Type}, held without its slash and with {@code #xx} escapes decoded
 * ({@code /A#20B} is {@code A B}). Each byte of the name is one {@code char} (ISO-8859-1), so no
 * byte is lost; names in real files are almost always ASCII.
 */
public record PdfName(String name) implements PdfObject {

    public PdfName {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
    }

    public static PdfName of(String name) {
        return new PdfName(name);
    }

    @Override
    public String toString() {
        return "/" + name;
    }
}
