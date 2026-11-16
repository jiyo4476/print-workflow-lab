package dev.yjimmy.printlab.afp;

import java.io.BufferedInputStream;
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

    private final InputStream in;
    private long position;
    private StructuredField next;
    private boolean done;

    public AfpReader(InputStream in) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
    }

    /** Reads every field. Convenient for small inputs; use the iterator for big files. */
    public static List<StructuredField> readAll(InputStream in) {
        List<StructuredField> fields = new ArrayList<>();
        AfpReader reader = new AfpReader(in);
        reader.forEachRemaining(fields::add);
        return fields;
    }

    @Override
    public boolean hasNext() {
        if (next == null && !done) {
            try {
                next = readField();
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
        long start = position;
        int first = read();
        if (first < 0) {
            done = true;
            return null;
        }
        if (first != INTRODUCER) {
            throw new AfpFormatException(start, String.format(
                    "expected 0x5A before a structured field but found 0x%02X. Files copied"
                            + " from a mainframe sometimes have a record header (RDW) before"
                            + " each field instead; this reader expects the 0x5A form.", first));
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
        try {
            byte[] bytes = in.readNBytes(count);
            position += bytes.length;
            if (bytes.length < count) {
                long available = position - fieldStart;
                throw new AfpFormatException(fieldStart, fieldLength < 0
                        ? "truncated field: the file ends " + available
                                + " byte(s) into the 9-byte field header"
                        : "truncated field: its length says " + (fieldLength + 1)
                                + " bytes including the 0x5A, but the file ends after "
                                + available);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int read() {
        try {
            int b = in.read();
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
