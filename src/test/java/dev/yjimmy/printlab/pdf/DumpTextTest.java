package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** {@code App dump-text}: each glyph on a page with its position, width and size. */
public class DumpTextTest {

    private static final String FIXTURE = "/fixtures/pdf/two-statements.pdf";

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = DumpTextTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    private static String dump(PdfDocumentReader pdf, int pageNumber, boolean expected) {
        StringBuilder out = new StringBuilder();
        assertEquals("returned", expected, new PdfDump().dumpText(pdf, pageNumber, out));
        return out.toString();
    }

    private static PdfDocumentReader page(String content, String font) {
        return TextPositionTest.page("", content, font, new PdfBytes("1.4"));
    }

    @Test
    public void twoStatementsPageOneMatchesGoldenDump() throws IOException {
        String golden = new String(resource("/fixtures/pdf/two-statements.text-1.dump.txt"), StandardCharsets.UTF_8);

        assertEquals("dump-text page 1", golden, dump(new PdfDocumentReader(resource(FIXTURE)), 1, true));
    }

    @Test
    public void eachGlyphIsOneLineWithItsPositionWidthAndSize() {
        String out = dump(page("BT /F1 10 Tf 72 700 Td (AV) Tj ET", TextPositionTest.HELVETICA), 1, true);

        assertEquals("dump", """
                Page 1 of 1  3 0 R
                text              x        y    width    size
                "A"           72.00    92.00     6.67   10.00
                "V"           78.67    92.00     6.67   10.00
                2 glyphs
                """, out);
        assertThat(dump(page("BT /F1 10 Tf (A) Tj ET", TextPositionTest.HELVETICA), 1, true)).endsWith("\n1 glyph\n");
        assertThat(dump(page("", TextPositionTest.HELVETICA), 1, true)).endsWith("size\n0 glyphs\n");
    }

    @Test
    public void unsupportedFontStopsTheDumpWithANote() {
        String type0 = "<< /Type /Font /Subtype /Type0 /BaseFont /MSGothic /Encoding /Identity-H"
                + " /DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /MSGothic >>] >>";

        String out = dump(page("BT /F1 10 Tf <00410042> Tj ET", type0), 1, false);

        assertThat(out).startsWith("Page 1 of 1  3 0 R\n% stopped: ").contains("unsupported font MSGothic")
                .doesNotContain("glyphs");
    }

    @Test
    public void rotatedPageAndBadContentStopTheDumpWithANote() {
        PdfDocumentReader rotated = TextPositionTest.page("/Rotate 90", "BT /F1 10 Tf (A) Tj ET",
                TextPositionTest.HELVETICA, new PdfBytes("1.4"));
        assertThat(dump(rotated, 1, false)).endsWith("% stopped: page 1 is rotated (/Rotate 90); only unrotated pages are read\n");

        assertThat(dump(page("BT /F1 10 Tf (open", TextPositionTest.HELVETICA), 1, false))
                .contains("% stopped: ").contains("literal string is not closed");
    }

    @Test
    public void pageOutsideTheDocumentIsReported() throws IOException {
        PdfDocumentReader pdf = new PdfDocumentReader(resource(FIXTURE));

        assertEquals("past the end", "Page 7 is not in the document (it has 6 pages)\n", dump(pdf, 7, false));
        assertEquals("zero", "Page 0 is not in the document (it has 6 pages)\n", dump(pdf, 0, false));
        assertThat(dump(page("", TextPositionTest.HELVETICA), 2, false)).endsWith("(it has 1 page)\n");
        assertFalse("not found", new PdfDump().dumpText(pdf, -1, new StringBuilder()));
    }

    @Test
    public void glyphTextIsEscapedNeverPrintedRaw() {
        String font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica"
                + " /Encoding << /BaseEncoding /WinAnsiEncoding /Differences [1 /uni001B /uni202E /uniE000] >> >>";

        String out = dump(page("BT /F1 10 Tf <01020322205C41> Tj ET", font), 1, true);

        assertThat(out).doesNotContain("\u001B").doesNotContain("\u202E").doesNotContain("\uE000")
                .contains("\n\"\\u001B\" ").contains("\n\"\\u202E\" ").contains("\n\"\\uE000\" ")
                .contains("\n\"\\\"\" ").contains("\n\" \" ").contains("\n\"\\\\\" ").contains("\n\"A\" ");
    }

    @Test
    public void quotedEscapesWhatCantBeShown() {
        assertEquals("empty", "\"\"", PdfDump.quoted(""));
        assertEquals("ligature text", "\"fi\"", PdfDump.quoted("fi"));
        assertEquals("accented", "\"é\"", PdfDump.quoted("é"));
        assertEquals("supplementary printable", "\"\uD835\uDC00\"", PdfDump.quoted("\uD835\uDC00"));
        assertEquals("lone surrogate", "\"\\uD800\"", PdfDump.quoted("\uD800"));
        assertEquals("line separator and DEL", "\"\\u2028\\u007F\"", PdfDump.quoted("\u2028\u007F"));
    }
}
