package dev.yjimmy.printlab.pdf;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.cos.COSString;
import org.junit.Test;

/** {@link PdfString#text()}: the three encodings a PDF 2.0 text string may use (7.9.2.2). */
public class PdfStringTest {

    private static byte[] withPrefix(byte[] prefix, byte[] rest) {
        byte[] all = new byte[prefix.length + rest.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(rest, 0, all, prefix.length, rest.length);
        return all;
    }

    /** "Zurich" with u-umlaut, then a euro sign: code points, so the source stays ASCII. */
    private static final String TEXT = "Z" + (char) 0xFC + "rich " + (char) 0x20AC + "5";

    @Test
    public void utf8TextStringWithBomDecodes() {
        byte[] bytes = withPrefix(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                TEXT.getBytes(StandardCharsets.UTF_8));

        assertEquals("UTF-8 (PDF 2.0)", TEXT, new PdfString(bytes).text());
    }

    @Test
    public void utf16TextStringWithBomDecodes() {
        byte[] bytes = withPrefix(new byte[] {(byte) 0xFE, (byte) 0xFF}, TEXT.getBytes(StandardCharsets.UTF_16BE));

        assertEquals("UTF-16BE", TEXT, new PdfString(bytes).text());
    }

    @Test
    public void otherStringsArePdfDocEncoding() {
        byte[] bytes = {'A', (byte) 0x80, (byte) 0x93, (byte) 0xA0, (byte) 0xFC, 0x18, (byte) 0xAD};

        assertEquals("bullet, fi ligature, euro, u-umlaut, breve, undefined",
                "A" + (char) 0x2022 + (char) 0xFB01 + (char) 0x20AC + (char) 0xFC + (char) 0x02D8 + (char) 0xFFFD,
                new PdfString(bytes).text());
    }

    @Test
    public void shortStringsAreNotMistakenForAByteOrderMark() {
        assertEquals("one byte", "" + (char) 0xFE, new PdfString(new byte[] {(byte) 0xFE}).text());
        assertEquals("two bytes", "" + (char) 0xEF + (char) 0xBB,
                new PdfString(new byte[] {(byte) 0xEF, (byte) 0xBB}).text());
        assertEquals("empty", "", new PdfString(new byte[0]).text());
    }

    /** PDFBox's PDFDocEncoding is the oracle for every byte that isn't undefined. */
    @Test
    public void pdfDocEncodingMatchesPdfBoxForEveryDefinedByte() {
        for (int code = 0; code < 256; code++) {
            if (code == 0x7F || code == 0x9F || code == 0xAD) {
                continue; // undefined in Annex D; PDFBox and this reader may differ
            }
            byte[] bytes = {'x', (byte) code};

            assertEquals(String.format("byte %02X", code), new COSString(bytes).getString(),
                    new PdfString(bytes).text());
        }
    }
}
