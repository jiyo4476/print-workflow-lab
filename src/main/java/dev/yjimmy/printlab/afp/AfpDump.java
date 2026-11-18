package dev.yjimmy.printlab.afp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
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
 * <p>Problems don't stop the dump. Each is marked {@code !!}, following the rules of the MO:DCA
 * Reference (AFPC-0004-11):
 * <ul>
 *   <li>an End with no matching Begin, or a Begin never closed;
 *   <li>an End whose name differs from its Begin's (an End name starting {@code FFFF} matches
 *       any Begin name);
 *   <li>a segmented field not followed directly by the rest of its data, a field of the same type;
 *   <li>a TLE that can't be decoded;
 *   <li>bytes that aren't a structured field, which ends the dump, since the next field can't be
 *       found.
 * </ul>
 * The output uses {@code \n} line endings on every platform, so it can be compared with a golden
 * file.
 */
public final class AfpDump {

    /** Begin and End fields start with an 8-byte name token. */
    private static final int NAME_LENGTH = 8;

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
        StructuredField segmented = null;
        line(out, "Offset    Length  Field");
        Iterator<StructuredField> reader = new AfpReader(in);
        try {
            while (reader.hasNext()) {
                StructuredField field = reader.next();
                fields++;
                List<String> notes = new ArrayList<>();
                if (segmented != null && field.typeId() != segmented.typeId()) {
                    notes.add(unfinishedSegments(segmented));
                }
                segmented = field.isSegmented() ? field : null;
                if (field.isEnd()) {
                    notes.addAll(close(open, field));
                }
                StringBuilder text = new StringBuilder(label(field));
                String detail = detail(field);
                if (detail.startsWith("!!")) {
                    problems++;
                }
                if (!detail.isEmpty()) {
                    text.append("  ").append(detail);
                }
                for (String note : notes) {
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
        if (segmented != null) {
            problems++;
            line(out, unfinishedSegments(segmented));
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

    /** MO:DCA requires the rest of a segmented field's data to follow it directly. */
    private static String unfinishedSegments(StructuredField segment) {
        return String.format(Locale.ROOT, "!! the %s at %08X is segmented, but the next field"
                + " isn't the next %s segment", segment.typeName(), segment.offset(),
                segment.typeName());
    }

    /**
     * Pops the Begin that {@code end} closes and returns any problems: none when it closes the
     * innermost one with a matching name. When an inner Begin was never closed but an outer one
     * matches, the unclosed ones are dropped so the rest of the file still indents sensibly.
     */
    private List<String> close(Deque<StructuredField> open, StructuredField end) {
        List<String> problems = new ArrayList<>();
        if (open.isEmpty() || open.peek().matchingEndId() != end.typeId()) {
            problems.add(open.isEmpty()
                    ? "!! no matching Begin"
                    : "!! expected " + FieldType.nameOf(open.peek().matchingEndId()) + " first");
            if (open.stream().noneMatch(b -> b.matchingEndId() == end.typeId())) {
                return problems;
            }
            while (open.peek().matchingEndId() != end.typeId()) {
                open.pop();
            }
        }
        StructuredField begin = open.pop();
        if (!namesMatch(begin.data(), end.data())) {
            problems.add(String.format(Locale.ROOT, "!! name %s doesn't match the %s at %08X (%s)",
                    shownName(end.data()), begin.typeName(), begin.offset(),
                    shownName(begin.data())));
        }
        return problems;
    }

    /**
     * An End's name, if it has one, must equal its Begin's. An End name starting {@code FFFF}
     * matches any Begin ("Name" in "Structured Field Parameters").
     */
    private static boolean namesMatch(byte[] begin, byte[] end) {
        if (end.length < NAME_LENGTH || isWildcard(end)) {
            return true;
        }
        return begin.length >= NAME_LENGTH
                && Arrays.equals(begin, 0, NAME_LENGTH, end, 0, NAME_LENGTH);
    }

    private static boolean isWildcard(byte[] name) {
        return (name[0] & 0xFF) == 0xFF && (name[1] & 0xFF) == 0xFF;
    }

    /** A name for a problem note: the quoted text if it's printable, or "no name". */
    private String shownName(byte[] data) {
        String name = beginName(data);
        return name.isEmpty() ? "no name" : name;
    }

    private static String label(StructuredField field) {
        FieldType type = field.type();
        return type == null ? field.typeName() : type.acronym() + "  " + type.description();
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
        if (data.length < NAME_LENGTH || isWildcard(data)) {
            return "";
        }
        String name = Ebcdic.decode(data, 0, NAME_LENGTH, codePage);
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
