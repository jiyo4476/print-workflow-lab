package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testdata.Statement;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.Test;

public class TextPositionTest {

    static final String HELVETICA = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>";

    /** A one-page PDF: {@code pageExtra} in the page, object 4 its content, 5 the font /F1, then {@code more}. */
    static PdfDocumentReader page(String pageExtra, String content, String font, PdfBytes more) {
        return new PdfDocumentReader(more
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R"
                        + " /Resources << /Font << /F1 5 0 R >> /XObject << /Fm1 6 0 R >> >> " + pageExtra + " >>")
                .stream(4, "", content.getBytes(StandardCharsets.ISO_8859_1))
                .object(5, font)
                .build("/Root 1 0 R"));
    }

    static List<Glyph> glyphs(String content) {
        return glyphs(page("", content, HELVETICA, new PdfBytes("1.4")));
    }

    static List<Glyph> glyphs(PdfDocumentReader pdf) {
        return new TextPositionExtractor(pdf).glyphs(pdf.pages().get(0));
    }

    private static String text(List<Glyph> glyphs) {
        return glyphs.stream().map(Glyph::text).collect(Collectors.joining());
    }

    private static void assertAt(Glyph glyph, double x, double y) {
        assertThat(glyph.x()).as(glyph + " x").isCloseTo(x, within(0.001));
        assertThat(glyph.y()).as(glyph + " y").isCloseTo(y, within(0.001));
    }

    /** StatementLayout puts the account number's baseline at (400, 40) from the top-left. */
    @Test
    public void accountNumberFoundAtGeneratorPosition() throws IOException {
        Statement statement = new StatementDataGenerator(42).generate(1, 0).get(0);
        byte[] pdf;
        try (PDDocument doc = StatementPdfGenerator.document(List.of(statement))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        List<Glyph> glyphs = glyphs(new PdfDocumentReader(pdf));

        int first = -1;
        for (int i = 0; i < glyphs.size() && first < 0; i++) {
            Glyph g = glyphs.get(i);
            if (Math.abs(g.x() - 400) <= 0.5 && Math.abs(g.y() - 40) <= 0.5) {
                first = i;
            }
        }
        assertThat(first).as("a glyph at (400, 40)").isNotNegative();
        String account = statement.accountNumber();
        assertEquals("the account number starts there", account,
                text(glyphs.subList(first, first + account.length())));
        assertEquals("font size", 10, glyphs.get(first).fontSize(), 1e-9);
    }

    /** Helvetica's A is 667/1000 wide, so at 10 pt the next glyph is 6.67 pt along. */
    @Test
    public void glyphsAdvanceByTheirAfmWidths() {
        List<Glyph> glyphs = glyphs("BT /F1 10 Tf 100 700 Td (AB C) Tj ET");

        assertEquals("AB C", text(glyphs));
        assertAt(glyphs.get(0), 100, 92);
        assertAt(glyphs.get(1), 106.67, 92);
        assertAt(glyphs.get(2), 113.34, 92);
        assertAt(glyphs.get(3), 116.12, 92);
        assertEquals("A's width", 6.67, glyphs.get(0).width(), 1e-9);
        assertEquals("space width", 2.78, glyphs.get(0).spaceWidth(), 1e-9);
    }

    /** A TJ number moves the next glyph back by thousandths of text space: -500 at 10 pt is 5 pt right. */
    @Test
    public void tjKerningShiftsFollowingGlyphs() {
        List<Glyph> glyphs = glyphs("BT /F1 10 Tf 100 700 Td [(A) -500 (B) 250 (C)] TJ ET");

        assertEquals("ABC", text(glyphs));
        assertAt(glyphs.get(0), 100, 92);
        assertAt(glyphs.get(1), 100 + 6.67 + 5, 92);
        assertAt(glyphs.get(2), 100 + 6.67 + 5 + 6.67 - 2.5, 92);
    }

    /** Tc after every glyph, Tw after a space too, all times Tz. */
    @Test
    public void charAndWordSpacingAndHorizontalScaling() {
        List<Glyph> glyphs = glyphs("BT /F1 10 Tf 2 Tc 10 Tw 50 Tz 100 700 Td (A B) Tj ET");

        assertAt(glyphs.get(1), 100 + (6.67 + 2) * 0.5, 92);
        assertAt(glyphs.get(2), 100 + (6.67 + 2) * 0.5 + (2.78 + 2 + 10) * 0.5, 92);
        assertEquals("half-width A", 3.335, glyphs.get(0).width(), 1e-9);
    }

    @Test
    public void quoteOperatorsShowTheirStringOnTheNextLine() {
        List<Glyph> glyphs = glyphs("BT /F1 10 Tf 12 TL 72 700 Td (A) Tj (B) ' 0 0 (C) \" ET");

        assertAt(glyphs.get(0), 72, 92);
        assertAt(glyphs.get(1), 72, 104);
        assertAt(glyphs.get(2), 72, 116);
    }

    /** The CTM and the crop box both move the origin; x and y are measured from the crop box. */
    @Test
    public void ctmAndCropBoxMoveTheOrigin() {
        PdfDocumentReader pdf = page("/CropBox [10 20 600 780]",
                "2 0 0 2 50 50 cm BT /F1 10 Tf 10 10 Td (A) Tj ET", HELVETICA, new PdfBytes("1.4"));
        Glyph a = glyphs(pdf).get(0);

        // Origin (10, 10) scaled by 2 and moved by 50: (70, 70) in user space.
        assertAt(a, 70 - 10, 780 - 70);
        assertEquals("font size on the page", 20, a.fontSize(), 1e-9);
        assertEquals("width on the page", 13.34, a.width(), 1e-9);
    }

    @Test
    public void textInAFormXObjectIsFound() {
        PdfDocumentReader pdf = page("", "q 1 0 0 1 0 -100 cm /Fm1 Do Q BT /F1 10 Tf 0 0 Td (B) Tj ET", HELVETICA,
                new PdfBytes("1.4").stream(6, "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Matrix [1 0 0 1 50 0]",
                        "BT /F1 10 Tf 100 700 Td (A) Tj ET q".getBytes(StandardCharsets.US_ASCII)));
        List<Glyph> glyphs = glyphs(pdf);

        assertEquals("AB", text(glyphs));
        assertAt(glyphs.get(0), 150, 192);
        assertAt(glyphs.get(1), 0, 792);
    }

    @Test
    public void imagesAndMissingXObjectsAreSkipped() {
        PdfDocumentReader pdf = page("", "/Fm1 Do /Nope Do BT /F1 10 Tf (A) Tj ET", HELVETICA,
                new PdfBytes("1.4").stream(6, "/Type /XObject /Subtype /Image /Width 1 /Height 1", new byte[] {0}));

        assertEquals("A", text(glyphs(pdf)));
    }

    @Test
    public void aFormThatDrawsItselfFails() {
        PdfDocumentReader pdf = page("", "/Fm1 Do", HELVETICA, new PdfBytes("1.4").stream(6,
                "/Subtype /Form /Resources << /XObject << /Fm1 6 0 R >> >>", "/Fm1 Do".getBytes(StandardCharsets.US_ASCII)));

        assertThat(assertThrows(UnsupportedContentException.class, () -> glyphs(pdf)).getMessage())
                .contains("nested more than 20 deep").contains("/Fm1");
    }

    /** /Fm1 draws /Fm2 four times, and the page draws /Fm1 four times: 16 A's from 2 small forms. */
    private static PdfDocumentReader formsDrawingForms() {
        String fourTimes = "/Fm2 Do /Fm2 Do /Fm2 Do /Fm2 Do";
        return page("", fourTimes.replace("Fm2", "Fm1"), HELVETICA, new PdfBytes("1.4")
                .stream(6, "/Subtype /Form /Resources << /XObject << /Fm2 7 0 R >> >>",
                        fourTimes.getBytes(StandardCharsets.US_ASCII))
                .stream(7, "/Subtype /Form /Resources << /Font << /F1 5 0 R >> >>",
                        "BT /F1 10 Tf (A) Tj ET".getBytes(StandardCharsets.US_ASCII)));
    }

    /** Forms that draw each other many times over could make a few bytes into billions of glyphs. */
    @Test
    public void formsDrawnOverAndOverFailPastTheContentLimit() {
        PdfDocumentReader pdf = formsDrawingForms();
        assertEquals("within the limits", "A".repeat(16), text(glyphs(pdf)));

        // The page's 31 bytes, 31 for each /Fm1 drawn and 22 for each /Fm2: 470 in all, past 300 midway.
        assertThat(assertThrows(UnsupportedContentException.class,
                () -> new TextPositionExtractor(pdf, 300, 1000).glyphs(pdf.pages().get(0))).getMessage())
                .contains("page 1 draws more than 300 bytes of content with its form XObjects");
        assertThat(assertThrows(UnsupportedContentException.class,
                () -> new TextPositionExtractor(pdf, 1000, 10).glyphs(pdf.pages().get(0))).getMessage())
                .contains("page 1 shows more than 10 glyphs");
    }

    /** Text turned by cm or Tm can't be read as lines; a skew (oblique) and an upside-down y can. */
    @Test
    public void textThatIsNotHorizontalIsReported() {
        assertThat(assertThrows(UnsupportedContentException.class,
                () -> glyphs("0 1 -1 0 612 0 cm BT /F1 10 Tf (A) Tj ET")).getMessage())
                .contains("page 1 draws text that isn't horizontal and left to right");
        assertThat(assertThrows(UnsupportedContentException.class,
                () -> glyphs("BT /F1 10 Tf -1 0 0 1 300 300 Tm (A) Tj ET")).getMessage())
                .contains("isn't horizontal");

        assertAt(glyphs("BT /F1 10 Tf 1 0 0.2 1 100 700 Tm (A) Tj ET").get(0), 100, 92);
        assertAt(glyphs("1 0 0 -1 0 792 cm BT /F1 10 Tf 1 0 0 -1 100 100 Tm (A) Tj ET").get(0), 100, 100);
        assertEquals("zero-size text has no direction", 1, glyphs("BT /F1 0 Tf (A) Tj ET").size());
    }

    /** A font's own /Widths win over the AFM; /Differences renames codes, uniXXXX names included. */
    @Test
    public void widthsArrayAndDifferencesAreRead() {
        String font = "<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+Custom /FirstChar 65 /LastChar 67"
                + " /Widths [500 600 700] /FontDescriptor << /MissingWidth 250 >>"
                + " /Encoding << /BaseEncoding /WinAnsiEncoding /Differences [65 /Euro /uni00E9] >> >>";
        List<Glyph> glyphs = glyphs(page("", "BT /F1 10 Tf (ABCD) Tj ET", font, new PdfBytes("1.4")));

        assertEquals("€éCD", text(glyphs));
        assertAt(glyphs.get(1), 5, 792);
        assertAt(glyphs.get(2), 11, 792);
        assertAt(glyphs.get(3), 18, 792);
        assertEquals("D is past /LastChar: /MissingWidth", 2.5, glyphs.get(3).width(), 1e-9);
    }

    @Test
    public void cidFontReportedAsUnsupportedNotGarbled() {
        String type0 = "<< /Type /Font /Subtype /Type0 /BaseFont /MSGothic /Encoding /Identity-H"
                + " /DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /MSGothic >>] >>";
        PdfDocumentReader pdf = page("", "BT /F1 10 Tf <00410042> Tj ET", type0, new PdfBytes("1.4"));

        UnsupportedContentException e = assertThrows(UnsupportedContentException.class, () -> glyphs(pdf));
        assertThat(e.getMessage()).contains("unsupported font MSGothic").contains("/Type0 (CID-keyed)");
    }

    @Test
    public void otherFontsTheExtractorCantReadAreReported() {
        assertThat(unsupported("<< /Subtype /Type3 /BaseFont /T3 >>")).contains("/Type3 fonts aren't read");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /Helvetica /Encoding /MacRomanEncoding >>"))
                .contains("/MacRomanEncoding isn't read");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /Symbol >>"))
                .contains("a symbolic or embedded font's built-in encoding isn't read");
        assertThat(unsupported("<< /Subtype /TrueType /BaseFont /Arial /Encoding /WinAnsiEncoding >>"))
                .contains("unsupported font Arial: it has no /Widths and isn't one of the Standard 14 fonts");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /Helvetica /Encoding 3 >>"))
                .contains("its /Encoding is 3");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Differences [65 (A)] >> >>"))
                .contains("has (A) where a code or glyph name belongs");
    }

    /** Without a base encoding, an embedded or symbolic font uses its program's own, which isn't read. */
    @Test
    public void builtInEncodingsAreReportedNotGuessed() {
        String widths = " /FirstChar 65 /Widths [500]";
        assertThat(unsupported("<< /Subtype /TrueType /BaseFont /ABCDEF+Custom" + widths
                + " /FontDescriptor << /Flags 4 >> >>")).contains("a symbolic or embedded font's built-in encoding");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /ABCDEF+CMR10" + widths
                + " /FontDescriptor << /Flags 32 /FontFile 9 0 R >> >>")).contains("built-in encoding isn't read");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /ABCDEF+CMR10" + widths
                + " /FontDescriptor << /FontFile3 9 0 R >> /Encoding << /Differences [65 /B] >> >>"))
                .contains("its /Differences change a symbolic or embedded font's built-in encoding");
        assertThat(unsupported("<< /Subtype /Type1 /BaseFont /Symbol /Encoding << /Differences [65 /B] >> >>"))
                .contains("no /BaseEncoding");

        String embeddedWinAnsi = "<< /Subtype /TrueType /BaseFont /ABCDEF+Custom" + widths
                + " /FontDescriptor << /Flags 32 /FontFile2 9 0 R >> /Encoding << /BaseEncoding /WinAnsiEncoding"
                + " /Differences [65 /B] >> >>";
        assertEquals("a named base encoding is read", "B",
                text(glyphs(page("", "BT /F1 10 Tf (A) Tj ET", embeddedWinAnsi, new PdfBytes("1.4")))));
    }

    /** Codes past a Standard 14 font's /Widths, with no /MissingWidth, take the AFM width, as in PDFBox. */
    @Test
    public void standard14CodesOutsideWidthsUseTheAfm() {
        String font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding"
                + " /FirstChar 65 /LastChar 65 /Widths [600] >>";
        List<Glyph> glyphs = glyphs(page("", "BT /F1 10 Tf (AB) Tj ET", font, new PdfBytes("1.4")));

        assertEquals("A from /Widths", 6, glyphs.get(0).width(), 1e-9);
        assertEquals("B from the AFM", 6.67, glyphs.get(1).width(), 1e-9);
    }

    private static String unsupported(String font) {
        PdfDocumentReader pdf = page("", "BT /F1 10 Tf (A) Tj ET", font, new PdfBytes("1.4"));
        return assertThrows(UnsupportedContentException.class, () -> glyphs(pdf)).getMessage();
    }

    @Test
    public void rotatedPagesAreReported() {
        PdfDocumentReader pdf = page("/Rotate 90", "BT /F1 10 Tf (A) Tj ET", HELVETICA, new PdfBytes("1.4"));

        assertThat(assertThrows(UnsupportedContentException.class, () -> glyphs(pdf)).getMessage())
                .contains("page 1 is rotated (/Rotate 90)");
        assertEquals("360 is unrotated", 1,
                glyphs(page("/Rotate 360", "BT /F1 10 Tf (A) Tj ET", HELVETICA, new PdfBytes("1.4"))).size());
    }

    @Test
    public void malformedTextOperatorsFailWithTheirOffset() {
        assertThat(assertThrows(PdfFormatException.class, () -> glyphs("BT (A) Tj ET")).getMessage())
                .contains("(3)").contains("text is shown before any 'Tf' sets a font");
        assertThat(assertThrows(PdfFormatException.class, () -> glyphs("BT /F9 10 Tf (A) Tj ET")).getMessage())
                .contains("font /F9 isn't in the /Font resources");
        assertThat(assertThrows(PdfFormatException.class, () -> glyphs("BT /F1 10 Tf 5 Tj ET")).getMessage())
                .contains("'Tj' needs a string");
        assertThat(assertThrows(PdfFormatException.class, () -> glyphs("BT /F1 10 Tf (A) TJ ET")).getMessage())
                .contains("'TJ' needs one array");
        assertThat(assertThrows(PdfFormatException.class, () -> glyphs("BT /F1 10 Tf [/A] TJ ET")).getMessage())
                .contains("a 'TJ' array holds strings and numbers, not /A");
        assertThat(assertThrows(PdfFormatException.class, () -> glyphs("5 Do")).getMessage())
                .contains("'Do' needs one name");
    }
}
