package dev.yjimmy.printlab.pdf;

import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RegionTextExtractorTest {

    /** Glyphs 5 wide at size 10 with a 2.5-wide space, one per character of {@code text}. */
    private static List<Glyph> word(String text, double x, double y) {
        List<Glyph> glyphs = new ArrayList<>();
        for (char c : text.toCharArray()) {
            glyphs.add(new Glyph(String.valueOf(c), x, y, 5, 10, 2.5));
            x += 5;
        }
        return glyphs;
    }

    private static List<Glyph> all(List<List<Glyph>> words) {
        return words.stream().flatMap(List::stream).toList();
    }

    /** Left and top edges are in, right and bottom edges out, as Rectangle2D.contains has it. */
    @Test
    public void aGlyphBelongsWhereItsBaselineStarts() {
        List<Glyph> glyphs = all(List.of(word("A", 100, 50), word("B", 110, 60), word("C", 120, 50)));
        Map<String, Rectangle2D> regions = new LinkedHashMap<>();
        regions.put("leftTopEdges", new Rectangle2D.Double(100, 50, 5, 5));
        regions.put("rightEdge", new Rectangle2D.Double(90, 45, 10, 10));
        regions.put("bottomEdge", new Rectangle2D.Double(105, 50, 10, 10));
        regions.put("glyphBodyOnly", new Rectangle2D.Double(95, 42, 40, 7));

        assertEquals(Map.of("leftTopEdges", "A", "rightEdge", "", "bottomEdge", "", "glyphBodyOnly", ""),
                RegionTextExtractor.extract(glyphs, regions));
    }

    /** PDFBox would give the shared glyphs to only one of the two; see RegionExtractorVsPdfBoxTest. */
    @Test
    public void overlappingRegionsEachGetTheirGlyphs() {
        Map<String, Rectangle2D> regions = new LinkedHashMap<>();
        regions.put("left", new Rectangle2D.Double(0, 0, 120, 100));
        regions.put("all", new Rectangle2D.Double(0, 0, 200, 100));

        Map<String, String> text = RegionTextExtractor.extract(all(List.of(word("ab", 100, 50), word("cd", 150, 50))), regions);

        assertEquals("ab", text.get("left"));
        assertEquals("ab cd", text.get("all"));
    }

    /** Lines top to bottom whatever order they were drawn in; within a line, left to right. */
    @Test
    public void linesAreInReadingOrder() {
        List<Glyph> glyphs = all(List.of(word("third", 10, 90), word("first", 10, 30), word("line", 45, 61),
                word("second", 10, 60)));

        assertEquals("first\nsecond line\nthird", RegionTextExtractor.text(glyphs));
    }

    /** A gap of more than half a space is a word break; drawn spaces aren't doubled. */
    @Test
    public void wordGapsBecomeSpaces() {
        assertEquals("narrow gap", "ab", RegionTextExtractor.text(all(List.of(word("a", 0, 0), word("b", 6.2, 0)))));
        assertEquals("wide gap", "a b", RegionTextExtractor.text(all(List.of(word("a", 0, 0), word("b", 6.3, 0)))));
        assertEquals("drawn space", "a b", RegionTextExtractor.text(word("a b", 0, 0)));
        assertEquals("drawn space, then a gap", "a b", RegionTextExtractor.text(
                all(List.of(word("a ", 0, 0), word("b", 30, 0)))));
        assertEquals("no space width: half the glyph", "a b", RegionTextExtractor.text(List.of(
                new Glyph("a", 0, 0, 4, 10, 0), new Glyph("b", 6.1, 0, 4, 10, 0))));
        assertEquals("nothing", "", RegionTextExtractor.text(List.of()));
    }

    @Test
    public void aPageIsReadStraightIntoRegions() {
        PdfDocumentReader pdf = TextPositionTest.page("", "BT /F1 10 Tf 100 700 Td (Hello) Tj ET",
                TextPositionTest.HELVETICA, new PdfBytes("1.4"));

        assertEquals(Map.of("greeting", "Hello"), RegionTextExtractor.extract(pdf, pdf.pages().get(0),
                Map.of("greeting", new Rectangle2D.Double(90, 80, 100, 20))));
    }
}
