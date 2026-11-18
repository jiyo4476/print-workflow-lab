package dev.yjimmy.printlab.afp;

import java.nio.charset.Charset;

/**
 * Decodes a Tag Logical Element (TLE). Its data is only triplets, each a length byte (counting
 * itself), an ID byte, then data (MO:DCA Reference AFPC-0004-11, "Tag Logical Element" and
 * chapter 6, "MO:DCA Triplets"):
 * <pre>
 *   06 01 GG GG CC CC        CGCSGID: code page for the character strings after it
 *   len 02 0B 00 name...     Fully Qualified Name, type 0B (attribute name), format 00 (text)
 *   len 36 00 00 value...    Attribute Value (two reserved bytes, then the text)
 *   len 80 ...               Attribute Qualifier, other FQN types: ignored
 * </pre>
 * The name and the value must each occur exactly once. Text is decoded with the code page passed
 * in (IBM037 by default) until a CGCSGID (X'01') triplet names another, which then applies to
 * every later triplet in the TLE. A CGCSGID on an enclosing Begin field is not applied.
 */
public final class TleDecoder {

    /** Triplet ID: Coded Graphic Character Set Global Identifier. */
    public static final int TRIPLET_CGCSGID = 0x01;
    /** Triplet ID: Fully Qualified Name. */
    public static final int TRIPLET_FQN = 0x02;
    /** Triplet ID: Attribute Value. */
    public static final int TRIPLET_ATTRIBUTE_VALUE = 0x36;
    /** FQN type for a TLE's attribute name (Attribute GID). */
    public static final int FQN_TYPE_ATTRIBUTE_NAME = 0x0B;
    /** FQN format: a character string. The others (an OID, a URL) aren't text names. */
    public static final int FQN_FORMAT_CHARACTER_STRING = 0x00;

    /**
     * MO:DCA triplet lengths, counting the length byte. A triplet is at least a length, an ID and
     * one byte of data; X'FF' is not a valid length.
     */
    static final int MIN_TRIPLET_LENGTH = 3;
    static final int MAX_TRIPLET_LENGTH = 254;
    /** Minimum lengths of the triplets this decoder reads (Tlength ranges in chapter 6). */
    static final int CGCSGID_LENGTH = 6;
    static final int MIN_FQN_LENGTH = 5;
    static final int MIN_ATTRIBUTE_VALUE_LENGTH = 4;

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
     * Decodes TLE data, starting with {@code codePage}. Throws {@link IllegalArgumentException} if
     * a triplet's length is outside its range or runs past the data, a CGCSGID names a code page
     * this JVM doesn't have, the name isn't a character string (FQN format 00), or the name or
     * value is missing or repeated.
     *
     * <p>An Attribute Value triplet with no text means the attribute has no value; it decodes as
     * the empty string.
     */
    public static Tle decode(byte[] data, Charset codePage) {
        Charset charset = codePage;
        String name = null;
        String value = null;
        int i = 0;
        while (i < data.length) {
            int length = data[i] & 0xFF;
            if (length < MIN_TRIPLET_LENGTH || length > MAX_TRIPLET_LENGTH) {
                throw new IllegalArgumentException(String.format(
                        "malformed triplet at data offset %d: length %d is outside the MO:DCA"
                                + " range %d-%d", i, length, MIN_TRIPLET_LENGTH,
                        MAX_TRIPLET_LENGTH));
            }
            if (i + length > data.length) {
                throw new IllegalArgumentException(String.format(
                        "malformed triplet at data offset %d: length %d, but %d byte(s) remain",
                        i, length, data.length - i));
            }
            int id = data[i + 1] & 0xFF;
            if (id == TRIPLET_CGCSGID) {
                charset = codePage(data, i, length);
            } else if (id == TRIPLET_FQN) {
                checkLength(i, length, MIN_FQN_LENGTH, "Fully Qualified Name");
                if ((data[i + 2] & 0xFF) == FQN_TYPE_ATTRIBUTE_NAME) {
                    if (name != null) {
                        throw new IllegalArgumentException(String.format(
                                "second attribute name triplet at data offset %d; a TLE has"
                                        + " exactly one", i));
                    }
                    int format = data[i + 3] & 0xFF;
                    if (format != FQN_FORMAT_CHARACTER_STRING) {
                        throw new IllegalArgumentException(String.format(
                                "attribute name triplet at data offset %d has FQN format %02X;"
                                        + " only 00 (character string) is supported",
                                i, format));
                    }
                    name = Ebcdic.decode(data, i + TEXT_START, length - TEXT_START, charset);
                }
            } else if (id == TRIPLET_ATTRIBUTE_VALUE) {
                checkLength(i, length, MIN_ATTRIBUTE_VALUE_LENGTH, "Attribute Value");
                if (value != null) {
                    throw new IllegalArgumentException(String.format(
                            "second attribute value triplet at data offset %d; a TLE has"
                                    + " exactly one", i));
                }
                value = Ebcdic.decode(data, i + TEXT_START, length - TEXT_START, charset);
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

    /**
     * Reads a CGCSGID triplet: GCSGID then CPGID, or {@code 0000} then a CCSID. The GCSGID only
     * narrows the character set, so the code page alone decides the charset.
     */
    private static Charset codePage(byte[] data, int i, int length) {
        if (length != CGCSGID_LENGTH) {
            throw new IllegalArgumentException(String.format(
                    "CGCSGID triplet at data offset %d has length %d; it must be %d",
                    i, length, CGCSGID_LENGTH));
        }
        int first = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
        int second = ((data[i + 4] & 0xFF) << 8) | (data[i + 5] & 0xFF);
        try {
            return first == 0 ? Ebcdic.forCcsid(second) : Ebcdic.forCodePage(second);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format(
                    "CGCSGID triplet at data offset %d: %s", i, e.getMessage()), e);
        }
    }

    private static void checkLength(int i, int length, int min, String triplet) {
        if (length < min) {
            throw new IllegalArgumentException(String.format(
                    "%s triplet at data offset %d has length %d; the minimum is %d",
                    triplet, i, length, min));
        }
    }
}
