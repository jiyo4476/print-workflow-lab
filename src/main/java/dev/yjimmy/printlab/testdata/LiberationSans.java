package dev.yjimmy.printlab.testdata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

/**
 * Liberation Sans for the generated statements (PRD-11 FR-8): a free font (SIL Open Font License
 * 1.1, {@code fonts/LiberationSans-OFL-1.1.txt}) that is metric-compatible with Arial, and so with
 * Helvetica, so text keeps the positions it had when the generator drew Helvetica.
 *
 * <p>Each document gets a subset holding only the characters it draws, made in memory: the font
 * files on disk are the unmodified release files. The subset is embedded as a simple TrueType font
 * with {@code /WinAnsiEncoding} and {@code /Widths}, the kind of font the project's own text
 * extractor ({@code pdf/SimpleFont}) reads; a CID-keyed (Type0) font it does not.
 */
final class LiberationSans {

    /** The two faces the statement layout uses. */
    enum Face {
        REGULAR("LiberationSans-Regular.ttf"),
        BOLD("LiberationSans-Bold.ttf");

        private final String file;

        Face(String file) {
            this.file = file;
        }
    }

    private static final Map<Face, byte[]> PROGRAMS = new EnumMap<>(Face.class);

    /**
     * The tables kept in a subset: the ones ISO 32000-1 9.9 says an embedded TrueType program needs
     * (hinting included), plus {@code cmap}, {@code post}, {@code name} and {@code OS/2} for the
     * encoding, names and metrics. The layout tables ({@code GPOS}, {@code GSUB}, {@code kern},
     * about 85 KB per face) are left out: a PDF positions every glyph itself and never reads them.
     */
    private static final List<String> TABLES = List.of("head", "hhea", "loca", "maxp", "cvt ", "prep",
            "glyf", "hmtx", "fpgm", "cmap", "post", "name", "OS/2");

    private LiberationSans() {
    }

    /**
     * The face, subset to {@code characters} and embedded. Its name carries a subset tag
     * ({@code ABCDEF+LiberationSans}, ISO 32000-1 9.6.4) made from the face and the characters, so
     * the same text always gives the same bytes.
     */
    static PDTrueTypeFont embedded(PDDocument doc, Face face, Set<Integer> characters) throws IOException {
        return load(doc, face, characters, tag(face, characters) + "+");
    }

    /**
     * The face as a font a viewer has to supply: the dictionary and widths are there, but not the
     * font program. For the {@code formFont} preflight defect, which must fail the font check
     * whether or not Standard 14 fonts are allowed. No subset tag, since nothing is embedded.
     */
    static PDTrueTypeFont notEmbedded(PDDocument doc, Face face, Set<Integer> characters) throws IOException {
        PDTrueTypeFont font = load(doc, face, characters, null);
        // The program stream is then referenced by nothing, so it isn't written.
        font.getFontDescriptor().getCOSObject().removeItem(COSName.FONT_FILE2);
        return font;
    }

    private static PDTrueTypeFont load(PDDocument doc, Face face, Set<Integer> characters, String prefix)
            throws IOException {
        byte[] subset;
        try (TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(program(face)))) {
            TTFSubsetter subsetter = new TTFSubsetter(ttf, TABLES);
            if (prefix != null) {
                subsetter.setPrefix(prefix);
            }
            subsetter.add(' ');
            subsetter.addAll(characters);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            subsetter.writeToStream(out);
            subset = out.toByteArray();
        }
        return PDTrueTypeFont.load(doc, new ByteArrayInputStream(subset), WinAnsiEncoding.INSTANCE);
    }

    /** Six capital letters from the face and its characters (a tag only has to tell subsets apart). */
    static String tag(Face face, Set<Integer> characters) {
        long hash = 17;
        hash = hash * 31 + face.ordinal();
        for (int c : new TreeSet<>(characters)) {
            hash = hash * 31 + c;
        }
        StringBuilder tag = new StringBuilder();
        long rest = Math.floorMod(hash, 308_915_776L); // 26^6
        for (int i = 0; i < 6; i++) {
            tag.append((char) ('A' + rest % 26));
            rest /= 26;
        }
        return tag.toString();
    }

    /** The unmodified font file, read from the classpath once. */
    private static synchronized byte[] program(Face face) {
        return PROGRAMS.computeIfAbsent(face, f -> {
            String path = "/fonts/" + f.file;
            try (InputStream in = LiberationSans.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Font " + path + " is missing from the classpath");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Can't read font " + path, e);
            }
        });
    }
}
