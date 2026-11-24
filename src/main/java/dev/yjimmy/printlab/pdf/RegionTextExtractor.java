package dev.yjimmy.printlab.pdf;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The text inside rectangular regions of a page, the way {@code SplitPdf} indexes documents with
 * {@code extractor: builtin}.
 *
 * <p>A glyph belongs to a region when the point where it starts on its baseline is inside the
 * rectangle ({@link Rectangle2D#contains(double, double)}: left and top edges in, right and
 * bottom edges out). That is PDFBox's rule too, so both extractors agree on which glyphs a
 * region holds. Two differences, both deliberate:
 * <ul>
 *   <li>Overlapping regions each get every glyph inside them. PDFBox gives a glyph to only one of
 *       them, because its suppression of duplicated (fake-bold) text is shared by all regions.</li>
 *   <li>Lines are put in reading order, top to bottom, then left to right; PDFBox keeps the
 *       order the content draws them in.</li>
 * </ul>
 *
 * <p>Glyphs whose baselines are within half a font size of each other are one line. Within a
 * line, a space is added where the gap between two glyphs is more than half a space wide (text
 * often positions words instead of drawing spaces). Lines are joined with {@code \n}.
 */
public final class RegionTextExtractor {

    private RegionTextExtractor() {
    }

    /** Reads {@code page} and returns each region's text, in the order of {@code regions}. */
    public static Map<String, String> extract(PdfDocumentReader pdf, PdfPage page, Map<String, ? extends Rectangle2D> regions) {
        return extract(new TextPositionExtractor(pdf).glyphs(page), regions);
    }

    /** Each region's text from {@code glyphs}, in the order of {@code regions}. */
    public static Map<String, String> extract(List<Glyph> glyphs, Map<String, ? extends Rectangle2D> regions) {
        Map<String, String> result = new LinkedHashMap<>();
        regions.forEach((name, region) -> result.put(name,
                text(glyphs.stream().filter(g -> region.contains(g.x(), g.y())).toList())));
        return result;
    }

    /** {@code glyphs} as lines of text in reading order. */
    public static String text(List<Glyph> glyphs) {
        List<Glyph> byLine = new ArrayList<>(glyphs);
        byLine.sort(Comparator.comparingDouble(Glyph::y));
        List<List<Glyph>> lines = new ArrayList<>();
        double lineY = Double.NaN;
        for (Glyph glyph : byLine) {
            if (lines.isEmpty() || Math.abs(glyph.y() - lineY) > glyph.fontSize() / 2) {
                lines.add(new ArrayList<>());
                lineY = glyph.y();
            }
            lines.get(lines.size() - 1).add(glyph);
        }
        StringBuilder text = new StringBuilder();
        for (List<Glyph> line : lines) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            line.sort(Comparator.comparingDouble(Glyph::x));
            Glyph previous = null;
            for (Glyph glyph : line) {
                if (previous != null && isWordGap(previous, glyph)) {
                    text.append(' ');
                }
                text.append(glyph.text());
                previous = glyph;
            }
        }
        return text.toString();
    }

    private static boolean isWordGap(Glyph previous, Glyph next) {
        if (previous.text().isBlank() || next.text().isBlank()) {
            return false;
        }
        double space = previous.spaceWidth() > 0 ? previous.spaceWidth() : previous.width();
        return next.x() - previous.endX() > space / 2;
    }
}
