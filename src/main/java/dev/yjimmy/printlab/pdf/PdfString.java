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
     * The bytes as a text string (ISO 32000-2, 7.9.2.2): UTF-16BE after the byte-order mark
     * {@code FE FF}, UTF-8 after {@code EF BB BF} (new in PDF 2.0), and otherwise
     * PDFDocEncoding (Annex D.3). PDFDocEncoding is ISO-8859-1 except for {@code 18-1F} and
     * {@code 80-A0}, which hold accents, typographic quotes, dashes and ligatures instead of
     * control characters; its three undefined codes ({@code 7F}, {@code 9F}, {@code AD}) read as
     * U+FFFD.
     */
    public String text() {
        if (startsWith(0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (startsWith(0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        StringBuilder text = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            text.append(pdfDocEncoding(b & 0xFF));
        }
        return text.toString();
    }

    private boolean startsWith(int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** PDFDocEncoding codes {@code 18-1F}, then {@code 80-A0}, where it differs from ISO-8859-1. */
    private static final String PDF_DOC_18_TO_1F =
            "\u02D8\u02C7\u02C6\u02D9\u02DD\u02DB\u02DA\u02DC";
    private static final String PDF_DOC_80_TO_A0 =
            "\u2022\u2020\u2021\u2026\u2014\u2013\u0192\u2044\u2039\u203A\u2212\u2030\u201E\u201C\u201D\u2018"
            + "\u2019\u201A\u2122\uFB01\uFB02\u0141\u0152\u0160\u0178\u017D\u0131\u0142\u0153\u0161\u017E\uFFFD"
            + "\u20AC";

    static char pdfDocEncoding(int code) {
        if (code >= 0x18 && code <= 0x1F) {
            return PDF_DOC_18_TO_1F.charAt(code - 0x18);
        }
        if (code >= 0x80 && code <= 0xA0) {
            return PDF_DOC_80_TO_A0.charAt(code - 0x80);
        }
        if (code == 0x7F || code == 0xAD) {
            return '\uFFFD';
        }
        return (char) code;
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
