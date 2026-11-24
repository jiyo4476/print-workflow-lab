package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class GraphicsStateTest {

    private static GraphicsState run(String content) {
        GraphicsState state = new GraphicsState();
        ContentStreamParser.parse(content.getBytes(StandardCharsets.US_ASCII)).forEach(state::apply);
        return state;
    }

    private static void assertPoint(double[] point, double x, double y) {
        assertThat(point[0]).as("x").isCloseTo(x, within(1e-9));
        assertThat(point[1]).as("y").isCloseTo(y, within(1e-9));
    }

    @Test
    public void qAndQRestoreState() {
        GraphicsState state = run("q 2 0 0 2 10 10 cm 3 Tc 4 Tw 50 Tz 12 TL /F2 9 Tf 5 Ts Q");

        assertEquals("ctm", Matrix.IDENTITY, state.ctm());
        assertEquals("Tc", 0, state.charSpacing(), 0);
        assertEquals("Tw", 0, state.wordSpacing(), 0);
        assertEquals("Tz", 1, state.horizontalScaling(), 0);
        assertEquals("TL", 0, state.leading(), 0);
        assertEquals("font", null, state.fontName());
        assertEquals("Ts", 0, state.rise(), 0);
        assertEquals("depth", 0, state.depth());

        GraphicsState inside = run("/F1 10 Tf q /F2 9 Tf");
        assertEquals("depth inside q", 1, inside.depth());
        assertEquals("font inside q", "F2", inside.fontName());
        inside.restore();
        assertEquals("font after Q", "F1", inside.fontName());
        assertEquals("size after Q", 10, inside.fontSize(), 0);
    }

    /** The text matrices aren't part of the graphics state: q/Q leaves them alone. */
    @Test
    public void textMatricesSurviveQ() {
        GraphicsState state = run("BT q 1 0 0 1 50 60 Tm Q");

        assertEquals(new Matrix(1, 0, 0, 1, 50, 60), state.textMatrix());
    }

    /** cm applies the new matrix first, in the coordinates the old CTM maps (8.3.4). */
    @Test
    public void cmConcatenatesInCorrectOrder() {
        GraphicsState state = run("1 0 0 1 100 0 cm 2 0 0 2 0 0 cm");

        // Scale first, then translate: (1, 1) -> (2, 2) -> (102, 2). The other order gives (202, 2).
        assertPoint(state.ctm().transform(1, 1), 102, 2);
        assertEquals(new Matrix(2, 0, 0, 2, 100, 0), state.ctm());
    }

    @Test
    public void extraQIsIgnored() {
        GraphicsState state = run("2 0 0 2 0 0 cm Q Q");

        assertEquals("a Q with nothing saved changes nothing", Matrix.scale(2, 2), state.ctm());
    }

    @Test
    public void tdMovesLineMatrix() {
        GraphicsState state = run("BT 100 700 Td 10 -20 Td");

        assertEquals("line matrix", Matrix.translate(110, 680), state.lineMatrix());
        assertEquals("text matrix follows", state.lineMatrix(), state.textMatrix());

        state.advance(30);
        assertEquals("advance moves only the text matrix", Matrix.translate(140, 680), state.textMatrix());
        state.apply(Operation.of("Td", PdfNumber.of(0), PdfNumber.of(-12)));
        assertEquals("Td starts from the line, not from where the text got to",
                Matrix.translate(110, 668), state.textMatrix());
    }

    @Test
    public void tStarUsesLeading() {
        GraphicsState state = run("BT 14 TL 72 700 Td T*");
        assertEquals(Matrix.translate(72, 686), state.textMatrix());

        GraphicsState td = run("BT 72 700 Td 0 -16 TD T*");
        assertEquals("TD sets the leading to -ty", 16, td.leading(), 0);
        assertEquals(Matrix.translate(72, 668), td.textMatrix());
    }

    @Test
    public void quoteOperatorsMoveToTheNextLine() {
        GraphicsState single = run("BT 10 TL 0 100 Td (a) '");
        assertEquals("' is T* then Tj", Matrix.translate(0, 90), single.textMatrix());

        GraphicsState dbl = run("BT 10 TL 0 100 Td 3 1.5 (a) \"");
        assertEquals("\" is T* then Tj", Matrix.translate(0, 90), dbl.textMatrix());
        assertEquals("\" sets Tw", 3, dbl.wordSpacing(), 0);
        assertEquals("\" sets Tc", 1.5, dbl.charSpacing(), 0);
    }

    @Test
    public void tmReplacesTheMatricesAndBtResetsThem() {
        GraphicsState state = run("BT 1 0 0 1 50 60 Tm 5 5 Td");
        assertEquals(Matrix.translate(55, 65), state.textMatrix());

        state.apply(Operation.of("BT"));
        assertEquals("text matrix", Matrix.IDENTITY, state.textMatrix());
        assertEquals("line matrix", Matrix.IDENTITY, state.lineMatrix());
    }

    /** Trm = [Tfs×Th 0 0 Tfs 0 Trise] × Tm × CTM (9.4.4). */
    @Test
    public void textRenderingMatrixScalesAndRises() {
        GraphicsState state = run("2 0 0 2 0 0 cm BT /F1 10 Tf 50 Tz 3 Ts 100 200 Td");
        Matrix trm = state.textRenderingMatrix();

        assertEquals(new Matrix(10, 0, 0, 20, 200, 406), trm);
        assertEquals("x scale: 10 × 0.5 × 2", 10, trm.xScale(), 1e-9);
        assertEquals("y scale: 10 × 2", 20, trm.yScale(), 1e-9);
    }

    @Test
    public void wrongOperandsFailWithTheirOffset() {
        assertThat(assertThrows(PdfFormatException.class, () -> run("q 1 2 Td 3 Td")).getMessage())
                .contains("(9)").contains("'Td' needs 2 operands");
        assertThat(assertThrows(PdfFormatException.class, () -> run("(F1) 10 Tf")).getMessage())
                .contains("'Tf' needs a font name and a size");
        assertThat(assertThrows(PdfFormatException.class, () -> run("/a Tc")).getMessage())
                .contains("'Tc' needs a number");
        assertThat(assertThrows(PdfFormatException.class, () -> run("1 0 0 1 0 /x cm")).getMessage())
                .contains("'cm' needs 6 numbers");
    }

    @Test
    public void otherOperatorsLeaveTheStateAlone() {
        GraphicsState state = run("0 0 m 10 10 l S 1 0 0 rg /Im1 Do");

        assertEquals(Matrix.IDENTITY, state.ctm());
        assertEquals(0, state.depth());
    }
}
