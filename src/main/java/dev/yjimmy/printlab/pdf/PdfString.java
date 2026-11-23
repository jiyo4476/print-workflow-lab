package dev.yjimmy.printlab.pdf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * A string, written {@code (literal)} or {@code <48656C6C6F>} (hex). PDF strings are bytes, not
 * text: the lexer decodes escapes, so {@link #bytes()} is the same however it was written.
 */
public record PdfString(byte[] bytes) implements PdfObject {

    public PdfString {
        bytes = bytes.clone();
    }

    /** One byte per character (ISO-8859-1): {@code PdfString.of("Hello")}. */
    public static PdfString of(String text) {
        return new PdfString(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * The bytes as text: UTF-16BE when they start with the byte-order mark {@code FE FF}, as a
     * text string may (ISO 32000-1, 7.9.2.2); otherwise one {@code char} per byte. That's close
     * to PDFDocEncoding but not exact: the two differ in {@code 18-1F} and {@code 80-9F}.
     *
     * <p>Not yet: PDF 2.0 (ISO 32000-2, 7.9.2.2) also allows UTF-8 text strings, marked by
     * {@code EF BB BF}; those read here as one char per byte. PR 7b adds them, since its dump is
     * the first thing to show string text (PRD-07, "PDF versions").
     */
    public String text() {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PdfString s && Arrays.equals(bytes, s.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** {@code (text)} when every byte is printable ASCII, else {@code <hex>}. */
    @Override
    public String toString() {
        for (byte b : bytes) {
            if (b < 0x20 || b > 0x7E) {
                return "<" + HexFormat.of().withUpperCase().formatHex(bytes) + ">";
            }
        }
        return "(" + new String(bytes, StandardCharsets.US_ASCII)
                .replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")";
    }
}
