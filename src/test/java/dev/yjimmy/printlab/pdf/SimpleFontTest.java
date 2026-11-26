package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.Test;

/** The encoding table and the AFM widths, with PDFBox as the oracle. */
public class SimpleFontTest {

    /** Somewhere for the fonts' references to resolve; these fonts have none. */
    private static PdfDocumentReader emptyPdf() {
        return TextPositionTest.page("", "", TextPositionTest.HELVETICA, new PdfBytes("1.4"));
    }

    @Test
    public void encodingTablesMatchPdfBox() {
        assertSameAs(SimpleEncoding.WIN_ANSI, WinAnsiEncoding.INSTANCE);
        assertSameAs(SimpleEncoding.STANDARD, StandardEncoding.INSTANCE);
    }

    private static void assertSameAs(SimpleEncoding ours, Encoding pdfBox) {
        for (int code = 0; code < 256; code++) {
            String name = pdfBox.getName(code);
            String expected = ".notdef".equals(name) ? null : name;
            assertEquals(ours + " code " + code, expected, ours.glyphName(code));
            assertEquals(ours + " text of " + code, expected == null ? "" : GlyphList.getAdobeGlyphList().toUnicode(name),
                    ours.text(code));
        }
    }

    /** The non-symbolic Standard 14 fonts, code by code. */
    @Test
    public void standard14WidthsMatchPdfBox() throws IOException {
        for (Standard14Fonts.FontName name : Standard14Fonts.FontName.values()) {
            if (name == Standard14Fonts.FontName.SYMBOL || name == Standard14Fonts.FontName.ZAPF_DINGBATS) {
                continue;
            }
            PDType1Font theirs = new PDType1Font(name);
            PdfDocumentReader pdf = emptyPdf();
            SimpleFont ours = SimpleFont.from(pdf, PdfDictionary.of("Subtype", PdfName.of("Type1"),
                    "BaseFont", PdfName.of(name.getName()), "Encoding", PdfName.of("WinAnsiEncoding")));
            for (int code = 32; code < 256; code++) {
                assertEquals(name.getName() + " code " + code, theirs.getWidth(code), ours.width(code), 0.0001);
            }
        }
    }

    @Test
    public void glyphNamesBecomeUnicode() {
        assertEquals("from the table", "Æ", SimpleEncoding.unicode("AE"));
        assertEquals("uniXXXX", "é", SimpleEncoding.unicode("uni00E9"));
        assertEquals("uniXXXXXXXX: a ligature", "fi", SimpleEncoding.unicode("uni00660069"));
        assertEquals("uXXXXX", new String(Character.toChars(0x1F600)), SimpleEncoding.unicode("u1F600"));
        assertEquals("a surrogate isn't text", SimpleEncoding.UNKNOWN, SimpleEncoding.unicode("uniD800"));
        assertEquals("not hex", SimpleEncoding.UNKNOWN, SimpleEncoding.unicode("uniZZZZ"));
        assertEquals("unknown name", SimpleEncoding.UNKNOWN, SimpleEncoding.unicode("g123"));
        assertEquals("a variant suffix is dropped", "A", SimpleEncoding.unicode("A.sc"));
        assertEquals("on uni names too", "é", SimpleEncoding.unicode("uni00E9.alt"));
        assertEquals("ligature parts are joined", "ffi", SimpleEncoding.unicode("f_f_i"));
        assertEquals("an unknown part spoils the ligature", SimpleEncoding.UNKNOWN, SimpleEncoding.unicode("f_g123"));
        assertEquals("an empty part too", SimpleEncoding.UNKNOWN, SimpleEncoding.unicode("f__i"));
        assertThat(SimpleEncoding.named("MacRomanEncoding")).isNull();
        assertEquals("WinAnsiEncoding", SimpleEncoding.named("WinAnsiEncoding").toString());
    }

    @Test
    public void differencesStartAtEachCode() {
        SimpleEncoding encoding = SimpleEncoding.STANDARD.withDifferences(PdfArray.of(PdfNumber.of(65),
                PdfName.of("B"), PdfName.of("C"), PdfNumber.of(255), PdfName.of("Z"), PdfName.of("past255")));

        assertEquals("A", "B", encoding.glyphName(65));
        assertEquals("then B", "C", encoding.glyphName(66));
        assertEquals("C unchanged", "C", encoding.glyphName(67));
        assertEquals("255", "Z", encoding.glyphName(255));
        assertEquals("StandardEncoding with /Differences", encoding.toString());
    }

    @Test
    public void aSubsetTagIsIgnoredForTheStandard14() {
        PdfDocumentReader pdf = emptyPdf();
        SimpleFont font = SimpleFont.from(pdf, PdfDictionary.of("Subtype", PdfName.of("Type1"),
                "BaseFont", PdfName.of("ABCDEF+Helvetica")));

        assertEquals("A in Helvetica", 667, font.width('A'), 0);
        assertEquals("StandardEncoding when none is given", SimpleEncoding.STANDARD, font.encoding());
        assertEquals("ABCDEF+Helvetica (StandardEncoding)", font.toString());
        assertEquals("ABCDEF+Helvetica", font.baseFont());
    }
}
