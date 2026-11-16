package dev.yjimmy.printlab.afp;

import java.nio.charset.Charset;

/**
 * Decodes a TLE's data, which is a list of triplets. Each triplet is a length byte (counting
 * itself), an ID byte, then data:
 * <pre>
 *   len 02 0B 00 name...     Fully Qualified Name, type 0B (attribute name), format 00 (text)
 *   len 36 00 00 value...    Attribute Value (two reserved bytes, then the text)
 *   len 80 ...               Attribute Qualifier and anything else: ignored
 * </pre>
 * Names and values are EBCDIC, IBM037 unless another code page is given.
 */
public final class TleDecoder {

    /** Triplet ID: Fully Qualified Name. */
    public static final int TRIPLET_FQN = 0x02;
    /** Triplet ID: Attribute Value. */
    public static final int TRIPLET_ATTRIBUTE_VALUE = 0x36;
    /** FQN type for a TLE's attribute name (Attribute GID). */
    public static final int FQN_TYPE_ATTRIBUTE_NAME = 0x0B;
    /** FQN format: a character string. The others (an OID, a URL) aren't text names. */
    public static final int FQN_FORMAT_CHARACTER_STRING = 0x00;

    /** Bytes before the text in both triplets: length, ID, and two type/format/reserved bytes. */
    private static final int TEXT_START = 4;

    private TleDecoder() {
    }

    public static Tle decode(StructuredField field) {
        return decode(field, Ebcdic.IBM037);
    }

    public static Tle decode(StructuredField field, Charset codePage) {
        if (field.type() != FieldType.TLE) {
            throw new IllegalArgumentException(field + " is not a TLE");
        }
        return decode(field.data(), codePage);
    }

    /**
     * Decodes TLE data. Throws {@link IllegalArgumentException} if a triplet's length runs past
     * the data, the name isn't a character string (FQN format 00), or the name or value triplet
     * is missing.
     */
    public static Tle decode(byte[] data, Charset codePage) {
        String name = null;
        String value = null;
        int i = 0;
        while (i < data.length) {
            int length = data[i] & 0xFF;
            if (length < 2 || i + length > data.length) {
                throw new IllegalArgumentException(String.format(
                        "malformed triplet at data offset %d: length %d, but %d byte(s) remain",
                        i, length, data.length - i));
            }
            int id = data[i + 1] & 0xFF;
            boolean hasText = length >= TEXT_START;
            if (id == TRIPLET_FQN && hasText
                    && (data[i + 2] & 0xFF) == FQN_TYPE_ATTRIBUTE_NAME) {
                int format = data[i + 3] & 0xFF;
                if (format != FQN_FORMAT_CHARACTER_STRING) {
                    throw new IllegalArgumentException(String.format(
                            "attribute name triplet at data offset %d has FQN format %02X;"
                                    + " only 00 (character string) is supported",
                            i, format));
                }
                name = Ebcdic.decode(data, i + TEXT_START, length - TEXT_START, codePage);
            } else if (id == TRIPLET_ATTRIBUTE_VALUE && hasText) {
                value = Ebcdic.decode(data, i + TEXT_START, length - TEXT_START, codePage);
            }
            i += length;
        }
        if (name == null) {
            throw new IllegalArgumentException(
                    "no attribute name triplet (02, FQN type 0B)");
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "TLE '" + name + "' has no attribute value triplet (36)");
        }
        return new Tle(name, value);
    }
}
