package dev.yjimmy.printlab.pdf;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out where each character on a page is drawn: runs the page's {@link Operation}s through
 * a {@link GraphicsState} and, for every byte that {@code Tj}, {@code TJ}, {@code '} or
 * {@code "} shows, makes a {@link Glyph} in top-left page coordinates.
 *
 * <p>For each glyph (ISO 32000-1, 9.4.4): its origin is where the text rendering matrix
 * {@code [Tfs×Th 0 0 Tfs 0 Trise] × Tm × CTM} puts {@code (0, 0)}; then the text matrix moves
 * along the line by {@code (w0 / 1000 × Tfs + Tc + Tw) × Th}, where {@code w0} is the glyph's
 * width in the font and {@code Tw} applies only to the byte 32. A number in a {@code TJ} array
 * moves the next glyph back by that many thousandths of a text space unit.
 *
 * <p>Page coordinates: {@code x} is measured from the left of the crop box (else the media box),
 * {@code y} down from its top, as workflow regions are. A page with {@code /Rotate} other than 0
 * is not supported. Text in form XObjects ({@code Do} on a {@code /Subtype /Form}) is followed,
 * with the form's {@code /Matrix} and {@code /Resources}, to {@value #MAX_FORM_DEPTH} levels;
 * the page and every form it draws (each time it is drawn) may come to no more than
 * {@link StreamDecoder#DEFAULT_MAX_DECODED_BYTES} of content, so forms that draw each other
 * many times over fail instead of running out of memory, as does a page of more than
 * {@value #MAX_GLYPHS} glyphs. Text must run left to right along a
 * horizontal baseline: text that {@code cm}, {@code Tm} or a form's {@code /Matrix} turns
 * fails rather than come out one glyph per line. Fonts are {@link SimpleFont}s; any other font
 * throws {@link UnsupportedContentException}.
 */
public final class TextPositionExtractor {

    /** How deep form XObjects may draw each other; deeper (or a form that draws itself) fails. */
    public static final int MAX_FORM_DEPTH = 20;

    /** The most glyphs one page may show; a real page has a few thousand. */
    public static final int MAX_GLYPHS = 1_000_000;

    private final PdfDocumentReader pdf;
    private final long maxContentBytes;
    private final int maxGlyphs;
    private final Map<PdfDictionary, SimpleFont> fonts = new IdentityHashMap<>();

    public TextPositionExtractor(PdfDocumentReader pdf) {
        this(pdf, StreamDecoder.DEFAULT_MAX_DECODED_BYTES, MAX_GLYPHS);
    }

    /** With other limits on the content a page draws and the glyphs it shows (for tests). */
    TextPositionExtractor(PdfDocumentReader pdf, long maxContentBytes, int maxGlyphs) {
        this.pdf = pdf;
        this.maxContentBytes = maxContentBytes;
        this.maxGlyphs = maxGlyphs;
    }

    /** Every character {@code page} draws, in the order the content draws them. */
    public List<Glyph> glyphs(PdfPage page) {
        PdfObject rotate = pdf.resolve(page.dictionary().get("Rotate"));
        if (rotate instanceof PdfNumber degrees && Math.floorMod(degrees.intValue(), 360) != 0) {
            throw new UnsupportedContentException("page " + page.number() + " is rotated (/Rotate "
                    + degrees + "); only unrotated pages are read");
        }
        double[] box = box(page);
        Run run = new Run(page.number(), box[0], box[3]);
        byte[] content = pdf.content(page);
        run.drawn = content.length;
        run.draw(content, page.resources(), 0);
        return run.glyphs;
    }

    /** The crop box, else the media box, as {@code [left bottom right top]}; US Letter if neither. */
    private double[] box(PdfPage page) {
        PdfObject value = pdf.resolve(page.dictionary().get("CropBox"));
        if (!(value instanceof PdfArray)) {
            value = page.mediaBox().map(PdfObject.class::cast).orElse(null);
        }
        if (value instanceof PdfArray array && array.size() == 4) {
            double[] n = new double[4];
            for (int i = 0; i < 4; i++) {
                if (!(pdf.resolve(array.get(i)) instanceof PdfNumber number)) {
                    throw new UnsupportedContentException("page " + page.number() + " has a box that isn't 4 numbers: " + array);
                }
                n[i] = number.doubleValue();
            }
            return new double[] {Math.min(n[0], n[2]), Math.min(n[1], n[3]), Math.max(n[0], n[2]), Math.max(n[1], n[3])};
        }
        return new double[] {0, 0, 612, 792};
    }

    /** One page's walk: the state, and the glyphs found so far. */
    private final class Run {
        private final int pageNumber;
        private final double left;
        private final double top;
        private final GraphicsState state = new GraphicsState();
        private final List<Glyph> glyphs = new ArrayList<>();
        private final Map<PdfStream, byte[]> forms = new IdentityHashMap<>();
        /** Content bytes drawn so far: the page's, plus each form's every time it is drawn. */
        private long drawn;

        Run(int pageNumber, double left, double top) {
            this.pageNumber = pageNumber;
            this.left = left;
            this.top = top;
        }

        void draw(byte[] content, PdfDictionary resources, int depth) {
            ContentStreamParser parser = new ContentStreamParser(content);
            for (Operation op = parser.next(); op != null; op = parser.next()) {
                state.apply(op);
                switch (op.operator()) {
                    case "Tj", "'" -> show(op, resources, string(op, 0));
                    case "\"" -> show(op, resources, string(op, 2));
                    case "TJ" -> showArray(op, resources);
                    case "Do" -> form(op, resources, depth);
                    default -> {
                        // State only, or nothing to do with text.
                    }
                }
            }
        }

        private void showArray(Operation op, PdfDictionary resources) {
            if (op.operands().size() != 1 || !(op.operands().get(0) instanceof PdfArray array)) {
                throw new PdfFormatException(op.offset(), "'TJ' needs one array, not " + op.operands());
            }
            for (PdfObject item : array.items()) {
                if (item instanceof PdfString text) {
                    show(op, resources, text);
                } else if (item instanceof PdfNumber adjustment) {
                    state.advance(-adjustment.doubleValue() / 1000 * state.fontSize() * state.horizontalScaling());
                } else {
                    throw new PdfFormatException(op.offset(), "a 'TJ' array holds strings and numbers, not " + item);
                }
            }
        }

        private void show(Operation op, PdfDictionary resources, PdfString text) {
            SimpleFont font = font(op, resources);
            double size = state.fontSize();
            double scaling = state.horizontalScaling();
            for (byte b : text.bytes()) {
                int code = b & 0xFF;
                Matrix trm = state.textRenderingMatrix();
                boolean zeroSize = trm.a() == 0 && trm.b() == 0;
                if (!zeroSize && (!(trm.a() > 0) || Math.abs(trm.b()) > trm.a() * 1e-3)) {
                    throw new UnsupportedContentException("page " + pageNumber + " draws text that isn't"
                            + " horizontal and left to right (it is turned by 'cm', 'Tm' or a form's /Matrix);"
                            + " only such text is read");
                }
                if (glyphs.size() == maxGlyphs) {
                    throw new UnsupportedContentException("page " + pageNumber + " shows more than "
                            + maxGlyphs + " glyphs");
                }
                double width = font.width(code) / 1000;
                glyphs.add(new Glyph(font.text(code), trm.e() - left, top - trm.f(),
                        width * trm.xScale(), trm.yScale(), font.width(' ') / 1000 * trm.xScale()));
                double spacing = state.charSpacing() + (code == ' ' ? state.wordSpacing() : 0);
                state.advance((width * size + spacing) * scaling);
            }
        }

        private SimpleFont font(Operation op, PdfDictionary resources) {
            String name = state.fontName();
            if (name == null) {
                throw new PdfFormatException(op.offset(), "text is shown before any 'Tf' sets a font");
            }
            PdfObject entry = pdf.resolve(resource(resources, "Font", name));
            if (!(entry instanceof PdfDictionary dictionary)) {
                throw new PdfFormatException(op.offset(), "font /" + name + " isn't in the /Font resources");
            }
            return fonts.computeIfAbsent(dictionary, d -> SimpleFont.from(pdf, d));
        }

        private void form(Operation op, PdfDictionary resources, int depth) {
            if (op.operands().size() != 1 || !(op.operands().get(0) instanceof PdfName name)) {
                throw new PdfFormatException(op.offset(), "'Do' needs one name, not " + op.operands());
            }
            if (!(pdf.resolve(resource(resources, "XObject", name.name())) instanceof PdfStream stream)
                    || !(pdf.resolve(stream.dictionary().get("Subtype")) instanceof PdfName subtype)
                    || !subtype.name().equals("Form")) {
                return; // An image, or a name that isn't there: no text either way.
            }
            if (depth >= MAX_FORM_DEPTH) {
                throw new UnsupportedContentException("form XObjects are nested more than " + MAX_FORM_DEPTH
                        + " deep (does /" + name.name() + " draw itself?)");
            }
            PdfDictionary formResources = pdf.resolve(stream.dictionary().get("Resources")) instanceof PdfDictionary own
                    ? own : resources;
            int level = state.depth();
            state.save();
            try {
                if (pdf.resolve(stream.dictionary().get("Matrix")) instanceof PdfArray m && m.size() == 6) {
                    double[] n = new double[6];
                    for (int i = 0; i < 6; i++) {
                        n[i] = pdf.resolve(m.get(i)) instanceof PdfNumber number ? number.doubleValue() : 0;
                    }
                    state.concatenate(new Matrix(n[0], n[1], n[2], n[3], n[4], n[5]));
                }
                byte[] content = forms.computeIfAbsent(stream, pdf::decode);
                drawn += content.length;
                if (drawn > maxContentBytes) {
                    throw new UnsupportedContentException("page " + pageNumber + " draws more than "
                            + maxContentBytes + " bytes of content with its form XObjects"
                            + " (/" + name.name() + " is drawn again and again?)");
                }
                draw(content, formResources, depth + 1);
            } finally {
                while (state.depth() > level) {
                    state.restore(); // Also undoes any q the form left open.
                }
            }
        }

        private PdfObject resource(PdfDictionary resources, String category, String name) {
            return pdf.resolve(resources.get(category)) instanceof PdfDictionary entries ? entries.get(name) : null;
        }

        private PdfString string(Operation op, int index) {
            if (op.operands().size() <= index || !(op.operands().get(index) instanceof PdfString s)) {
                throw new PdfFormatException(op.offset(), "'" + PdfLexer.printable(op.operator())
                        + "' needs a string, not " + op.operands());
            }
            return s;
        }
    }
}
