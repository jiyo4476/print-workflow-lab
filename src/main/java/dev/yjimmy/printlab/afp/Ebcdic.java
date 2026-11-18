package dev.yjimmy.printlab.afp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * EBCDIC text, the character encoding used inside AFP. Letters and digits are not where ASCII
 * puts them: {@code A} is {@code 0xC1}, {@code 1} is {@code 0xF1}, space is {@code 0x40}.
 *
 * <p>The default is code page IBM037 (US/Canada). Files from other countries may use another code
 * page (IBM500, IBM273, ...), so every method has a variant that takes one. A file can also name
 * its code page with a Coded Graphic Character Set Global Identifier (X'01') triplet;
 * {@link #forCodePage(int)} and {@link #forCcsid(int)} turn its numbers into a {@link Charset}.
 */
public final class Ebcdic {

    /** EBCDIC code page 037, US/Canada. Ships in the JDK's {@code jdk.charsets} module. */
    public static final Charset IBM037 = Charset.forName("IBM037");

    private Ebcdic() {
    }

    public static String decode(byte[] bytes) {
        return decode(bytes, 0, bytes.length, IBM037);
    }

    public static String decode(byte[] bytes, int offset, int length, Charset codePage) {
        return new String(bytes, offset, length, codePage);
    }

    public static byte[] encode(String text) {
        return encode(text, IBM037);
    }

    public static byte[] encode(String text, Charset codePage) {
        return text.getBytes(codePage);
    }

    /**
     * The charset for a Code Page Global Identifier (CPGID), e.g. 37 → IBM037, 500 → IBM500,
     * 1140 → IBM01140. Throws {@link IllegalArgumentException} if this JVM has no such charset.
     */
    public static Charset forCodePage(int cpgid) {
        String name = String.format(Locale.ROOT, "IBM%03d", cpgid);
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("code page " + cpgid + " (" + name
                    + ") isn't available in this JVM", e);
        }
    }

    /**
     * The charset for a Coded Character Set Identifier (CCSID). The Unicode CCSIDs are mapped by
     * name; for the single-byte code pages AFP uses, the CCSID is the code page number.
     */
    public static Charset forCcsid(int ccsid) {
        return switch (ccsid) {
            case 1208 -> StandardCharsets.UTF_8;
            case 1200, 13488 -> StandardCharsets.UTF_16BE;
            default -> forCodePage(ccsid);
        };
    }
}
