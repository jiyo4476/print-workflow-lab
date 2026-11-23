package dev.yjimmy.printlab.pdf;

import java.nio.charset.StandardCharsets;

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

    /**
     * The name as PDF syntax: {@code /A#20B} for {@code A B}. Bytes outside {@code 21-7E},
     * delimiters and {@code #} are written {@code #xx} (7.3.5), so a name read from a file can't
     * put control characters in a dump.
     */
    @Override
    public String toString() {
        // Names from a file are one byte per char; a char past 0xFF (from code) is written as UTF-8.
        boolean bytesOnly = name.chars().allMatch(c -> c <= 0xFF);
        byte[] bytes = name.getBytes(bytesOnly ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder("/");
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c < 0x21 || c > 0x7E || c == '#' || PdfLexer.isDelimiter(c)) {
                text.append(String.format("#%02X", c));
            } else {
                text.append((char) c);
            }
        }
        return text.toString();
    }
}
