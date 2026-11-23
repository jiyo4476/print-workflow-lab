package dev.yjimmy.printlab.testsupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds small PDF files for tests, the way {@link AfpBytes} builds AFP: a header, objects
 * written as PDF syntax, and a cross-reference table with the offsets worked out.
 *
 * <pre>
 * byte[] pdf = new PdfBytes("1.4")
 *         .object(1, "&lt;&lt; /Type /Catalog /Pages 2 0 R &gt;&gt;")
 *         .object(2, "&lt;&lt; /Type /Pages /Kids [] /Count 0 &gt;&gt;")
 *         .build("/Root 1 0 R");
 * </pre>
 * Text is written one byte per {@code char} (ISO-8859-1). For an unusual table, write
 * {@link #bytes()} plus your own {@code xref} section using {@link #offset}.
 */
public final class PdfBytes {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<Integer, Integer> offsets = new TreeMap<>();

    /** Starts the file with {@code %PDF-<version>} and a binary comment, as writers do. */
    public PdfBytes(String version) {
        this("", version);
    }

    /** Starts with {@code junk} before the header, as some writers' files do. */
    public PdfBytes(String junk, String version) {
        raw(junk + "%PDF-" + version + "\n%\u00E2\u00E3\u00CF\u00D3\n");
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
        offsets.put(number, out.size());
        return raw(number + " 0 obj\n" + body + "\nendobj\n");
    }

    /** Appends a stream object; {@code dictionaryEntries} gets {@code /Length} added. */
    public PdfBytes stream(int number, String dictionaryEntries, byte[] data) {
        offsets.put(number, out.size());
        raw(number + " 0 obj\n<< " + dictionaryEntries + " /Length " + data.length + " >>\nstream\n");
        raw(data);
        return raw("\nendstream\nendobj\n");
    }

    /** Where object {@code number} starts. */
    public int offset(int number) {
        return offsets.get(number);
    }

    /** The bytes so far. */
    public byte[] bytes() {
        return out.toByteArray();
    }

    /** Where the next byte goes: the offset a table written now would have. */
    public int size() {
        return out.size();
    }

    /**
     * The file with one table covering objects 0 to the highest written: those not written are
     * free. The trailer gets {@code /Size} plus {@code trailerEntries}.
     */
    public byte[] build(String trailerEntries) {
        int xref = out.size();
        int size = offsets.isEmpty() ? 1 : ((TreeMap<Integer, Integer>) offsets).lastKey() + 1;
        StringBuilder table = new StringBuilder("xref\n0 " + size + "\n");
        for (int n = 0; n < size; n++) {
            table.append(offsets.containsKey(n) ? entry(offsets.get(n), 0, 'n')
                    : entry(0, n == 0 ? 65535 : 0, 'f'));
        }
        table.append("trailer\n<< /Size ").append(size).append(' ').append(trailerEntries)
                .append(" >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        raw(table.toString());
        return bytes();
    }

    /** One 20-byte table entry: {@code 0000000017 00000 n} and CR LF. */
    public static String entry(int offset, int generation, char type) {
        return String.format("%010d %05d %c\r\n", offset, generation, type);
    }
}
