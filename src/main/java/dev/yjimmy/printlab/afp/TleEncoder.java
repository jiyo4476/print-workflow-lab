package dev.yjimmy.printlab.afp;

import java.io.ByteArrayOutputStream;

/**
 * Encodes a Tag Logical Element, the inverse of {@link TleDecoder}. The data is two triplets in
 * code page IBM037, the decoder's default, so no CGCSGID triplet is needed:
 * <pre>
 *   len 02 0B 00 name...     Fully Qualified Name, type 0B (attribute name), format 00 (text)
 *   len 36 00 00 value...    Attribute Value (two reserved bytes, then the text)
 * </pre>
 * Each triplet's length byte counts itself and is at most 254, so the name and the value are each
 * at most 250 bytes.
 */
public final class TleEncoder {

    /** The longest name or value: the maximum triplet length less the 4 bytes before the text. */
    public static final int MAX_TEXT_LENGTH = TleDecoder.MAX_TRIPLET_LENGTH - 4;

    private TleEncoder() {
    }

    /** A TLE field's data for {@code tle}. */
    public static byte[] encode(Tle tle) {
        return encode(tle.name(), tle.value());
    }

    /**
     * A TLE field's data. An empty value is allowed and means the attribute has no value.
     *
     * @throws IllegalArgumentException if the name is empty, either text is longer than
     *     {@link #MAX_TEXT_LENGTH} bytes, or has characters that aren't in IBM037
     */
    public static byte[] encode(String name, String value) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a TLE needs a name");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeText(out, TleDecoder.TRIPLET_FQN, TleDecoder.FQN_TYPE_ATTRIBUTE_NAME,
                TleDecoder.FQN_FORMAT_CHARACTER_STRING, "name", name);
        // The Attribute Value triplet's two bytes before the text are reserved.
        writeText(out, TleDecoder.TRIPLET_ATTRIBUTE_VALUE, 0, 0, "value", value);
        return out.toByteArray();
    }

    private static void writeText(ByteArrayOutputStream out, int id, int byte2, int byte3,
            String what, String text) {
        byte[] bytes;
        try {
            bytes = Ebcdic.encodeStrict(text, Ebcdic.IBM037);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TLE " + what + ": " + e.getMessage(), e);
        }
        if (bytes.length > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("TLE " + what + " is " + bytes.length
                    + " bytes; a triplet holds at most " + MAX_TEXT_LENGTH);
        }
        out.write(4 + bytes.length);
        out.write(id);
        out.write(byte2);
        out.write(byte3);
        out.writeBytes(bytes);
    }
}
