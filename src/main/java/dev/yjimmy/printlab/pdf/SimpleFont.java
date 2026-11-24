package dev.yjimmy.printlab.pdf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple font (ISO 32000-1, 9.6): one byte per character, a width for each code, and an
 * encoding that names each code's glyph. Type 1, MMType1 and TrueType fonts are simple.
 *
 * <p>Widths, in thousandths of a text space unit: the font's {@code /FirstChar} and
 * {@code /Widths} when it has them ({@code /MissingWidth} from the descriptor, else 0, for
 * other codes). A Standard 14 font may leave them out (9.6.2.2); its widths then come from
 * Adobe's AFM metrics, read from the copies inside the PDFBox jar.
 *
 * <p>Encoding: {@code /WinAnsiEncoding}, {@code /StandardEncoding}, or a dictionary with an
 * optional {@code /BaseEncoding} and {@code /Differences}; none means {@code StandardEncoding}.
 *
 * <p>Everything else throws {@link UnsupportedContentException} ("unsupported font") rather
 * than give wrong text: Type 0 (CID) fonts, Type 3 fonts, other encodings ({@code
 * MacRomanEncoding}), symbolic fonts with no encoding (Symbol, ZapfDingbats), and a non-standard
 * font with no {@code /Widths}. {@code /ToUnicode} maps aren't read.
 */
public final class SimpleFont {

    /** The Standard 14 fonts (9.6.2.2), whose AFM files PDFBox ships. */
    public static final Set<String> STANDARD_14 = Set.of(
            "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
            "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
            "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
            "Symbol", "ZapfDingbats");

    private static final String AFM_DIR = "org/apache/pdfbox/resources/afm/";
    private static final Map<String, Map<String, Integer>> AFM = new ConcurrentHashMap<>();

    /**
     * WinAnsiEncoding names the AFM files don't list, measured as the glyph they look like (as
     * PDFBox and Acrobat do): a no-break space is a space, a soft hyphen a hyphen.
     */
    private static final Map<String, String> AFM_ALIASES = Map.of("nbspace", "space", "sfthyphen", "hyphen");

    private final String baseFont;
    private final SimpleEncoding encoding;
    private final double[] widths = new double[256];

    private SimpleFont(String baseFont, SimpleEncoding encoding) {
        this.baseFont = baseFont;
        this.encoding = encoding;
    }

    /** Reads the font dictionary {@code font}; {@code pdf} resolves its references. */
    public static SimpleFont from(PdfDocumentReader pdf, PdfDictionary font) {
        String subtype = name(pdf.resolve(font.get("Subtype")));
        String baseFont = name(pdf.resolve(font.get("BaseFont")));
        String label = baseFont == null ? "a font with no /BaseFont" : baseFont;
        if (!"Type1".equals(subtype) && !"MMType1".equals(subtype) && !"TrueType".equals(subtype)) {
            throw unsupported(label, "/" + subtype + (("Type0".equals(subtype)) ? " (CID-keyed)" : "")
                    + " fonts aren't read; only simple Type1 and TrueType fonts are");
        }
        String standard = baseFont == null ? null : withoutSubsetPrefix(baseFont);
        boolean isStandard = standard != null && STANDARD_14.contains(standard);

        SimpleFont result = new SimpleFont(label, encoding(pdf, label, font.get("Encoding"), standard));
        PdfObject widths = pdf.resolve(font.get("Widths"));
        if (widths instanceof PdfArray array) {
            double missing = 0;
            if (pdf.resolve(font.get("FontDescriptor")) instanceof PdfDictionary descriptor
                    && pdf.resolve(descriptor.get("MissingWidth")) instanceof PdfNumber n) {
                missing = n.doubleValue();
            }
            java.util.Arrays.fill(result.widths, missing);
            int first = pdf.resolve(font.get("FirstChar")) instanceof PdfNumber n ? n.intValue() : 0;
            for (int i = 0; i < array.size(); i++) {
                int code = first + i;
                if (code >= 0 && code < 256 && pdf.resolve(array.get(i)) instanceof PdfNumber w) {
                    result.widths[code] = w.doubleValue();
                }
            }
        } else if (isStandard) {
            Map<String, Integer> metrics = afm(standard);
            for (int code = 0; code < 256; code++) {
                String glyph = result.encoding.glyphName(code);
                result.widths[code] = glyph == null ? 0 : metrics.getOrDefault(glyph,
                        metrics.getOrDefault(AFM_ALIASES.getOrDefault(glyph, glyph), 0));
            }
        } else {
            throw unsupported(label, "it has no /Widths and isn't one of the Standard 14 fonts");
        }
        return result;
    }

    private static SimpleEncoding encoding(PdfDocumentReader pdf, String label, PdfObject value, String standard) {
        PdfObject resolved = pdf.resolve(value);
        if (resolved == null || resolved instanceof PdfNull) {
            if ("Symbol".equals(standard) || "ZapfDingbats".equals(standard)) {
                throw unsupported(label, "a symbolic font's built-in encoding isn't read");
            }
            return SimpleEncoding.STANDARD;
        }
        if (resolved instanceof PdfName name) {
            return base(label, name.name());
        }
        if (resolved instanceof PdfDictionary dictionary) {
            String baseName = name(pdf.resolve(dictionary.get("BaseEncoding")));
            SimpleEncoding base = baseName == null ? SimpleEncoding.STANDARD : base(label, baseName);
            return pdf.resolve(dictionary.get("Differences")) instanceof PdfArray differences
                    ? base.withDifferences(differences) : base;
        }
        throw unsupported(label, "its /Encoding is " + resolved);
    }

    private static SimpleEncoding base(String label, String name) {
        SimpleEncoding encoding = SimpleEncoding.named(name);
        if (encoding == null) {
            throw unsupported(label, "/" + name + " isn't read; only /WinAnsiEncoding and /StandardEncoding are");
        }
        return encoding;
    }

    /** The glyph widths in a Standard 14 font's AFM file, by glyph name. */
    static Map<String, Integer> afm(String fontName) {
        return AFM.computeIfAbsent(fontName, SimpleFont::readAfm);
    }

    /** Reads the {@code C code ; WX width ; N name ; ...} lines of an AFM file (Adobe TN 5004). */
    private static Map<String, Integer> readAfm(String fontName) {
        Map<String, Integer> widths = new HashMap<>();
        InputStream in = SimpleFont.class.getClassLoader().getResourceAsStream(AFM_DIR + fontName + ".afm");
        if (in == null) {
            throw new IllegalStateException("no AFM metrics for " + fontName + " on the class path ("
                    + AFM_DIR + "): is PDFBox missing?");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("C ")) {
                    continue;
                }
                Integer width = null;
                String glyph = null;
                for (String part : line.split(";")) {
                    String[] words = part.trim().split("\\s+");
                    if (words.length >= 2 && (words[0].equals("WX") || words[0].equals("W0X"))) {
                        width = (int) Math.round(Double.parseDouble(words[1]));
                    } else if (words.length >= 2 && words[0].equals("N")) {
                        glyph = words[1];
                    }
                }
                if (width != null && glyph != null) {
                    widths.put(glyph, width);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(widths);
    }

    /** {@code ABCDEF+Helvetica} → {@code Helvetica} (a subset's tag, 9.6.4). */
    private static String withoutSubsetPrefix(String baseFont) {
        int plus = baseFont.indexOf('+');
        return plus == 6 && baseFont.substring(0, 6).chars().allMatch(c -> c >= 'A' && c <= 'Z')
                ? baseFont.substring(7) : baseFont;
    }

    private static String name(PdfObject object) {
        return object instanceof PdfName n ? n.name() : null;
    }

    private static UnsupportedContentException unsupported(String font, String why) {
        return new UnsupportedContentException("unsupported font " + font + ": " + why);
    }

    /** The font's {@code /BaseFont}, as written. */
    public String baseFont() {
        return baseFont;
    }

    public SimpleEncoding encoding() {
        return encoding;
    }

    /** The width of {@code code}'s glyph, in thousandths of a text space unit. */
    public double width(int code) {
        return widths[code];
    }

    /** The Unicode text of {@code code}'s glyph, empty if the encoding has none. */
    public String text(int code) {
        return encoding.text(code);
    }

    @Override
    public String toString() {
        return baseFont + " (" + encoding + ")";
    }
}
