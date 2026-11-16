package dev.yjimmy.printlab.afp;

import java.nio.charset.Charset;

/**
 * EBCDIC text, the character encoding used inside AFP. Letters and digits are not where ASCII
 * puts them: {@code A} is {@code 0xC1}, {@code 1} is {@code 0xF1}, space is {@code 0x40}.
 *
 * <p>The default is code page IBM037 (US/Canada). Files from other countries may use another code
 * page (IBM500, IBM273, ...), so every method has a variant that takes one.
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
}
