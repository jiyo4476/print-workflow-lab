package dev.yjimmy.printlab.afp;

import java.nio.charset.StandardCharsets;

/**
 * Says what a file that isn't AFP probably is, from its first bytes, so the error for a wrong
 * input names the likely cause instead of just a byte value. A guess, offered as one.
 */
final class AfpSniffer {

    private AfpSniffer() {
    }

    /**
     * A sentence about what {@code head} (the first bytes of a file, up to a few dozen) looks like,
     * or an empty string if it looks like nothing in particular.
     */
    static String hint(byte[] head) {
        if (head.length == 0) {
            return "";
        }
        if (startsWith(head, "%PDF-")) {
            return "The file starts with %PDF-, so it is a PDF: use SplitPdf or SplitPdfByDPart.";
        }
        if (startsWith(head, "%!PS")) {
            return "The file starts with %!PS, so it is PostScript, which this project doesn't read.";
        }
        if (unsigned(head[0]) == 0x1B) {
            return "The file starts with an ESC byte, so it looks like PCL or another printer"
                    + " language, not MO:DCA.";
        }
        if (head.length >= 3 && unsigned(head[2]) == 0xD3) {
            return "Byte 3 is 0xD3, the start of a structured field's type, so the fields may have"
                    + " no 0x5A before them; this reader needs the 0x5A form.";
        }
        if (head.length >= 4 && head[2] == 0 && head[3] == 0
                && ((unsigned(head[0]) << 8) | unsigned(head[1])) >= 4) {
            return "The file starts like a record or block descriptor word (a length, then two"
                    + " zero bytes), so it may be RDW or BDW framed.";
        }
        if (share(head, AfpSniffer::isAscii) >= 0.9) {
            return "The file is mostly printable ASCII text, so it looks like line data or a text"
                    + " file, not MO:DCA.";
        }
        if (share(head, AfpSniffer::isEbcdic) >= 0.9) {
            return "The file looks like EBCDIC text, probably line data, which needs a page"
                    + " definition to become AFP; this project reads MO:DCA.";
        }
        return "";
    }

    private static boolean startsWith(byte[] head, String prefix) {
        byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (head.length < bytes.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (head[i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    private interface ByteTest {
        boolean test(int value);
    }

    private static double share(byte[] head, ByteTest test) {
        int matches = 0;
        for (byte b : head) {
            if (test.test(unsigned(b))) {
                matches++;
            }
        }
        return (double) matches / head.length;
    }

    private static boolean isAscii(int b) {
        return (b >= 0x20 && b <= 0x7E) || b == 0x09 || b == 0x0A || b == 0x0D;
    }

    /** Space, punctuation, letters and digits of the common EBCDIC code pages. */
    private static boolean isEbcdic(int b) {
        return b == 0x40
                || (b >= 0x4A && b <= 0x50) || (b >= 0x5A && b <= 0x61) || (b >= 0x6A && b <= 0x6F)
                || (b >= 0x79 && b <= 0x7F)
                || (b >= 0x81 && b <= 0x89) || (b >= 0x91 && b <= 0x99) || (b >= 0xA2 && b <= 0xA9)
                || (b >= 0xC1 && b <= 0xC9) || (b >= 0xD1 && b <= 0xD9) || (b >= 0xE2 && b <= 0xE9)
                || (b >= 0xF0 && b <= 0xF9);
    }

    private static int unsigned(byte b) {
        return b & 0xFF;
    }
}
