package dev.yjimmy.printlab.pdf;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Splits PDF bytes into tokens (ISO 32000-1, 7.2 and 7.3): numbers, names, literal and hex
 * strings, the delimiters {@code [ ] << >>}, and keywords ({@code obj}, {@code R},
 * {@code true}, ...). Comments ({@code %} to the end of the line) and white space are skipped.
 *
 * <p>The lexer reads a byte array and can {@link #seek(int)} anywhere in it, because a PDF is
 * read by jumping to the offsets in its cross-reference table, not front to back. Errors are
 * {@link PdfFormatException}s with the byte offset of the token.
 */
public final class PdfLexer {

    /** What a token is. */
    public enum Kind {
        /** A {@link PdfNumber}, a {@link PdfName} or a {@link PdfString}: see {@link Token#value()}. */
        VALUE,
        ARRAY_START,
        ARRAY_END,
        DICTIONARY_START,
        DICTIONARY_END,
        /** A run of regular characters that isn't a number: {@code obj}, {@code R}, {@code true}. */
        KEYWORD,
        END_OF_FILE
    }

    /**
     * One token. {@code value} is set for {@link Kind#VALUE}; {@code keyword} for
     * {@link Kind#KEYWORD}. {@code offset} is where its first byte is.
     */
    public record Token(Kind kind, int offset, PdfObject value, String keyword) {

        public boolean isKeyword(String word) {
            return kind == Kind.KEYWORD && keyword.equals(word);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case VALUE -> value.toString();
                case KEYWORD -> printable(keyword);
                case ARRAY_START -> "[";
                case ARRAY_END -> "]";
                case DICTIONARY_START -> "<<";
                case DICTIONARY_END -> ">>";
                case END_OF_FILE -> "end of file";
            };
        }
    }

    /**
     * A keyword as it can be shown: a keyword is any run of regular characters, which includes
     * control bytes and bytes past 0x7E, so those (and {@code #}) are written {@code #xx}, the way
     * {@link PdfName#toString()} writes names. A file's bytes never reach a terminal raw.
     */
    public static String printable(String keyword) {
        StringBuilder text = new StringBuilder(keyword.length());
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c < 0x21 || c > 0x7E || c == '#') {
                text.append(String.format("#%02X", c & 0xFF));
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");

    private final byte[] data;
    private int pos;

    public PdfLexer(byte[] data) {
        this(data, 0);
    }

    public PdfLexer(byte[] data, int offset) {
        this.data = data;
        seek(offset);
    }

    /** Where the next token is read from. */
    public int position() {
        return pos;
    }

    public void seek(int offset) {
        if (offset < 0 || offset > data.length) {
            throw new PdfFormatException(offset, "offset is outside the file (" + data.length + " bytes)");
        }
        pos = offset;
    }

    /** The bytes being read (not copied). */
    byte[] data() {
        return data;
    }

    /** Space, tab, CR, LF, form feed and NUL (Table 1). */
    static boolean isWhiteSpace(int b) {
        return b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09 || b == 0x0C || b == 0x00;
    }

    /** {@code ( ) < > [ ] { } / %} (Table 2). */
    static boolean isDelimiter(int b) {
        return switch (b) {
            case '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true;
            default -> false;
        };
    }

    private static boolean isRegular(int b) {
        return !isWhiteSpace(b) && !isDelimiter(b);
    }

    /** Skips white space and comments. */
    public void skipWhiteSpace() {
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            if (isWhiteSpace(b)) {
                pos++;
            } else if (b == '%') {
                while (pos < data.length && data[pos] != '\n' && data[pos] != '\r') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    /** Reads the next token, or {@link Kind#END_OF_FILE}. */
    public Token next() {
        skipWhiteSpace();
        int start = pos;
        if (pos >= data.length) {
            return new Token(Kind.END_OF_FILE, start, null, null);
        }
        int b = data[pos] & 0xFF;
        switch (b) {
            case '[' -> {
                pos++;
                return new Token(Kind.ARRAY_START, start, null, null);
            }
            case ']' -> {
                pos++;
                return new Token(Kind.ARRAY_END, start, null, null);
            }
            case '<' -> {
                if (peek(1) == '<') {
                    pos += 2;
                    return new Token(Kind.DICTIONARY_START, start, null, null);
                }
                return value(start, hexString());
            }
            case '>' -> {
                if (peek(1) == '>') {
                    pos += 2;
                    return new Token(Kind.DICTIONARY_END, start, null, null);
                }
                throw new PdfFormatException(start, "'>' outside a hex string (expected '>>')");
            }
            case '(' -> {
                return value(start, literalString());
            }
            case ')' -> throw new PdfFormatException(start, "')' outside a string");
            case '/' -> {
                return value(start, name());
            }
            case '{', '}' -> {
                // Only PostScript calculator functions use braces; the parser rejects them.
                pos++;
                return new Token(Kind.KEYWORD, start, null, String.valueOf((char) b));
            }
            default -> {
                return word(start);
            }
        }
    }

    private int peek(int ahead) {
        int at = pos + ahead;
        return at < data.length ? data[at] & 0xFF : -1;
    }

    private static Token value(int start, PdfObject value) {
        return new Token(Kind.VALUE, start, value, null);
    }

    /** A number or a keyword: a run of regular characters. */
    private Token word(int start) {
        while (pos < data.length && isRegular(data[pos] & 0xFF)) {
            pos++;
        }
        String text = new String(data, start, pos - start, StandardCharsets.ISO_8859_1);
        char first = text.charAt(0);
        boolean numeric = first == '+' || first == '-' || first == '.' || (first >= '0' && first <= '9');
        if (!numeric) {
            return new Token(Kind.KEYWORD, start, null, text);
        }
        if (!NUMBER.matcher(text).matches()) {
            throw new PdfFormatException(start, "malformed number '" + text + "'");
        }
        String digits = text.startsWith("+") ? text.substring(1) : text;
        return value(start, new PdfNumber(new BigDecimal(digits), text.indexOf('.') >= 0));
    }

    /** After {@code /}: regular characters, with {@code #xx} as one byte. */
    private PdfName name() {
        pos++;
        StringBuilder name = new StringBuilder();
        while (pos < data.length && isRegular(data[pos] & 0xFF)) {
            int b = data[pos] & 0xFF;
            if (b == '#') {
                int high = hexValue(peek(1));
                int low = hexValue(peek(2));
                if (high < 0 || low < 0) {
                    throw new PdfFormatException(pos, "'#' in a name must be followed by two hex digits");
                }
                name.append((char) (high << 4 | low));
                pos += 3;
            } else {
                name.append((char) b);
                pos++;
            }
        }
        // "/" on its own is the empty name, which is valid (7.3.5).
        return new PdfName(name.toString());
    }

    private static int hexValue(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        return -1;
    }

    /**
     * {@code <48 65 6C>}: hex digits, white space ignored. An odd count is padded with a final
     * {@code 0} (7.3.4.3), so {@code <901FA>} is {@code 90 1F A0}.
     */
    private PdfString hexString() {
        int start = pos;
        pos++;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int high = -1;
        while (true) {
            if (pos >= data.length) {
                throw new PdfFormatException(start, "hex string is not closed with '>'");
            }
            int b = data[pos++] & 0xFF;
            if (b == '>') {
                break;
            }
            if (isWhiteSpace(b)) {
                continue;
            }
            int digit = hexValue(b);
            if (digit < 0) {
                throw new PdfFormatException(pos - 1,
                        "'" + (char) b + "' is not a hex digit, in the hex string at offset " + start);
            }
            if (high < 0) {
                high = digit;
            } else {
                out.write(high << 4 | digit);
                high = -1;
            }
        }
        if (high >= 0) {
            out.write(high << 4);
        }
        return new PdfString(out.toByteArray());
    }

    /**
     * {@code (text)}: balanced parentheses need no escape; {@code \n \r \t \b \f \( \) \\},
     * {@code \ddd} (one to three octal digits), and a backslash at the end of a line (the string
     * continues on the next line). Any other escaped character stands for itself. An unescaped end
     * of line (CR, LF or CR LF) is one LF (7.3.4.2).
     */
    private PdfString literalString() {
        int start = pos;
        pos++;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int depth = 1;
        while (true) {
            if (pos >= data.length) {
                throw new PdfFormatException(start, "literal string is not closed with ')'");
            }
            int b = data[pos++] & 0xFF;
            switch (b) {
                case '(' -> {
                    depth++;
                    out.write(b);
                }
                case ')' -> {
                    if (--depth == 0) {
                        return new PdfString(out.toByteArray());
                    }
                    out.write(b);
                }
                case '\r' -> {
                    if (peek(0) == '\n') {
                        pos++;
                    }
                    out.write('\n');
                }
                case '\\' -> escape(out, start);
                default -> out.write(b);
            }
        }
    }

    private void escape(ByteArrayOutputStream out, int stringStart) {
        if (pos >= data.length) {
            throw new PdfFormatException(stringStart, "literal string is not closed with ')'");
        }
        int b = data[pos++] & 0xFF;
        switch (b) {
            case 'n' -> out.write('\n');
            case 'r' -> out.write('\r');
            case 't' -> out.write('\t');
            case 'b' -> out.write('\b');
            case 'f' -> out.write('\f');
            case '\r' -> {
                // Line continuation: neither the backslash nor the end of line is in the string.
                if (peek(0) == '\n') {
                    pos++;
                }
            }
            case '\n' -> {
                // Line continuation.
            }
            default -> {
                if (b >= '0' && b <= '7') {
                    int value = b - '0';
                    for (int i = 0; i < 2 && peek(0) >= '0' && peek(0) <= '7'; i++) {
                        value = value * 8 + (data[pos++] - '0');
                    }
                    out.write(value & 0xFF); // high-order overflow is ignored (7.3.4.2)
                } else {
                    out.write(b); // \( \) \\ and any other character: the character itself
                }
            }
        }
    }
}
