package dev.yjimmy.printlab.afp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;

/**
 * Prints an AFP file's structure, one line per structured field:
 * <pre>
 * Offset    Length  Field
 * 00000000      16  BDT  Begin Document  "STMTRUN "
 * 00000011      16    BNG  Begin Named Page Group  "G0000001"
 * 00000022      38      TLE  Tag Logical Element  accountNumber = "1234567890"
 * </pre>
 * Offsets are hex, lengths are the field's length value (which doesn't count the {@code 0x5A}).
 * Fields are indented between each Begin and its End.
 *
 * <p>Problems don't stop the dump. Each gets a line starting {@code !!}: an End with no matching
 * Begin, a Begin never closed, a TLE that can't be decoded, or bytes that aren't a structured
 * field (which does end the dump, since the next field can't be found). The output uses
 * {@code \n} line endings on every platform, so it can be compared with a golden file.
 */
public final class AfpDump {

    private final Charset codePage;

    public AfpDump() {
        this(Ebcdic.IBM037);
    }

    public AfpDump(Charset codePage) {
        this.codePage = codePage;
    }

    /** Dumps {@code in} to {@code out} and returns the number of problems found. */
    public int dump(InputStream in, Appendable out) {
        Deque<StructuredField> open = new ArrayDeque<>();
        int fields = 0;
        int problems = 0;
        line(out, "Offset    Length  Field");
        Iterator<StructuredField> reader = new AfpReader(in);
        try {
            while (reader.hasNext()) {
                StructuredField field = reader.next();
                fields++;
                String note = "";
                if (field.isEnd()) {
                    note = close(open, field);
                }
                StringBuilder text = new StringBuilder(label(field));
                String detail = detail(field);
                if (detail.startsWith("!!")) {
                    problems++;
                }
                if (!detail.isEmpty()) {
                    text.append("  ").append(detail);
                }
                if (!note.isEmpty()) {
                    problems++;
                    text.append("  ").append(note);
                }
                line(out, String.format(Locale.ROOT, "%08X  %6d  %s%s",
                        field.offset(), field.length(), "  ".repeat(open.size()), text));
                if (field.isBegin()) {
                    open.push(field);
                }
            }
        } catch (AfpFormatException e) {
            problems++;
            line(out, "!! " + e.getMessage());
        }
        while (!open.isEmpty()) {
            StructuredField begin = open.pop();
            problems++;
            line(out, String.format(Locale.ROOT, "!! %s at %08X has no %s before the end of the"
                    + " file", begin.typeName(), begin.offset(),
                    FieldType.nameOf(begin.matchingEndId())));
        }
        line(out, fields + " fields, " + problems + (problems == 1 ? " problem" : " problems"));
        return problems;
    }

    /**
     * Pops the Begin that {@code end} closes. Returns "" when it closes the innermost one, or a
     * problem note. When an inner Begin was never closed but an outer one matches, the unclosed
     * ones are dropped so the rest of the file still indents sensibly.
     */
    private static String close(Deque<StructuredField> open, StructuredField end) {
        if (!open.isEmpty() && open.peek().matchingEndId() == end.typeId()) {
            open.pop();
            return "";
        }
        String expected = open.isEmpty() ? null : FieldType.nameOf(open.peek().matchingEndId());
        boolean outerMatches = open.stream().anyMatch(b -> b.matchingEndId() == end.typeId());
        if (outerMatches) {
            while (open.pop().matchingEndId() != end.typeId()) {
                // drop the unclosed inner Begins
            }
        }
        return expected == null
                ? "!! no matching Begin"
                : "!! expected " + expected + " first";
    }

    private static String label(StructuredField field) {
        FieldType type = field.type();
        return type == null ? field.typeName() : type.name() + "  " + type.description();
    }

    /** A TLE's name and value, a Begin field's 8-character name, or "". */
    private String detail(StructuredField field) {
        if (field.type() == FieldType.TLE) {
            try {
                Tle tle = TleDecoder.decode(field, codePage);
                return tle.name() + " = " + quote(tle.value());
            } catch (IllegalArgumentException e) {
                return "!! bad TLE: " + e.getMessage();
            }
        }
        if (field.isBegin()) {
            return beginName(field.data());
        }
        return "";
    }

    /**
     * Begin fields start with an 8-byte name token. {@code FFFF} means "no name"; anything that
     * doesn't decode to printable text isn't shown.
     */
    private String beginName(byte[] data) {
        if (data.length < 8 || ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFF)) {
            return "";
        }
        String name = Ebcdic.decode(data, 0, 8, codePage);
        return name.chars().allMatch(c -> c >= 0x20 && c < 0x7F) ? quote(name) : "";
    }

    /** Quotes text and escapes anything that isn't printable ASCII, so the output stays plain. */
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7F) {
                quoted.append(c);
            } else {
                quoted.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            }
        }
        return quoted.append('"').toString();
    }

    private static void line(Appendable out, String text) {
        try {
            out.append(text).append('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
