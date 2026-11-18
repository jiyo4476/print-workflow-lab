package dev.yjimmy.printlab.afp;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Writes MO:DCA (AFP) structured fields, the inverse of {@link AfpReader}. Each field is written
 * with the {@code 0x5A} introducer, its length computed from the data, and the reserved bytes 6-7
 * as {@code 0000}. See {@link StructuredField} for the byte layout.
 *
 * <p>The writer doesn't check the structure (Begin/End nesting, which fields may appear where);
 * that's the caller's job, and {@link AfpDump} can check the result.
 */
public final class AfpWriter implements Closeable, Flushable {

    /** The most data one field can carry: the maximum SFLength less the 8-byte header. */
    public static final int MAX_DATA_LENGTH = AfpReader.MAX_LENGTH - AfpReader.HEADER_LENGTH;
    /** Begin and End fields start with an 8-byte name token. */
    public static final int NAME_LENGTH = 8;

    private final OutputStream out;
    private long position;

    public AfpWriter(OutputStream out) {
        this.out = out instanceof BufferedOutputStream ? out : new BufferedOutputStream(out);
    }

    /** Writes a field with flags {@code 00}. */
    public AfpWriter write(FieldType type, byte[] data) throws IOException {
        return write(type.id(), 0, data);
    }

    /**
     * Writes one field. {@code data} is everything after the 8-byte header, so if {@code flags}
     * marks an extension or padding, {@code data} must already contain them.
     *
     * @throws IllegalArgumentException if the ID isn't 3 bytes, the flags aren't 1 byte, or the
     *     data is longer than {@link #MAX_DATA_LENGTH}
     */
    public AfpWriter write(int typeId, int flags, byte[] data) throws IOException {
        if ((typeId & ~0xFFFFFF) != 0) {
            throw new IllegalArgumentException(String.format(
                    "type ID %X is more than 3 bytes", typeId));
        }
        if ((flags & ~0xFF) != 0) {
            throw new IllegalArgumentException(String.format(
                    "flags %X are more than 1 byte", flags));
        }
        if (data.length > MAX_DATA_LENGTH) {
            throw new IllegalArgumentException(FieldType.nameOf(typeId) + " data is "
                    + data.length + " bytes; a structured field holds at most " + MAX_DATA_LENGTH
                    + ". Split it across fields (PTX, IPD) or use segmentation.");
        }
        int length = AfpReader.HEADER_LENGTH + data.length;
        byte[] header = {
            (byte) AfpReader.INTRODUCER,
            (byte) (length >> 8), (byte) length,
            (byte) (typeId >> 16), (byte) (typeId >> 8), (byte) typeId,
            (byte) flags,
            // Reserved. MO:DCA says a sequence number here is an unarchitected use to avoid.
            0, 0,
        };
        out.write(header);
        out.write(data);
        position += header.length + data.length;
        return this;
    }

    /** Writes a Begin field whose data is its name, padded with EBCDIC spaces to 8 bytes. */
    public AfpWriter begin(FieldType type, String name) throws IOException {
        return write(type, name(name));
    }

    /** Writes an End field with no name, which matches any Begin of its type. */
    public AfpWriter end(FieldType type) throws IOException {
        return write(type, new byte[0]);
    }

    /** Bytes written so far, which is the offset the next field will have. */
    public long position() {
        return position;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /**
     * An 8-byte name token in IBM037: the name, then EBCDIC spaces.
     *
     * @throws IllegalArgumentException if the name is empty, longer than 8 characters, or has
     *     characters the code page can't encode
     */
    public static byte[] name(String name) {
        if (name.isEmpty() || name.length() > NAME_LENGTH) {
            throw new IllegalArgumentException("a name token is 1-" + NAME_LENGTH
                    + " characters, got '" + name + "'");
        }
        byte[] bytes = Ebcdic.encodeStrict(name, Ebcdic.IBM037);
        byte[] padded = Arrays.copyOf(bytes, NAME_LENGTH);
        Arrays.fill(padded, bytes.length, NAME_LENGTH, Ebcdic.SPACE);
        return padded;
    }
}
