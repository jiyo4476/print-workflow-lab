package dev.yjimmy.printlab.afp;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Reads a MO:DCA (AFP) file one structured field at a time. Only the current field is held in
 * memory, so files of any size can be read. See {@link StructuredField} for the byte layout.
 *
 * <p>The reader expects the usual AFP file form: each MO:DCA structured field preceded by a
 * {@code 0x5A} byte. It checks what MO:DCA defines for every field (length 8-32,767, a valid
 * extension and padding) and strips padding, but returns segmented fields one at a time, as they
 * appear. Bad input throws
 * {@link AfpFormatException} with the byte offset, after which {@link #hasNext()} is false.
 * I/O errors are wrapped in {@link UncheckedIOException}, since {@link Iterator} can't throw
 * checked exceptions.
 *
 * <p>What sits between the fields depends on the tool that wrote the file; {@link AfpFraming}
 * names the forms this reader accepts. The default, {@link AfpFraming#STRICT}, allows nothing
 * between fields. With {@link AfpFraming#RDW} or {@link AfpFraming#BDW} each field must fill its
 * record exactly, and offsets in errors are file offsets, pointing at the field's {@code 0x5A}.
 */
public final class AfpReader implements Iterator<StructuredField>, Closeable {

    /**
     * The byte before every structured field in an AFP file. It's a carriage-control character
     * that platforms add around MO:DCA records, not part of MO:DCA itself.
     */
    public static final int INTRODUCER = 0x5A;
    /** The Structured Field Introducer: length 2, ID 3, flags 1, reserved 2. */
    public static final int HEADER_LENGTH = 8;
    /** The largest SFLength MO:DCA allows. */
    public static final int MAX_LENGTH = 32_767;

    /** A record or block descriptor word: a 2-byte length (counting the word) and two zero bytes. */
    private static final int DESCRIPTOR_LENGTH = 4;
    /** The shortest record: its descriptor word, the 0x5A and a field header. */
    private static final int MIN_RECORD = DESCRIPTOR_LENGTH + 1 + HEADER_LENGTH;
    /** How much of the file {@link AfpFraming#AUTO} and the wrong-file hints look at. */
    private static final int SNIFF_LENGTH = 32;

    private final InputStream in;
    private final AfpFraming requested;
    /** What was asked for, or what {@link AfpFraming#AUTO} found; null until the first read. */
    private AfpFraming framing;
    /** Where field bytes come from: {@link #in}, or the record being parsed. */
    private InputStream source;
    private boolean inRecord;
    /** File offset where the current block ends (BDW framing). */
    private long blockEnd;
    private long position;
    /** How many fields have been returned; zero padding only follows a field. */
    private long fieldsRead;
    private StructuredField next;
    private boolean done;

    public AfpReader(InputStream in) {
        this(in, AfpFraming.STRICT);
    }

    public AfpReader(InputStream in, AfpFraming framing) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
        this.source = this.in;
        this.requested = framing;
    }

    /** Reads every field. Convenient for small inputs; use the iterator for big files. */
    public static List<StructuredField> readAll(InputStream in) {
        return readAll(in, AfpFraming.STRICT);
    }

    /** Reads every field of a file with the given framing. */
    public static List<StructuredField> readAll(InputStream in, AfpFraming framing) {
        List<StructuredField> fields = new ArrayList<>();
        AfpReader reader = new AfpReader(in, framing);
        reader.forEachRemaining(fields::add);
        return fields;
    }

    @Override
    public boolean hasNext() {
        if (next == null && !done) {
            try {
                next = readField();
                if (next != null) {
                    fieldsRead++;
                }
            } catch (RuntimeException e) {
                done = true;
                throw e;
            }
        }
        return next != null;
    }

    @Override
    public StructuredField next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        StructuredField field = next;
        next = null;
        return field;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private StructuredField readField() {
        if (framing == null) {
            framing = requested == AfpFraming.AUTO ? detect() : requested;
        }
        switch (framing) {
            case PADDED -> {
                long at;
                int first;
                do {
                    at = position;
                    first = read();
                } while (first == 0x0D || first == 0x0A);
                if (first == 0x00) {
                    return endPadding(at);
                }
                return field(first, at);
            }
            case RDW, BDW -> {
                return record();
            }
            default -> {
                long at = position;
                return field(read(), at);
            }
        }
    }

    /**
     * Zero bytes where a field should start. They're padding only at the very end of the file;
     * anywhere else they're what a field with too short a length leaves behind, so they fail.
     */
    private StructuredField endPadding(long start) {
        int next;
        do {
            next = read();
        } while (next == 0x00);
        if (next >= 0) {
            throw new AfpFormatException(start, String.format("expected 0x5A before a structured"
                    + " field but found 0x00. Zero bytes are only skipped as padding at the end of"
                    + " the file, and 0x%02X follows them here, so a field's length may be too"
                    + " short.", next));
        }
        if (fieldsRead == 0) {
            throw new AfpFormatException(start, "the file holds nothing but zero bytes, which are"
                    + " only skipped as padding after the last field");
        }
        done = true;
        return null;
    }

    /**
     * What {@link AfpFraming#AUTO} means for this file, from its first bytes: {@code PADDED} if it
     * starts like a field and has line endings before it or right after the first field,
     * {@code STRICT} if it starts like a field and has none, {@code BDW} or {@code RDW} if it starts
     * with a descriptor word and then the {@code 0x5A} of a field; otherwise it fails.
     */
    private AfpFraming detect() {
        byte[] head = peek(MAX_LENGTH + 2);
        if (head.length == 0) {
            return AfpFraming.STRICT;
        }
        int lineEndings = 0;
        while (lineEndings < head.length
                && (head[lineEndings] == 0x0D || head[lineEndings] == 0x0A)) {
            lineEndings++;
        }
        // A field starts 0x5A, length, then 0xD3, the first byte of every field's type. An RDW or
        // BDW file can start with 0x5A too (a length of 0x5A00 and up), but its fourth byte is 0.
        if (lineEndings < head.length && unsigned(head, lineEndings, 1) == INTRODUCER
                && (head.length < lineEndings + 4 || unsigned(head, lineEndings + 3, 1) == 0xD3)) {
            if (lineEndings > 0) {
                return AfpFraming.PADDED;
            }
            int after = 1 + (head.length >= 3 ? unsigned(head, 1, 2) : 0);
            boolean lineEndingAfterFirstField = after < head.length
                    && (head[after] == 0x0D || head[after] == 0x0A);
            return lineEndingAfterFirstField ? AfpFraming.PADDED : AfpFraming.STRICT;
        }
        if (isDescriptor(head, 0) && isDescriptor(head, DESCRIPTOR_LENGTH) && head.length > 8
                && unsigned(head, 8, 1) == INTRODUCER) {
            return AfpFraming.BDW;
        }
        if (isDescriptor(head, 0) && head.length > 4 && unsigned(head, 4, 1) == INTRODUCER) {
            return AfpFraming.RDW;
        }
        byte[] start = Arrays.copyOf(head, Math.min(head.length, SNIFF_LENGTH));
        if (isDescriptor(head, 0)) {
            throw new AfpFormatException(0, "can't tell how this file is framed: it starts like a"
                    + " record or block descriptor word (a length, then two zero bytes), but what"
                    + " follows is neither the 0x5A of a field (RDW) nor a second descriptor word"
                    + " and a 0x5A (BDW).");
        }
        throw new AfpFormatException(0, "can't tell how this file is framed: it doesn't start with a"
                + " structured field (0x5A), a record descriptor word or a block descriptor word. "
                + AfpSniffer.hint(start, false));
    }

    private static boolean isDescriptor(byte[] bytes, int at) {
        return bytes.length >= at + DESCRIPTOR_LENGTH && bytes[at + 2] == 0 && bytes[at + 3] == 0
                && unsigned(bytes, at, 2) >= DESCRIPTOR_LENGTH;
    }

    /** The next {@code count} bytes (fewer at the end of the file), left to be read again. */
    private byte[] peek(int count) {
        try {
            in.mark(count);
            byte[] bytes = in.readNBytes(count);
            in.reset();
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One field in a record ({@link AfpFraming#RDW}, {@link AfpFraming#BDW}): the record is read
     * whole, then parsed as a field, which must fill it.
     */
    private StructuredField record() {
        if (framing == AfpFraming.BDW && position >= blockEnd) {
            long blockStart = position;
            int blockLength = descriptor("block descriptor word (BDW)", blockStart);
            if (blockLength < 0) {
                done = true;
                return null;
            }
            if (blockLength < DESCRIPTOR_LENGTH + MIN_RECORD) {
                throw new AfpFormatException(blockStart, "block length " + blockLength
                        + " is too short to hold a record (" + (DESCRIPTOR_LENGTH + MIN_RECORD)
                        + " bytes at least, counting the block descriptor word)");
            }
            blockEnd = blockStart + blockLength;
        }
        long start = position;
        int length = descriptor("record descriptor word (RDW)", start);
        if (length < 0) {
            if (framing == AfpFraming.BDW) {
                throw new AfpFormatException(start, "truncated block: the file ends "
                        + (blockEnd - start) + " byte(s) before the end the block's length gives");
            }
            done = true;
            return null;
        }
        if (length < MIN_RECORD) {
            throw new AfpFormatException(start, "record length " + length + " is too short for a"
                    + " structured field (" + MIN_RECORD + " bytes at least, counting the record"
                    + " descriptor word)");
        }
        if (framing == AfpFraming.BDW && start + length > blockEnd) {
            throw new AfpFormatException(start, "the record's length (" + length + ") runs "
                    + (start + length - blockEnd) + " byte(s) past the end of its block");
        }
        byte[] record = readExact(length - DESCRIPTOR_LENGTH);
        if (record.length < length - DESCRIPTOR_LENGTH) {
            throw new AfpFormatException(start, "truncated record: its length says " + length
                    + " bytes, but the file ends after " + (DESCRIPTOR_LENGTH + record.length));
        }
        long end = position;
        ByteArrayInputStream recordBytes = new ByteArrayInputStream(record);
        source = recordBytes;
        inRecord = true;
        position = start + DESCRIPTOR_LENGTH;
        try {
            long at = position;
            StructuredField field = field(read(), at);
            int left = recordBytes.available();
            if (left > 0) {
                throw new AfpFormatException(at, left + " byte(s) of the record follow its"
                        + " structured field; each record must hold exactly one");
            }
            return field;
        } finally {
            source = in;
            inRecord = false;
            position = end;
        }
    }

    /**
     * Reads a record or block descriptor word and returns its length, or -1 at the end of the
     * file (before its first byte). Fails if the word is cut short or doesn't end in two zeros.
     */
    private int descriptor(String what, long start) {
        byte[] word = readExact(DESCRIPTOR_LENGTH);
        if (word.length == 0) {
            return -1;
        }
        if (word.length < DESCRIPTOR_LENGTH) {
            throw new AfpFormatException(start, "truncated " + what + ": the file ends "
                    + word.length + " byte(s) into its " + DESCRIPTOR_LENGTH);
        }
        if (word[2] >= 1 && word[2] <= 3 && word[3] == 0) {
            // A spanned record (VBS) is cut into segments, each led by a segment descriptor word:
            // a length, then a segment code of 1 (first), 2 (last) or 3 (middle), then 0.
            throw new AfpFormatException(start, what + " looks like the segment descriptor word of"
                    + " a spanned (VBS) record, which this reader doesn't support");
        }
        if (word[2] != 0 || word[3] != 0) {
            throw new AfpFormatException(start, what + " should end in two zero bytes, but found 0x"
                    + String.format("%02X%02X", word[2], word[3]) + ". Is the file really framed"
                    + " this way? The framings are strict, padded, rdw, bdw and auto.");
        }
        return unsigned(word, 0, 2);
    }

    /** The field whose first byte ({@code first}, read at {@code start}) has been read. */
    private StructuredField field(int first, long start) {
        if (first < 0) {
            done = true;
            return null;
        }
        if (first != INTRODUCER) {
            throw new AfpFormatException(start, introducerMessage(first, start));
        }
        byte[] header = readFully(HEADER_LENGTH, start, -1);
        int length = unsigned(header, 0, 2);
        if (length < HEADER_LENGTH) {
            throw new AfpFormatException(start, "length " + length
                    + " is less than the " + HEADER_LENGTH + "-byte header it must include");
        }
        if (length > MAX_LENGTH) {
            throw new AfpFormatException(start, "length " + length
                    + " is more than the MO:DCA maximum of " + MAX_LENGTH);
        }
        int typeId = unsigned(header, 2, 3);
        int flags = unsigned(header, 5, 1);
        int reserved = unsigned(header, 6, 2);
        byte[] body = readFully(length - HEADER_LENGTH, start, length);

        byte[] extension = new byte[0];
        byte[] data = body;
        if ((flags & StructuredField.FLAG_EXTENSION) != 0) {
            int extensionLength = body.length == 0 ? 0 : body[0] & 0xFF;
            if (extensionLength < 1 || extensionLength > body.length) {
                throw new AfpFormatException(start, "the extension flag is set but the extension"
                        + " length (" + extensionLength + ") doesn't fit in the field");
            }
            extension = Arrays.copyOfRange(body, 1, extensionLength);
            data = Arrays.copyOfRange(body, extensionLength, body.length);
        }
        if ((flags & StructuredField.FLAG_PADDING) != 0) {
            data = Arrays.copyOf(data, data.length - paddingLength(data, start));
        }
        return new StructuredField(start, length, typeId, flags, reserved, extension, data);
    }

    /**
     * Why {@code first} isn't the 0x5A that starts a field. At the start of the file, the bytes
     * after it are used to say what the file looks like instead.
     */
    private String introducerMessage(int first, long start) {
        String found = String.format("expected 0x5A before a structured field but found 0x%02X",
                first);
        if (inRecord) {
            return found + ", at the start of a record's field";
        }
        // Only a file read as strict can be advised to try another framing; one that was asked to
        // be read as padded, rdw, bdw or auto already has.
        boolean advise = framing == AfpFraming.STRICT && requested != AfpFraming.AUTO;
        String advice = advise ? " If the file has record headers (RDW or BDW) or line endings"
                + " between fields, set the framing: padded, rdw, bdw or auto." : "";
        if (start != 0) {
            return found + "." + advice;
        }
        byte[] head = new byte[SNIFF_LENGTH];
        head[0] = (byte) first;
        byte[] more = readExact(SNIFF_LENGTH - 1);
        System.arraycopy(more, 0, head, 1, more.length);
        String hint = AfpSniffer.hint(Arrays.copyOf(head, 1 + more.length), advise);
        return found + "." + (hint.isEmpty() ? "" : " " + hint) + advice;
    }

    /**
     * The padding at the end of {@code data}, including its length bytes ("Structured Field
     * Padding"): a non-zero last byte is the length (1-255 bytes of padding); a last byte of
     * {@code 00} means the two bytes before it are the length (3-32,759 bytes).
     */
    private static int paddingLength(byte[] data, long start) {
        int n = data.length;
        if (n == 0) {
            throw new AfpFormatException(start, "the padding flag is set but the field has no"
                    + " data to hold the padding length");
        }
        int padding = data[n - 1] & 0xFF;
        if (padding == 0) {
            padding = n < 3 ? 0 : unsigned(data, n - 3, 2);
            if (padding < 3) {
                throw new AfpFormatException(start, "the padding flag is set but the 3-byte"
                        + " padding length (" + padding + ") is less than 3");
            }
        }
        if (padding > n) {
            throw new AfpFormatException(start, "the padding flag is set but the padding length"
                    + " (" + padding + ") is more than the " + n + " byte(s) after the header"
                    + " and extension");
        }
        return padding;
    }

    /**
     * Reads exactly {@code count} bytes or fails. {@code fieldLength} is the length value, or -1
     * while the header itself is being read.
     */
    private byte[] readFully(int count, long fieldStart, int fieldLength) {
        byte[] bytes = readExact(count);
        if (bytes.length < count) {
            long available = position - fieldStart;
            String ends = inRecord ? "the record ends" : "the file ends";
            throw new AfpFormatException(fieldStart, fieldLength < 0
                    ? "truncated field: " + ends + " " + available
                            + " byte(s) into the 9-byte field header"
                    : "truncated field: its length says " + (fieldLength + 1)
                            + " bytes including the 0x5A, but " + ends + " after " + available);
        }
        return bytes;
    }

    /** Up to {@code count} bytes, fewer at the end of the input. */
    private byte[] readExact(int count) {
        try {
            byte[] bytes = source.readNBytes(count);
            position += bytes.length;
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int read() {
        try {
            int b = source.read();
            if (b >= 0) {
                position++;
            }
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int unsigned(byte[] bytes, int from, int count) {
        int value = 0;
        for (int i = from; i < from + count; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }
}
