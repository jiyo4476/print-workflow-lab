package dev.yjimmy.printlab.testsupport;

import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Builds AFP bytes for tests, so inputs read as structure instead of hex:
 * <pre>
 *   byte[] afp = AfpBytes.create()
 *           .begin(FieldType.BNG, "G0000001")
 *           .tle("accountNumber", "1234567890")
 *           .end(FieldType.ENG)
 *           .bytes();
 * </pre>
 * Each field gets the {@code 0x5A} introducer, the length (header plus data, not the 0x5A), flags
 * {@code 00} and sequence {@code 0000} unless given. Text is EBCDIC (IBM037).
 */
public final class AfpBytes {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private AfpBytes() {
    }

    public static AfpBytes create() {
        return new AfpBytes();
    }

    /** A field with flags 00 and sequence 0000. */
    public AfpBytes field(FieldType type, byte... data) {
        return field(type.id(), 0, 0, data);
    }

    public AfpBytes field(int typeId, int flags, int sequence, byte... data) {
        int length = 8 + data.length;
        out.write(0x5A);
        out.write(length >> 8);
        out.write(length);
        out.write(typeId >> 16);
        out.write(typeId >> 8);
        out.write(typeId);
        out.write(flags);
        out.write(sequence >> 8);
        out.write(sequence);
        out.writeBytes(data);
        return this;
    }

    /** A Begin field whose data is an 8-character EBCDIC name, padded with spaces. */
    public AfpBytes begin(FieldType type, String name) {
        return field(type, name8(name));
    }

    /** An End field with no data. */
    public AfpBytes end(FieldType type) {
        return field(type);
    }

    /** A TLE with just a name triplet and a value triplet. */
    public AfpBytes tle(String name, String value) {
        return field(FieldType.TLE, tleData(name, value));
    }

    /** Arbitrary bytes, e.g. a broken field. */
    public AfpBytes raw(int... bytes) {
        for (int b : bytes) {
            out.write(b);
        }
        return this;
    }

    public byte[] bytes() {
        return out.toByteArray();
    }

    /** TLE data: an FQN triplet (02, type 0B, format 00) then an Attribute Value triplet (36). */
    public static byte[] tleData(String name, String value) {
        return concat(
                triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode(name))),
                triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode(value))));
    }

    /** A triplet: a length byte that counts itself, the ID, then {@code data}. */
    public static byte[] triplet(int id, byte... data) {
        return concat(bytes(2 + data.length, id), data);
    }

    /** {@code name} in EBCDIC, padded with spaces to exactly 8 bytes. */
    public static byte[] name8(String name) {
        if (name.length() > 8) {
            throw new IllegalArgumentException("Name longer than 8 characters: " + name);
        }
        return Ebcdic.encode(String.format("%-8s", name));
    }

    public static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    public static byte[] concat(byte[]... parts) {
        byte[] all = new byte[Arrays.stream(parts).mapToInt(p -> p.length).sum()];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
