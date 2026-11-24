package dev.yjimmy.printlab.pdf;

/**
 * One character drawn on a page, in the coordinates workflow regions use: points from the
 * <b>top-left</b> of the page's crop box.
 *
 * @param text the character's Unicode text (usually one character; empty if the font's
 *     encoding has no glyph for the code)
 * @param x where the glyph starts, on its baseline
 * @param y the baseline, measured down from the top of the page
 * @param width how far the glyph's own width reaches along the baseline (character and word
 *     spacing not included)
 * @param fontSize the font's height on the page, in points
 * @param spaceWidth how wide a space is in this font at this size, used to tell a gap between
 *     words from the gap between letters; 0 if the font has no space glyph
 */
public record Glyph(String text, double x, double y, double width, double fontSize, double spaceWidth) {

    /** Where the glyph ends along the baseline. */
    public double endX() {
        return x + width;
    }

    @Override
    public String toString() {
        return String.format("'%s' at (%.2f, %.2f) w %.2f size %.2f", text, x, y, width, fontSize);
    }
}
