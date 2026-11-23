package dev.yjimmy.printlab.testsupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds small PDF files for tests, the way {@link AfpBytes} builds AFP: a header, objects
 * written as PDF syntax, and cross-reference sections with the offsets worked out.
 *
 * <pre>
 * byte[] pdf = new PdfBytes("1.4")
 *         .object(1, "&lt;&lt; /Type /Catalog /Pages 2 0 R &gt;&gt;")
 *         .object(2, "&lt;&lt; /Type /Pages /Kids [] /Count 0 &gt;&gt;")
 *         .build("/Root 1 0 R");
 * </pre>
 * <ul>
 *   <li>{@link #build} writes a classic table; {@link #buildWithXrefStream} a cross-reference
 *       stream (PDF 1.5) instead, which can list objects put in an {@link #objectStream}.</li>
 *   <li>{@link #update} appends an incremental update: a table listing only the objects written
 *       (or {@link #free freed}) since the last section, with {@code /Prev} pointing back.</li>
 * </ul>
 * Text is written one byte per {@code char} (ISO-8859-1), and streams are not compressed, so a
 * test can see every byte. For an unusual section, write {@link #bytes()} plus your own using
 * {@link #offset}.
 */
public final class PdfBytes {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final TreeMap<Integer, Integer> offsets = new TreeMap<>();
    /** Object number to {stream number, index} for objects inside object streams. */
    private final TreeMap<Integer, int[]> compressed = new TreeMap<>();
    /** Objects written or freed since the last section, for {@link #update}. */
    private final TreeSet<Integer> changed = new TreeSet<>();
    private final TreeSet<Integer> freed = new TreeSet<>();
    private int lastSection = -1;

    /** Starts the file with {@code %PDF-<version>} and a binary comment, as writers do. */
    public PdfBytes(String version) {
        this("", version);
    }

    /** Starts with {@code junk} before the header, as some writers' files do. */
    public PdfBytes(String junk, String version) {
        raw(junk + "%PDF-" + version + "\n%" + (char) 0xE2 + (char) 0xE3 + (char) 0xCF + (char) 0xD3 + "\n");
    }

    /** Appends {@code text} as it is. */
    public PdfBytes raw(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
        return this;
    }

    public PdfBytes raw(byte[] bytes) {
        out.write(bytes, 0, bytes.length);
        return this;
    }

    /** Appends {@code number 0 obj body endobj}. */
    public PdfBytes object(int number, String body) {
        written(number);
        return raw(number + " 0 obj\n" + body + "\nendobj\n");
    }

    /** Appends a stream object; {@code dictionaryEntries} gets {@code /Length} added. */
    public PdfBytes stream(int number, String dictionaryEntries, byte[] data) {
        written(number);
        raw(number + " 0 obj\n<< " + dictionaryEntries + " /Length " + data.length + " >>\nstream\n");
        raw(data);
        return raw("\nendstream\nendobj\n");
    }

    /**
     * Appends object stream {@code number} holding {@code objects} (object number to its text),
     * uncompressed: the header of {@code number offset} pairs, then the objects at {@code /First}.
     */
    public PdfBytes objectStream(int number, Map<Integer, String> objects) {
        StringBuilder header = new StringBuilder();
        StringBuilder body = new StringBuilder();
        int index = 0;
        for (Map.Entry<Integer, String> object : objects.entrySet()) {
            header.append(object.getKey()).append(' ').append(body.length()).append(' ');
            body.append(object.getValue()).append('\n');
            compressed.put(object.getKey(), new int[] {number, index++});
            changed.add(object.getKey());
        }
        String data = header + "\n" + body;
        return stream(number, "/Type /ObjStm /N " + objects.size() + " /First " + (header.length() + 1),
                data.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Lists object {@code number} in {@link #buildWithXrefStream}'s stream as the
     * {@code index}th object of object stream {@code streamNumber}, whatever that stream holds:
     * for a test of a broken object stream written with {@link #stream}.
     */
    public PdfBytes inObjectStream(int number, int streamNumber, int index) {
        offsets.remove(number);
        compressed.put(number, new int[] {streamNumber, index});
        return this;
    }

    /** Marks object {@code number} free in the next {@link #update}. */
    public PdfBytes free(int number) {
        offsets.remove(number);
        compressed.remove(number);
        freed.add(number);
        changed.remove(number);
        return this;
    }

    private void written(int number) {
        offsets.put(number, out.size());
        compressed.remove(number);
        changed.add(number);
        freed.remove(number);
    }

    /** Where object {@code number} starts. */
    public int offset(int number) {
        return offsets.get(number);
    }

    /** The bytes so far. */
    public byte[] bytes() {
        return out.toByteArray();
    }

    /** Where the next byte goes: the offset a section written now would have. */
    public int size() {
        return out.size();
    }

    private int highest() {
        int highest = 0;
        if (!offsets.isEmpty()) {
            highest = Math.max(highest, offsets.lastKey());
        }
        if (!compressed.isEmpty()) {
            highest = Math.max(highest, compressed.lastKey());
        }
        return highest;
    }

    /**
     * The file with one table covering objects 0 to the highest written: those not written are
     * free. The trailer gets {@code /Size} plus {@code trailerEntries}.
     */
    public byte[] build(String trailerEntries) {
        int xref = out.size();
        int size = highest() + 1;
        StringBuilder table = new StringBuilder("xref\n0 " + size + "\n");
        for (int n = 0; n < size; n++) {
            table.append(offsets.containsKey(n) ? entry(offsets.get(n), 0, 'n')
                    : entry(0, n == 0 ? 65535 : 0, 'f'));
        }
        return finish(xref, table + "trailer\n<< /Size " + size + " " + trailerEntries + " >>\n");
    }

    /**
     * Appends an incremental update: a table with one subsection per run of objects written or
     * freed since the last section, and a trailer with {@code /Prev} to it.
     */
    public byte[] update(String trailerEntries) {
        if (lastSection < 0) {
            throw new IllegalStateException("build the first section before an update");
        }
        int xref = out.size();
        TreeSet<Integer> numbers = new TreeSet<>(changed);
        numbers.addAll(freed);
        StringBuilder table = new StringBuilder("xref\n");
        List<Integer> run = new ArrayList<>();
        for (int number : numbers) {
            if (!run.isEmpty() && number != run.get(run.size() - 1) + 1) {
                subsection(table, run);
                run.clear();
            }
            run.add(number);
        }
        if (!run.isEmpty()) {
            subsection(table, run);
        }
        int size = Math.max(highest(), numbers.isEmpty() ? 0 : numbers.last()) + 1;
        return finish(xref, table + "trailer\n<< /Size " + size + " /Prev " + lastSection + " "
                + trailerEntries + " >>\n");
    }

    private void subsection(StringBuilder table, List<Integer> run) {
        table.append(run.get(0)).append(' ').append(run.size()).append('\n');
        for (int number : run) {
            table.append(offsets.containsKey(number) ? entry(offsets.get(number), 0, 'n') : entry(0, 1, 'f'));
        }
    }

    /**
     * The file with a cross-reference stream, object {@code number}, instead of a table: rows of
     * {@code /W [1 4 2]} for objects 0 to {@code number}, uncompressed. Objects in an object
     * stream get type-2 rows. {@code dictionaryEntries} go into its dictionary, which is also the
     * trailer.
     */
    public byte[] buildWithXrefStream(int number, String dictionaryEntries) {
        int xref = out.size();
        offsets.put(number, xref);
        int size = Math.max(highest(), number) + 1;
        byte[] rows = new byte[size * 7];
        for (int n = 0; n < size; n++) {
            int at = n * 7;
            if (offsets.containsKey(n)) {
                row(rows, at, 1, offsets.get(n), 0);
            } else if (compressed.containsKey(n)) {
                row(rows, at, 2, compressed.get(n)[0], compressed.get(n)[1]);
            } else {
                row(rows, at, 0, 0, n == 0 ? 65535 : 0);
            }
        }
        raw(number + " 0 obj\n<< /Type /XRef /Size " + size + " /W [1 4 2] " + dictionaryEntries
                + " /Length " + rows.length + " >>\nstream\n");
        raw(rows);
        raw("\nendstream\nendobj\n");
        return finish(xref, "");
    }

    /** One row of {@code /W [1 4 2]}: a type byte, a 4-byte field and a 2-byte field. */
    public static void row(byte[] rows, int at, int type, int second, int third) {
        rows[at] = (byte) type;
        for (int i = 0; i < 4; i++) {
            rows[at + 1 + i] = (byte) (second >>> 8 * (3 - i));
        }
        rows[at + 5] = (byte) (third >>> 8);
        rows[at + 6] = (byte) third;
    }

    private byte[] finish(int xref, String section) {
        raw(section + "startxref\n" + xref + "\n%%EOF\n");
        lastSection = xref;
        changed.clear();
        freed.clear();
        return bytes();
    }

    /** One 20-byte table entry: {@code 0000000017 00000 n} and CR LF. */
    public static String entry(int offset, int generation, char type) {
        return String.format("%010d %05d %c\r\n", offset, generation, type);
    }
}
