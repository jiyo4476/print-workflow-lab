package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/** Content streams as operators (7.8.2) and inline images (8.9.7). */
public class ContentStreamParserTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<Operation> parse(String content) {
        return ContentStreamParser.parse(ascii(content));
    }

    /** {@code text}, then {@code data}, then {@code rest}, as one content stream. */
    private static byte[] content(String text, byte[] data, String rest) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii(text));
        out.writeBytes(data);
        out.writeBytes(ascii(rest));
        return out.toByteArray();
    }

    private static String failure(String content) {
        return assertThrows(PdfFormatException.class, () -> parse(content)).getMessage();
    }

    @Test
    public void operandsAttachToFollowingOperator() {
        List<Operation> ops = parse("BT\n/F1 12 Tf\n72 752 Td\n(Hello) Tj\nET");

        assertThat(ops).extracting(Operation::operator).containsExactly("BT", "Tf", "Td", "Tj", "ET");
        assertEquals("Tf operands", List.of(PdfName.of("F1"), PdfNumber.of(12)), ops.get(1).operands());
        assertEquals("Td operands", List.of(PdfNumber.of(72), PdfNumber.of(752)), ops.get(2).operands());
        assertEquals("Tj operand", List.of(PdfString.of("Hello")), ops.get(3).operands());
        assertEquals("BT has none", List.of(), ops.get(0).operands());
        assertEquals("offset of Td's first operand", 13, ops.get(2).offset());
        assertEquals("printed", "/F1 12 Tf", ops.get(1).toString());
    }

    @Test
    public void tjArrayWithKerningNumbers() {
        Operation tj = parse("[(A) 120 (W) -50.5 (A)] TJ").get(0);

        assertEquals("operator", "TJ", tj.operator());
        PdfArray array = (PdfArray) tj.operands().get(0);
        assertEquals("items", List.of(PdfString.of("A"), PdfNumber.of(120), PdfString.of("W"),
                new PdfNumber(new java.math.BigDecimal("-50.5"), true), PdfString.of("A")), array.items());
        assertEquals("printed", "[(A) 120 (W) -50.5 (A)] TJ", tj.toString());
    }

    @Test
    public void everyOperandTypeAndTheQuoteOperators() {
        List<Operation> ops = parse("/Span << /MCID 3 /Alt (x) >> BDC EMC true false null 1 0 0 1 0 0 d0 "
                + "% a comment\n(line) ' 2 1 (more) \" <48 49> Tj");

        assertThat(ops).extracting(Operation::operator).containsExactly("BDC", "EMC", "d0", "'", "\"", "Tj");
        assertEquals("dictionary operand", PdfDictionary.of("MCID", PdfNumber.of(3), "Alt", PdfString.of("x")),
                ops.get(0).operands().get(1));
        assertEquals("constants", List.of(PdfBoolean.TRUE, PdfBoolean.FALSE, PdfNull.INSTANCE),
                ops.get(2).operands().subList(0, 3));
        assertEquals("\" takes three", 3, ops.get(4).operands().size());
        assertEquals("hex string", PdfString.of("HI"), ops.get(5).operands().get(0));
    }

    /** Content has no references: {@code 1 0 R} is two numbers and an unknown operator R. */
    @Test
    public void unknownOperatorsAreReturnedForTheCallerToJudge() {
        List<Operation> ops = parse("BX 1 0 R foo EX");

        assertThat(ops).extracting(Operation::toString).containsExactly("BX", "1 0 R", "foo", "EX");
    }

    @Test
    public void emptyContentHasNoOperations() {
        assertEquals("empty", List.of(), parse(""));
        assertEquals("white space and comments", List.of(), parse(" \r\n% nothing here\n"));
        ContentStreamParser parser = new ContentStreamParser(ascii("q"));
        assertEquals("one", "q", parser.next().operator());
        assertNull("then the end", parser.next());
        assertNull("and stays there", parser.next());
    }

    /**
     * An unfiltered 4 x 2 gray image is 8 bytes, which here are {@code " EI 0 0 "}: an
     * {@code EI} with white space around it and content after it. The size from the image's
     * parameters ends the data in the right place.
     */
    @Test
    public void inlineImageContainingEiBytesIsSkipped() {
        byte[] data = ascii(" EI 0 0 ");
        List<Operation> ops = ContentStreamParser.parse(content("q BI /W 4 /H 2 /BPC 8 /CS /G ID ", data, "\nEI Q"));

        assertThat(ops).extracting(Operation::operator).containsExactly("q", "BI", "Q");
        Operation bi = ops.get(1);
        assertTrue("inline image", bi.isInlineImage());
        PdfStream image = (PdfStream) bi.operands().get(0);
        assertThat(image.data()).containsExactly(data);
        assertEquals("parameters", PdfDictionary.of("W", PdfNumber.of(4), "H", PdfNumber.of(2),
                "BPC", PdfNumber.of(8), "CS", PdfName.of("G")), image.dictionary());
        assertEquals("data offset", 32, image.dataOffset());
        assertEquals("printed", "BI <</W 4 /H 2 /BPC 8 /CS /G>> ID 8 bytes EI", bi.toString());
    }

    /** PDF 2.0's {@code /L} gives the length, whatever the data looks like. */
    @Test
    public void inlineImageLengthIsUsedWhenGiven() {
        byte[] data = ascii("xx EI Q q EI yy");
        List<Operation> ops = ContentStreamParser.parse(content("BI /W 100 /H 100 /F /DCT /L 15 ID ", data, " EI Q"));

        assertThat(ops).extracting(Operation::operator).containsExactly("BI", "Q");
        assertThat(((PdfStream) ops.get(0).operands().get(0)).data()).containsExactly(data);
    }

    /**
     * A Flate-compressed image without a length: the first {@code EI} is followed by binary
     * bytes, so it's part of the data; the second is followed by content.
     */
    @Test
    public void filteredInlineImageEndsAtTheEiFollowedByContent() {
        byte[] data = {0x78, (byte) 0x9C, '\n', 'E', 'I', '\n', 0x00, (byte) 0xFF, 0x01};
        List<Operation> ops = ContentStreamParser.parse(content("BI /W 1 /H 1 /F /Fl ID ", data, "\nEI\nQ"));

        assertThat(ops).extracting(Operation::operator).containsExactly("BI", "Q");
        assertThat(((PdfStream) ops.get(0).operands().get(0)).data()).containsExactly(data);
        assertEquals("EI at the very end", 1, ContentStreamParser.parse(
                content("BI /F /Fl ID ", new byte[] {1, 2}, " EI")).size());
        assertEquals("no data", 0, ((PdfStream) ContentStreamParser.parse(ascii("BI /F /AHx ID EI"))
                .get(0).operands().get(0)).length());
    }

    @Test
    public void imageParametersGiveTheDataSize() {
        assertEquals("1-bit mask, 10 wide: 2 bytes a row", 6,
                ContentStreamParser.unfilteredSize(PdfDictionary.of("W", PdfNumber.of(10), "H", PdfNumber.of(3),
                        "IM", PdfBoolean.TRUE)));
        assertEquals("RGB, long names", 2 * 3 * 2, ContentStreamParser.unfilteredSize(PdfDictionary.of(
                "Width", PdfNumber.of(2), "Height", PdfNumber.of(2), "BitsPerComponent", PdfNumber.of(8),
                "ColorSpace", PdfName.of("DeviceRGB"))));
        assertEquals("CMYK at 16 bits", 8, ContentStreamParser.unfilteredSize(PdfDictionary.of(
                "W", PdfNumber.of(1), "H", PdfNumber.of(1), "BPC", PdfNumber.of(16), "CS", PdfName.of("CMYK"))));
        assertEquals("indexed: one component", 1, ContentStreamParser.unfilteredSize(PdfDictionary.of(
                "W", PdfNumber.of(2), "H", PdfNumber.of(1), "BPC", PdfNumber.of(4),
                "CS", PdfArray.of(PdfName.of("I"), PdfName.of("RGB"), PdfNumber.of(1), PdfString.of("abcdef")))));
        assertEquals("named color space: unknown", -1, ContentStreamParser.unfilteredSize(PdfDictionary.of(
                "W", PdfNumber.of(2), "H", PdfNumber.of(1), "BPC", PdfNumber.of(8), "CS", PdfName.of("CS0"))));
        assertEquals("odd bits", -1, ContentStreamParser.unfilteredSize(PdfDictionary.of(
                "W", PdfNumber.of(2), "H", PdfNumber.of(1), "BPC", PdfNumber.of(3), "CS", PdfName.of("G"))));
        assertEquals("too big for an int", -1, ContentStreamParser.unfilteredSize(PdfDictionary.of(
                "W", PdfNumber.of(100_000), "H", PdfNumber.of(100_000), "BPC", PdfNumber.of(8),
                "CS", PdfName.of("RGB"))));
    }

    /** An image whose size can't be worked out falls back to the scan for EI. */
    @Test
    public void namedColorSpaceImageIsScannedForEi() {
        List<Operation> ops = ContentStreamParser.parse(
                content("BI /W 2 /H 1 /BPC 8 /CS /CS0 ID ", new byte[] {(byte) 0x80, (byte) 0x81}, " EI Q"));

        assertThat(((PdfStream) ops.get(0).operands().get(0)).data()).containsExactly(0x80, 0x81);
    }

    @Test
    public void malformedInlineImagesFailWithTheirOffset() {
        assertThat(failure("BI /W 1 /H 1")).contains("(0)").contains("inline image has no 'ID'");
        assertThat(failure("BI 5 6 ID x EI")).contains("inline image key must be a name, found '5'");
        assertThat(failure("BI /W ID x EI")).contains("inline image key /W has no value");
        assertThat(failure("BI /W 1 /H 1 /F /Fl ID\tx")).contains("inline image has no 'EI' after its data");
        assertThat(failure("BI /F /Fl ID")).contains("'ID' must be followed by one white-space byte");
        assertThat(failure("BI /L 10 ID abc")).contains("inline image has no 'EI' after its data");
        assertThat(failure("BI /L -1 ID abc EI")).contains("inline image length must be a whole number, found -1");
        assertThat(failure("1 BI /L 0 ID  EI")).contains("operands before 'BI'");
    }

    /** A size the data doesn't match (wrong parameters, a bad /L) falls back to the scan for EI. */
    @Test
    public void wrongLengthFallsBackToTheScanForEi() {
        List<Operation> ops = ContentStreamParser.parse(ascii("BI /L 2 ID abc EI Q"));
        assertThat(((PdfStream) ops.get(0).operands().get(0)).data()).containsExactly('a', 'b', 'c');
        assertEquals("then Q", "Q", ops.get(1).operator());

        List<Operation> tooLong = ContentStreamParser.parse(ascii("BI /W 9 /H 9 /BPC 8 /CS /G ID xy EI Q"));
        assertThat(((PdfStream) tooLong.get(0).operands().get(0)).data()).containsExactly('x', 'y');
    }

    /** Some writers put CR LF after ID; with a known size, both bytes are the separator. */
    @Test
    public void crLfAfterIdIsOneSeparatorForAKnownSize() {
        byte[] data = {1, 2, 3, 4};
        List<Operation> ops = ContentStreamParser.parse(content("BI /W 4 /H 1 /BPC 8 /CS /G ID\r\n", data, "\nEI Q"));

        assertThat(((PdfStream) ops.get(0).operands().get(0)).data()).containsExactly(data);
        assertEquals("then Q", "Q", ops.get(1).operator());
    }

    /**
     * After the real EI may come a raw WinAnsi string with accented letters, or a delimiter;
     * both still end the image. The first EI is followed by a control byte, so it's data.
     */
    @Test
    public void eiFollowedByAccentedTextOrADelimiterEndsTheImage() {
        byte[] data = {0x78, ' ', 'E', 'I', ' ', 0x01, 0x02};
        byte[] text = content("BI /F /Fl ID ", data, " EI BT (Caf" + (char) 0xE9 + ") Tj ET");
        List<Operation> ops = ContentStreamParser.parse(text);
        assertThat(ops).extracting(Operation::operator).containsExactly("BI", "BT", "Tj", "ET");
        assertThat(((PdfStream) ops.get(0).operands().get(0)).data()).containsExactly(data);

        List<Operation> delimiter = ContentStreamParser.parse(content("BI /F /Fl ID ", new byte[] {9, 9}, " EI/GS0 gs"));
        assertThat(delimiter).extracting(Operation::toString).containsExactly("BI <</F /Fl>> ID 2 bytes EI", "/GS0 gs");
    }

    @Test
    public void operandsAndArrayItemsAreLimited() {
        String ok = "1 ".repeat(ContentStreamParser.MAX_OPERANDS) + "sc";
        assertEquals("at the limit", ContentStreamParser.MAX_OPERANDS, parse(ok).get(0).operands().size());

        assertThat(failure("q " + "1 ".repeat(ContentStreamParser.MAX_OPERANDS + 1) + "sc"))
                .contains("(2)").contains("more than 1000 operands before an operator");
        assertThat(failure("true ".repeat(ContentStreamParser.MAX_OPERANDS + 1) + "sc"))
                .contains("more than 1000 operands");
        assertThat(failure("[" + "0 ".repeat(ContentStreamParser.MAX_ITEMS + 1) + "] TJ"))
                .contains("(0)").contains("array has more than 100000 items");
    }

    /** An operator can hold any regular byte; printed, control and high bytes are #xx. */
    @Test
    public void operatorBytesArePrintedEscaped() {
        String escape = String.valueOf((char) 0x1B);
        List<Operation> ops = parse("1 x" + escape + "2J" + (char) 0xE9 + "#");

        assertEquals("kept as read", "x" + escape + "2J" + (char) 0xE9 + "#", ops.get(0).operator());
        assertEquals("printed", "1 x#1B2J#E9#23", ops.get(0).toString());
        assertThat(failure("[(a) " + escape + "z] TJ")).contains("'#1Bz'").doesNotContain(escape);
    }

    @Test
    public void malformedContentFailsWithItsOffset() {
        assertThat(failure("q 1 0 0")).contains("(2)").contains("3 operand(s) at the end of the content");
        assertThat(failure("q ID")).contains("'ID' outside an inline image");
        assertThat(failure("EI")).contains("'EI' outside an inline image");
        assertThat(failure("{ 1 }")).contains("braces are only for PostScript functions");
        assertThat(failure("] TJ")).contains("']' with nothing open");
        assertThat(failure("[(a) TJ")).contains("expected an object, found the keyword 'TJ'");
    }

    @Test
    public void operatorTableHasAllOfAnnexA() {
        assertEquals("Table A.1", 73, ContentOperators.count());
        assertEquals("described", "font and size", ContentOperators.describe("Tf").orElseThrow());
        assertFalse("not an operator", ContentOperators.isKnown("R"));
        assertThrows(IllegalArgumentException.class, () -> Operation.of(""));
    }
}
