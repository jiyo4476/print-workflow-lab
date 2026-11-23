package dev.yjimmy.printlab.pdf;

import java.util.Map;
import java.util.Optional;

/**
 * The content-stream operators of ISO 32000-1, Annex A (Table A.1), each with a short
 * description for {@code dump-content}. 73 operators in all: path construction and painting,
 * graphics state, color, text, XObjects and inline images, marked content, and Type 3 glyphs.
 */
public final class ContentOperators {

    private static final Map<String, String> DESCRIPTIONS = Map.<String, String>ofEntries(
            // General graphics state (8.4.4)
            Map.entry("w", "line width"),
            Map.entry("J", "line cap"),
            Map.entry("j", "line join"),
            Map.entry("M", "miter limit"),
            Map.entry("d", "dash pattern"),
            Map.entry("ri", "rendering intent"),
            Map.entry("i", "flatness"),
            Map.entry("gs", "graphics state from /ExtGState"),
            // Special graphics state (8.4.4)
            Map.entry("q", "save graphics state"),
            Map.entry("Q", "restore graphics state"),
            Map.entry("cm", "concatenate matrix to the CTM"),
            // Path construction (8.5.2)
            Map.entry("m", "move to"),
            Map.entry("l", "line to"),
            Map.entry("c", "curve to"),
            Map.entry("v", "curve to, first control point at the current point"),
            Map.entry("y", "curve to, second control point at the end"),
            Map.entry("h", "close subpath"),
            Map.entry("re", "rectangle"),
            // Path painting (8.5.3)
            Map.entry("S", "stroke"),
            Map.entry("s", "close and stroke"),
            Map.entry("f", "fill, nonzero winding"),
            Map.entry("F", "fill, nonzero winding (obsolete form of f)"),
            Map.entry("f*", "fill, even-odd"),
            Map.entry("B", "fill and stroke, nonzero winding"),
            Map.entry("B*", "fill and stroke, even-odd"),
            Map.entry("b", "close, fill and stroke, nonzero winding"),
            Map.entry("b*", "close, fill and stroke, even-odd"),
            Map.entry("n", "end path without painting"),
            // Clipping (8.5.4)
            Map.entry("W", "clip, nonzero winding"),
            Map.entry("W*", "clip, even-odd"),
            // Text objects and state (9.3, 9.4)
            Map.entry("BT", "begin text"),
            Map.entry("ET", "end text"),
            Map.entry("Tc", "character spacing"),
            Map.entry("Tw", "word spacing"),
            Map.entry("Tz", "horizontal scaling, percent"),
            Map.entry("TL", "leading"),
            Map.entry("Tf", "font and size"),
            Map.entry("Tr", "text rendering mode"),
            Map.entry("Ts", "text rise"),
            // Text positioning (9.4.2)
            Map.entry("Td", "next line, offset by tx ty"),
            Map.entry("TD", "next line, offset by tx ty, leading -ty"),
            Map.entry("Tm", "text matrix"),
            Map.entry("T*", "next line, by the leading"),
            // Text showing (9.4.3)
            Map.entry("Tj", "show text"),
            Map.entry("TJ", "show text, numbers move back by thousandths of the font size"),
            Map.entry("'", "next line, show text"),
            Map.entry("\"", "word and character spacing, next line, show text"),
            // Type 3 fonts (9.6.5)
            Map.entry("d0", "Type 3 glyph width"),
            Map.entry("d1", "Type 3 glyph width and bounding box"),
            // Color (8.6.8)
            Map.entry("CS", "stroking color space"),
            Map.entry("cs", "nonstroking color space"),
            Map.entry("SC", "stroking color"),
            Map.entry("SCN", "stroking color, pattern or separation"),
            Map.entry("sc", "nonstroking color"),
            Map.entry("scn", "nonstroking color, pattern or separation"),
            Map.entry("G", "stroking gray"),
            Map.entry("g", "nonstroking gray"),
            Map.entry("RG", "stroking RGB"),
            Map.entry("rg", "nonstroking RGB"),
            Map.entry("K", "stroking CMYK"),
            Map.entry("k", "nonstroking CMYK"),
            // Shading, XObjects, inline images (8.7.4.2, 8.8, 8.9.7)
            Map.entry("sh", "paint shading"),
            Map.entry("Do", "paint XObject"),
            Map.entry("BI", "inline image"),
            Map.entry("ID", "inline image data"),
            Map.entry("EI", "end inline image"),
            // Marked content (14.6)
            Map.entry("MP", "marked-content point"),
            Map.entry("DP", "marked-content point with properties"),
            Map.entry("BMC", "begin marked content"),
            Map.entry("BDC", "begin marked content with properties"),
            Map.entry("EMC", "end marked content"),
            // Compatibility (7.8.2)
            Map.entry("BX", "begin compatibility section: unknown operators are ignored"),
            Map.entry("EX", "end compatibility section"));

    private ContentOperators() {
    }

    /** All the operators Annex A lists. */
    public static int count() {
        return DESCRIPTIONS.size();
    }

    public static boolean isKnown(String operator) {
        return DESCRIPTIONS.containsKey(operator);
    }

    /** A short description of {@code operator}, if it's one Annex A lists. */
    public static Optional<String> describe(String operator) {
        return Optional.ofNullable(DESCRIPTIONS.get(operator));
    }
}
