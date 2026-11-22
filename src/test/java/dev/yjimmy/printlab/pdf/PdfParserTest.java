package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class PdfParserTest {

    private static byte[] ascii(String pdf) {
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static PdfObject parse(String pdf) {
        return new PdfParser(ascii(pdf)).parseObject();
    }

    private static PdfIndirectObject parseIndirect(String pdf) {
        return new PdfParser(ascii(pdf)).parseIndirectObject();
    }

    private static PdfNumber n(long value) {
        return PdfNumber.of(value);
    }

    @Test
    public void nestedDictionariesAndArrays() {
        PdfObject page = parse("""
                << /Type /Page
                   /MediaBox [0 0 612 792]
                   /Resources << /Font << /F1 5 0 R >> /ProcSet [/PDF /Text] >>
                   /Rotate 0.0 /Hidden false /Label (p. 1) >>""");

        assertEquals("page", PdfDictionary.of(
                "Type", PdfName.of("Page"),
                "MediaBox", PdfArray.of(n(0), n(0), n(612), n(792)),
                "Resources", PdfDictionary.of(
                        "Font", PdfDictionary.of("F1", new PdfReference(5, 0)),
                        "ProcSet", PdfArray.of(PdfName.of("PDF"), PdfName.of("Text"))),
                "Rotate", new PdfNumber(new BigDecimal("0.0"), true),
                "Hidden", PdfBoolean.FALSE,
                "Label", PdfString.of("p. 1")), page);
        assertEquals("text form", "<</Type /Page /MediaBox [0 0 612 792] /Resources <</Font <</F1 5 0 R>>"
                + " /ProcSet [/PDF /Text]>> /Rotate 0.0 /Hidden false /Label (p. 1)>>", page.toString());
    }

    @Test
    public void indirectReferenceVsTwoNumbers() {
        assertEquals("12 0 R", PdfArray.of(new PdfReference(12, 0)), parse("[12 0 R]"));
        assertEquals("12 0", PdfArray.of(n(12), n(0)), parse("[12 0]"));
        assertEquals("mixed", PdfArray.of(n(1), n(2), new PdfReference(3, 0), n(4), new PdfReference(5, 2)),
                parse("[1 2 3 0 R 4 5 2 R]"));
        assertEquals("number then end", n(7), parse("7"));
        assertEquals("number then a number", n(7), parse("7 8"));
    }

    @Test
    public void numbersThatCantBeObjectNumbersAreNotReferences() {
        // -1 and 1.0 can't start a reference, so the R that follows is a stray keyword.
        assertThat(assertThrows(PdfFormatException.class, () -> parse("[-1 0 R]")))
                .hasMessageContaining("found the keyword 'R'");
        assertThat(assertThrows(PdfFormatException.class, () -> parse("[1.0 0 R]")))
                .hasMessageContaining("found the keyword 'R'");
        assertThat(assertThrows(PdfFormatException.class, () -> parse("[1 0.0 R]")))
                .hasMessageContaining("found the keyword 'R'");
    }

    @Test
    public void keywordsTrueFalseAndNullAreObjects() {
        assertEquals("array", PdfArray.of(PdfBoolean.TRUE, PdfBoolean.FALSE, PdfNull.INSTANCE),
                parse("[true false null]"));
    }

    @Test
    public void nullEntryIsTheSameAsNoEntry() {
        PdfDictionary dictionary = (PdfDictionary) parse("<< /A null /B 1 >>");

        assertNull("A", dictionary.get("A"));
        assertEquals("keys", Map.of("B", n(1)), dictionary.entries());
    }

    @Test
    public void repeatedKeyKeepsTheLastValue() {
        assertEquals("dictionary", PdfDictionary.of("A", n(2)), parse("<< /A 1 /A 2 >>"));
    }

    @Test
    public void unterminatedDictionaryFailsWithOffset() {
        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> parse("  << /Type /Page /Kids [1 0 R]"));

        assertEquals("offset of the end of the file", 30, e.offset());
        assertThat(e).hasMessageContaining("dictionary opened at offset 2 is not closed with '>>'");
    }

    @Test
    public void unterminatedArrayFailsWithOffset() {
        PdfFormatException e = assertThrows(PdfFormatException.class, () -> parse("[1 [2]"));

        assertThat(e).hasMessageContaining("array opened at offset 0 is not closed with ']'");
    }

    @Test
    public void dictionaryKeysMustBeNames() {
        PdfFormatException e = assertThrows(PdfFormatException.class, () -> parse("<< /A 1 (B) 2 >>"));

        assertEquals("offset", 8, e.offset());
        assertThat(e).hasMessageContaining("key must be a name, found '(B)'");
    }

    @Test
    public void dictionaryKeyWithoutAValueFails() {
        assertThat(assertThrows(PdfFormatException.class, () -> parse("<< /A 1 /B >>")))
                .hasMessageContaining("key /B has no value");
    }

    @Test
    public void closingDelimiterOrEndWhereAnObjectBelongsFails() {
        assertThat(assertThrows(PdfFormatException.class, () -> parse("]")))
                .hasMessageContaining("found ']'");
        assertThat(assertThrows(PdfFormatException.class, () -> parse(">>")))
                .hasMessageContaining("found '>>'");
        assertThat(assertThrows(PdfFormatException.class, () -> parse("  ")))
                .hasMessageContaining("found the end of the file");
        assertThat(assertThrows(PdfFormatException.class, () -> parse("{ 1 }")))
                .hasMessageContaining("found the keyword '{'");
    }

    @Test
    public void indirectObjectRecordsItsNumberAndOffset() {
        PdfIndirectObject object = parseIndirect("\n  4 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        assertEquals("reference", new PdfReference(4, 0), object.reference());
        assertEquals("offset", 3, object.offset());
        assertEquals("object", PdfDictionary.of("Type", PdfName.of("Catalog"),
                "Pages", new PdfReference(2, 0)), object.object());
    }

    @Test
    public void indirectObjectCanBeANumber() {
        // How writers store a stream's /Length when they write it after the stream.
        assertEquals("object", n(1234), parseIndirect("13 0 obj 1234 endobj").object());
        assertEquals("a reference", new PdfReference(2, 0), parseIndirect("13 0 obj 2 0 R endobj").object());
    }

    @Test
    public void streamWithDirectLength() {
        String pdf = "5 0 obj\n<< /Length 11 >>\nstream\nBT (Hi) Tj\nendstream\nendobj";

        PdfStream stream = (PdfStream) parseIndirect(pdf).object();

        assertEquals("data", "BT (Hi) Tj\n", new String(stream.data(), StandardCharsets.US_ASCII));
        assertEquals("data offset", pdf.indexOf("BT"), stream.dataOffset());
        assertEquals("dictionary", PdfDictionary.of("Length", n(11)), stream.dictionary());
        assertEquals("text form", "<</Length 11>> stream[11 bytes]", stream.toString());
    }

    @Test
    public void streamWithIndirectLength() {
        String pdf = "5 0 obj\n<< /Length 6 0 R >>\nstream\nBT (Hi) Tj\nendstream\nendobj\n6 0 obj 10 endobj";

        PdfStream stream = (PdfStream) new PdfParser(ascii(pdf),
                ref -> ref.equals(new PdfReference(6, 0)) ? n(10) : null).parseIndirectObject().object();

        assertEquals("data", "BT (Hi) Tj", new String(stream.data(), StandardCharsets.US_ASCII));
    }

    @Test
    public void streamAfterCrLfAndBinaryDataThatLooksLikeEndstream() {
        // /Length, not the word endstream, decides where the data ends.
        byte[] data = ascii("1 0 obj<</Length 12>>stream\r\n\u0000endstreamÿ\u0080\r\nendstream endobj");

        PdfStream stream = (PdfStream) new PdfParser(data).parseIndirectObject().object();

        assertThat(stream.data()).containsExactly(ascii("\u0000endstreamÿ\u0080"));
        assertEquals("length", 12, stream.length());
    }

    @Test
    public void wrongStreamLengthFailsAtWhereEndstreamShouldBe() {
        String pdf = "5 0 obj << /Length 4 >> stream\nBT (Hi) Tj\nendstream endobj";

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> parseIndirect(pdf));

        assertEquals("offset of what follows the 4 bytes", pdf.indexOf("BT") + 4, e.offset());
        assertThat(e).hasMessageContaining("stream of object 5 0 R expected 'endstream' after /Length 4 bytes");
    }

    @Test
    public void streamLengthPastTheEndOfTheFileFails() {
        assertThat(assertThrows(PdfFormatException.class,
                () -> parseIndirect("5 0 obj << /Length 99 >> stream\nabc\nendstream endobj")))
                .hasMessageContaining("has /Length 99 but only");
    }

    @Test
    public void streamLengthThatIsntANonNegativeIntegerFails() {
        assertThat(assertThrows(PdfFormatException.class,
                () -> parseIndirect("5 0 obj << /Length -1 >> stream\nabc\nendstream endobj")))
                .hasMessageContaining("needs a non-negative integer /Length, found -1");
        assertThat(assertThrows(PdfFormatException.class,
                () -> parseIndirect("5 0 obj << >> stream\nabc\nendstream endobj")))
                .hasMessageContaining("found null");
    }

    @Test
    public void indirectLengthWithNoWayToResolveItFails() {
        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> parseIndirect("5 0 obj << /Length 6 0 R >> stream\nabc\nendstream endobj"));

        assertThat(e).hasMessageContaining("has /Length 6 0 R, which can't be resolved");
    }

    @Test
    public void streamMustFollowADictionary() {
        assertThat(assertThrows(PdfFormatException.class,
                () -> parseIndirect("5 0 obj [1] stream\nabc\nendstream endobj")))
                .hasMessageContaining("'stream' must follow a dictionary");
    }

    @Test
    public void missingEndobjFails() {
        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> parseIndirect("5 0 obj << /A 1 >> 6 0 obj"));

        assertEquals("offset", 19, e.offset());
        assertThat(e).hasMessageContaining("object 5 0 R expected 'endobj', found '6'");
    }

    @Test
    public void badObjectHeaderFails() {
        assertThat(assertThrows(PdfFormatException.class, () -> parseIndirect("5 obj 1 endobj")))
                .hasMessageContaining("expected 'n g obj', found '5 obj 1'");
        assertThat(assertThrows(PdfFormatException.class, () -> parseIndirect("-5 0 obj 1 endobj")))
                .hasMessageContaining("expected 'n g obj'");
    }

    @Test
    public void parserCanSeekBetweenObjects() {
        String pdf = "1 0 obj (one) endobj 2 0 obj (two) endobj";
        PdfParser parser = new PdfParser(ascii(pdf));

        parser.seek(pdf.indexOf("2 0 obj"));
        assertEquals("second", PdfString.of("two"), parser.parseIndirectObject().object());
        parser.seek(0);
        assertEquals("first", PdfString.of("one"), parser.parseIndirectObject().object());
        assertEquals("position after the first", pdf.indexOf(" 2 0 obj"), parser.position());
    }

    @Test
    public void positionAccountsForTokensReadAhead() {
        PdfParser parser = new PdfParser(ascii("7 8 9"));

        parser.parseObject(); // read 7, then 8 and 9 to see whether it was "7 8 R"

        assertEquals("position", 2, parser.position());
        assertEquals("next", n(8), parser.parseObject());
    }
}
