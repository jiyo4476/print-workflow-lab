package dev.yjimmy.printlab.pdf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The parts of the graphics state that decide where text lands (ISO 32000-1, 8.4 and 9.3), and
 * the text matrices of 9.4.2, updated one {@link Operation} at a time.
 *
 * <p>Tracked: {@code q}/{@code Q} (a stack), {@code cm}, {@code BT}/{@code ET}, {@code Tc},
 * {@code Tw}, {@code Tz}, {@code TL}, {@code Tf}, {@code Ts}, {@code Td}, {@code TD},
 * {@code Tm}, {@code T*}, and the line moves of {@code '} and {@code "}. Showing the text
 * ({@code Tj}, {@code TJ}, and the text of {@code '} and {@code "}) is the caller's job: it
 * reads {@link #textRenderingMatrix()} for each glyph and calls {@link #advance} after it. Every
 * other operator is ignored.
 *
 * <p>The text state parameters ({@code Tc} ... {@code Ts}) belong to the graphics state, so
 * {@code q}/{@code Q} saves and restores them; the text matrix and text line matrix don't, and
 * {@code BT} resets both to the identity. A {@code Q} with nothing saved is ignored, as viewers
 * do. An operator with the wrong operands fails with its offset.
 */
public final class GraphicsState {

    /** What {@code q} saves. {@code horizontalScaling} is {@code Tz / 100}. */
    private record Saved(Matrix ctm, double charSpacing, double wordSpacing, double horizontalScaling,
            double leading, String fontName, double fontSize, double rise) {
    }

    private Matrix ctm = Matrix.IDENTITY;
    private double charSpacing;
    private double wordSpacing;
    private double horizontalScaling = 1;
    private double leading;
    private String fontName;
    private double fontSize;
    private double rise;

    private Matrix textMatrix = Matrix.IDENTITY;
    private Matrix lineMatrix = Matrix.IDENTITY;
    private final Deque<Saved> stack = new ArrayDeque<>();

    /** Updates the state for {@code op}; operators that don't change it are ignored. */
    public void apply(Operation op) {
        switch (op.operator()) {
            case "q" -> save();
            case "Q" -> restore();
            case "cm" -> ctm = matrix(op).multiply(ctm);
            case "BT" -> {
                textMatrix = Matrix.IDENTITY;
                lineMatrix = Matrix.IDENTITY;
            }
            case "Tc" -> charSpacing = number(op, 0, 1);
            case "Tw" -> wordSpacing = number(op, 0, 1);
            case "Tz" -> horizontalScaling = number(op, 0, 1) / 100;
            case "TL" -> leading = number(op, 0, 1);
            case "Ts" -> rise = number(op, 0, 1);
            case "Tf" -> {
                expect(op, 2);
                if (!(op.operands().get(0) instanceof PdfName name)) {
                    throw bad(op, "a font name and a size");
                }
                fontName = name.name();
                fontSize = number(op, 1, 2);
            }
            case "Td" -> moveLine(number(op, 0, 2), number(op, 1, 2));
            case "TD" -> {
                leading = -number(op, 1, 2);
                moveLine(number(op, 0, 2), number(op, 1, 2));
            }
            case "Tm" -> {
                textMatrix = matrix(op);
                lineMatrix = textMatrix;
            }
            case "T*" -> moveLine(0, -leading);
            case "'" -> {
                expect(op, 1);
                moveLine(0, -leading);
            }
            case "\"" -> {
                expect(op, 3);
                wordSpacing = number(op, 0, 3);
                charSpacing = number(op, 1, 3);
                moveLine(0, -leading);
            }
            default -> {
                // Not part of the text position state.
            }
        }
    }

    /** Saves the graphics state, as {@code q} does; a form XObject is drawn between this and {@link #restore}. */
    public void save() {
        stack.push(new Saved(ctm, charSpacing, wordSpacing, horizontalScaling, leading, fontName, fontSize, rise));
    }

    /** Restores the last saved state, as {@code Q} does; nothing saved, nothing happens. */
    public void restore() {
        Saved s = stack.poll();
        if (s != null) {
            ctm = s.ctm();
            charSpacing = s.charSpacing();
            wordSpacing = s.wordSpacing();
            horizontalScaling = s.horizontalScaling();
            leading = s.leading();
            fontName = s.fontName();
            fontSize = s.fontSize();
            rise = s.rise();
        }
    }

    /** Applies {@code m} before the CTM, as {@code cm} does (and as a form's {@code /Matrix} does). */
    public void concatenate(Matrix m) {
        ctm = m.multiply(ctm);
    }

    /** Moves the text position along the line by {@code tx} unscaled text space units (9.4.4). */
    public void advance(double tx) {
        textMatrix = Matrix.translate(tx, 0).multiply(textMatrix);
    }

    /**
     * Where the next glyph is drawn, from text space to the default user space:
     * {@code [Tfs×Th 0 0 Tfs 0 Trise] × Tm × CTM} (9.4.4). Its {@code (e, f)} is the glyph's origin.
     */
    public Matrix textRenderingMatrix() {
        return new Matrix(fontSize * horizontalScaling, 0, 0, fontSize, 0, rise)
                .multiply(textMatrix).multiply(ctm);
    }

    private void moveLine(double tx, double ty) {
        lineMatrix = Matrix.translate(tx, ty).multiply(lineMatrix);
        textMatrix = lineMatrix;
    }

    public Matrix ctm() {
        return ctm;
    }

    public Matrix textMatrix() {
        return textMatrix;
    }

    public Matrix lineMatrix() {
        return lineMatrix;
    }

    public double charSpacing() {
        return charSpacing;
    }

    public double wordSpacing() {
        return wordSpacing;
    }

    /** {@code Tz / 100}: 1 is normal width. */
    public double horizontalScaling() {
        return horizontalScaling;
    }

    public double leading() {
        return leading;
    }

    /** The font's name in the resources ({@code F1} for {@code /F1 10 Tf}), or null before any {@code Tf}. */
    public String fontName() {
        return fontName;
    }

    public double fontSize() {
        return fontSize;
    }

    public double rise() {
        return rise;
    }

    /** How deep {@code q} is nested. */
    public int depth() {
        return stack.size();
    }

    private static Matrix matrix(Operation op) {
        return new Matrix(number(op, 0, 6), number(op, 1, 6), number(op, 2, 6), number(op, 3, 6),
                number(op, 4, 6), number(op, 5, 6));
    }

    private static double number(Operation op, int index, int count) {
        expect(op, count);
        if (!(op.operands().get(index) instanceof PdfNumber n)) {
            throw bad(op, count == 1 ? "a number" : count + " numbers");
        }
        return n.doubleValue();
    }

    private static void expect(Operation op, int count) {
        if (op.operands().size() != count) {
            throw bad(op, count + " operand" + (count == 1 ? "" : "s"));
        }
    }

    private static PdfFormatException bad(Operation op, String expected) {
        return new PdfFormatException(op.offset(), "'" + PdfLexer.printable(op.operator()) + "' needs "
                + expected + ", not " + op.operands());
    }
}
