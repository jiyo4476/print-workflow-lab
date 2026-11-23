package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** The value types: equality, text forms and the checks their constructors make. */
public class PdfObjectTest {

    @Test
    public void stringTextIsUtf16WithAByteOrderMarkElseOneCharPerByte() {
        byte[] utf16 = {(byte) 0xFE, (byte) 0xFF, 0x00, 'H', 0x00, (byte) 0xE9};

        assertEquals("UTF-16BE", "Hé", new PdfString(utf16).text());
        assertEquals("one char per byte", "café", new PdfString(new byte[] {'c', 'a', 'f', (byte) 0xE9}).text());
        assertEquals("a lone FE", "þ", new PdfString(new byte[] {(byte) 0xFE}).text());
    }

    @Test
    public void stringPrintsAsLiteralWhenPrintableElseHex() {
        assertEquals("literal", "(a\\(b\\)\\\\)", PdfString.of("a(b)\\").toString());
        assertEquals("hex", "<00FF>", new PdfString(new byte[] {0, (byte) 0xFF}).toString());
    }

    @Test
    public void stringsAreEqualByBytesAndDontShareTheirArray() {
        byte[] bytes = {'a', 'b'};
        PdfString string = new PdfString(bytes);
        bytes[0] = 'x';
        string.bytes()[1] = 'y';

        assertEquals("unchanged", PdfString.of("ab"), string);
        assertEquals("hash", PdfString.of("ab").hashCode(), string.hashCode());
        assertNotEquals("different bytes", PdfString.of("ac"), string);
        assertNotEquals("different type", PdfName.of("ab"), string);
    }

    @Test
    public void streamsAreEqualByDictionaryDataAndOffset() {
        PdfDictionary dictionary = PdfDictionary.of("Length", PdfNumber.of(2));
        PdfStream stream = new PdfStream(dictionary, new byte[] {1, 2}, 10);

        assertEquals("same", new PdfStream(dictionary, new byte[] {1, 2}, 10), stream);
        assertEquals("hash", new PdfStream(dictionary, new byte[] {1, 2}, 10).hashCode(), stream.hashCode());
        assertNotEquals("other data", new PdfStream(dictionary, new byte[] {1, 3}, 10), stream);
        assertNotEquals("other offset", new PdfStream(dictionary, new byte[] {1, 2}, 11), stream);
        assertNotEquals("other dictionary", new PdfStream(PdfDictionary.of(), new byte[] {1, 2}, 10), stream);
        assertNotEquals("other type", dictionary, stream);
        stream.data()[0] = 9;
        assertThat(stream.data()).containsExactly(1, 2);
    }

    @Test
    public void numbersKnowWhetherTheyAreInts() {
        assertEquals("int", 5, PdfNumber.of(5).intValue());
        assertEquals("real", false, new PdfNumber(new BigDecimal("4"), true).isInt());
        assertThrows(ArithmeticException.class, () -> new PdfNumber(new BigDecimal("4"), true).intValue());
        assertEquals("text", "-0.5", new PdfNumber(new BigDecimal("-.5"), true).toString());
        assertThrows(IllegalArgumentException.class, () -> new PdfNumber(null, false));
    }

    @Test
    public void constructorsRejectWhatPdfCantHold() {
        assertThrows(IllegalArgumentException.class, () -> new PdfReference(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PdfReference(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new PdfName(null));
        assertThrows(IllegalArgumentException.class, () -> PdfDictionary.of("A"));
    }

    @Test
    public void dictionaryKeepsOrderAndDropsNulls() {
        Map<String, PdfObject> entries = new HashMap<>();
        entries.put("A", null);
        entries.put("B", PdfNull.INSTANCE);

        assertEquals("no entries", Map.of(), new PdfDictionary(entries).entries());
        assertThat(PdfDictionary.of("Z", PdfNumber.of(1), "A", PdfNumber.of(2)).entries().keySet())
                .containsExactly("Z", "A");
    }

    @Test
    public void simpleValuesPrintAsPdf() {
        assertEquals("null", "null", PdfNull.INSTANCE.toString());
        assertEquals("true", "true", PdfBoolean.of(true).toString());
        assertEquals("false", PdfBoolean.FALSE, PdfBoolean.of(false));
        assertEquals("reference", "12 0 R", new PdfReference(12, 0).toString());
        assertEquals("name", "/Type", PdfName.of("Type").toString());
        assertEquals("indirect object", new PdfReference(1, 0),
                new PdfIndirectObject(new PdfReference(1, 0), PdfNull.INSTANCE, 0).reference());
    }

    /** A name read from a file can hold any byte; printed, it's PDF syntax again (7.3.5). */
    @Test
    public void namesPrintWithHashEscapes() {
        String escape = String.valueOf((char) 0x1B);

        assertEquals("space", "/A#20B", PdfName.of("A B").toString());
        assertEquals("delimiters and hash", "/a#2Fb#28#23", PdfName.of("a/b(#").toString());
        assertEquals("control character", "/x#1B#5B2J", PdfName.of("x" + escape + "[2J").toString());
        assertEquals("high byte", "/caf#E9", PdfName.of("caf" + (char) 0xE9).toString());
        assertEquals("past 0xFF, as UTF-8", "/caf#C3#A9#E2#82#AC",
                PdfName.of("caf" + (char) 0xE9 + (char) 0x20AC).toString());
    }
}
