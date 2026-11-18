package dev.yjimmy.printlab.testdata;

import dev.yjimmy.printlab.afp.AfpWriter;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.TleEncoder;
import dev.yjimmy.printlab.testdata.StatementLayout.TextLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Generates the same fake statement print run as {@link StatementPdfGenerator}, as AFP: for the
 * same seed, the same statements with the same pages and text.
 *
 * <p>Unlike the PDF, the AFP carries each statement's index values as data, so nothing has to be
 * read off the page. Each statement is a named page group with three TLEs:
 * <pre>
 *   BDT PRINTRUN
 *     BNG S0000001                  one group per statement
 *       TLE accountNumber = ...
 *       TLE name = ...              empty for a "bad address" statement
 *       TLE zip = ...
 *       BPG P0000001                one page; repeated per page
 *         BAG                       the page's environment:
 *           MCF                       font local ID 1 = code page T1V10037, character set C0D0GT10
 *           PGD                       US Letter, 1440 units per inch
 *           PTD                       text area: the whole page, same units
 *         EAG
 *         BPT
 *           PTX                     the page's text lines (PTOCA)
 *         EPT
 *       EPG
 *     ENG
 *   EDT
 * </pre>
 * Text sits where {@link StatementLayout} puts it in the PDF, converted from points to units (20
 * units per point), in one font: the PDF's sizes and bold aren't reproduced. There
 * are no inline fonts or resources: the font is referenced by name, so a viewer or printer uses
 * its own copy (or a substitute). The output has no timestamps, so the same seed gives
 * byte-identical files.
 */
public final class StatementAfpGenerator {

    /** Units per 10 inches in the page and text descriptors: 1440 units per inch. */
    static final int UNITS_PER_10_INCHES = 14_400;
    /** Units per point, at 1440 units per inch. */
    static final int UNITS_PER_POINT = 20;
    /** US Letter, 8.5 x 11 inches, in units. */
    static final int PAGE_WIDTH = 12_240;
    static final int PAGE_HEIGHT = 15_840;

    /** The code page and character set of the one font every page uses (local ID 1). */
    static final String CODE_PAGE = "T1V10037";
    static final String CHARACTER_SET = "C0D0GT10";
    private static final int FONT_LOCAL_ID = 1;

    // MO:DCA triplets for the Map Coded Font.
    private static final int TRIPLET_FQN = 0x02;
    private static final int FQN_CODE_PAGE_NAME = 0x85;
    private static final int FQN_CHARACTER_SET_NAME = 0x86;
    private static final int TRIPLET_RESOURCE_LOCAL_ID = 0x24;
    private static final int RESOURCE_TYPE_CODED_FONT = 0x05;

    // PTOCA control sequences. An odd function type means another control sequence follows in
    // the same chain; the last one in a chain has the even (unchained) type.
    private static final int PTOCA_ESCAPE_1 = 0x2B;
    private static final int PTOCA_ESCAPE_2 = 0xD3;
    private static final int SCFL_CHAINED = 0xF1;
    private static final int AMB_CHAINED = 0xD3;
    private static final int AMI_CHAINED = 0xC7;
    private static final int TRN_UNCHAINED = 0xDA;
    /** A control sequence's length byte counts itself and the type, so TRN holds 253 bytes. */
    private static final int MAX_TRN_TEXT = 253;

    private final StatementDataGenerator data;

    public StatementAfpGenerator(long seed) {
        this.data = new StatementDataGenerator(seed);
    }

    public void generate(int statements, Path out) throws IOException {
        generate(statements, 0, out);
    }

    /**
     * @param badAddresses how many statements get a blank name or a 4-digit zip (alternately, in
     *     statement order), at most {@code statements}
     */
    public void generate(int statements, int badAddresses, Path out) throws IOException {
        List<Statement> run = data.generate(statements, badAddresses);
        try (OutputStream file = Files.newOutputStream(out)) {
            write(run, file);
        }
    }

    /** Writes {@code statements} as one AFP document to {@code out}, without closing it. */
    public static void write(List<Statement> statements, OutputStream out) throws IOException {
        AfpWriter afp = new AfpWriter(out);
        afp.begin(FieldType.BDT, "PRINTRUN");
        int pageNumber = 0;
        for (int s = 0; s < statements.size(); s++) {
            Statement statement = statements.get(s);
            afp.begin(FieldType.BNG, token('S', s + 1));
            afp.write(FieldType.TLE, TleEncoder.encode("accountNumber", statement.accountNumber()));
            afp.write(FieldType.TLE, TleEncoder.encode("name", statement.name()));
            afp.write(FieldType.TLE, TleEncoder.encode("zip", statement.zip()));
            for (int p = 1; p <= statement.pageCount(); p++) {
                afp.begin(FieldType.BPG, token('P', ++pageNumber));
                writeEnvironment(afp);
                afp.write(FieldType.BPT, new byte[0]);
                afp.write(FieldType.PTX, pageText(statement, p));
                afp.end(FieldType.EPT);
                afp.end(FieldType.EPG);
            }
            afp.end(FieldType.ENG);
        }
        afp.end(FieldType.EDT);
        afp.flush();
    }

    /** An 8-character name token: a letter, then a 7-digit number. */
    private static String token(char prefix, int number) {
        if (number > 9_999_999) {
            throw new IllegalArgumentException("more than 9,999,999 " + prefix + " tokens");
        }
        return String.format(Locale.ROOT, "%c%07d", prefix, number);
    }

    private static void writeEnvironment(AfpWriter afp) throws IOException {
        afp.write(FieldType.BAG, new byte[0]);
        afp.write(FieldType.MCF, mapCodedFont());
        afp.write(FieldType.PGD, pageDescriptor());
        afp.write(FieldType.PTD, textDescriptor());
        afp.end(FieldType.EAG);
    }

    /**
     * One MCF repeating group: a 2-byte length counting itself, then the code page name, the
     * character set name, and the local ID that PTOCA's SCFL refers to.
     */
    private static byte[] mapCodedFont() {
        ByteArrayOutputStream group = new ByteArrayOutputStream();
        group.writeBytes(nameTriplet(FQN_CODE_PAGE_NAME, CODE_PAGE));
        group.writeBytes(nameTriplet(FQN_CHARACTER_SET_NAME, CHARACTER_SET));
        group.writeBytes(new byte[] {
            4, TRIPLET_RESOURCE_LOCAL_ID, RESOURCE_TYPE_CODED_FONT, FONT_LOCAL_ID});
        int length = 2 + group.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(length >> 8);
        out.write(length);
        out.writeBytes(group.toByteArray());
        return out.toByteArray();
    }

    /** A Fully Qualified Name triplet: length, 02, type, format 00 (character string), name. */
    private static byte[] nameTriplet(int fqnType, String name) {
        byte[] text = AfpWriter.name(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(4 + text.length);
        out.write(TRIPLET_FQN);
        out.write(fqnType);
        out.write(0x00);
        out.writeBytes(text);
        return out.toByteArray();
    }

    /** PGD: unit base 10 inches on both axes, units per base, page size, 3 reserved bytes. */
    private static byte[] pageDescriptor() {
        ByteArrayOutputStream out = unitsAndSize();
        out.writeBytes(new byte[3]);
        return out.toByteArray();
    }

    /** PTD (format 2): the same units and size as the page, then 2 reserved bytes. */
    private static byte[] textDescriptor() {
        ByteArrayOutputStream out = unitsAndSize();
        out.writeBytes(new byte[2]);
        return out.toByteArray();
    }

    private static ByteArrayOutputStream unitsAndSize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); // X unit base: 10 inches
        out.write(0x00); // Y unit base: 10 inches
        write16(out, UNITS_PER_10_INCHES);
        write16(out, UNITS_PER_10_INCHES);
        write24(out, PAGE_WIDTH);
        write24(out, PAGE_HEIGHT);
        return out;
    }

    /**
     * The page's PTOCA: select font 1, then for each line a chain that moves to the baseline and
     * the inline position and writes the text with Transparent Data (TRN).
     */
    static byte[] pageText(Statement statement, int page) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PTOCA_ESCAPE_1);
        out.write(PTOCA_ESCAPE_2);
        out.writeBytes(new byte[] {3, (byte) SCFL_CHAINED, FONT_LOCAL_ID});
        boolean firstChain = true;
        for (TextLine line : StatementLayout.lines(statement, page)) {
            if (!firstChain) {
                out.write(PTOCA_ESCAPE_1);
                out.write(PTOCA_ESCAPE_2);
            }
            firstChain = false;
            out.write(4);
            out.write(AMB_CHAINED);
            write16(out, line.y() * UNITS_PER_POINT);
            out.write(4);
            out.write(AMI_CHAINED);
            write16(out, line.x() * UNITS_PER_POINT);
            byte[] text = Ebcdic.encodeStrict(line.text(), Ebcdic.IBM037);
            if (text.length > MAX_TRN_TEXT) {
                throw new IllegalArgumentException("text line is " + text.length
                        + " bytes; one TRN holds at most " + MAX_TRN_TEXT + ": " + line.text());
            }
            out.write(2 + text.length);
            out.write(TRN_UNCHAINED);
            out.writeBytes(text);
        }
        return out.toByteArray();
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }

    private static void write24(ByteArrayOutputStream out, int value) {
        out.write(value >> 16);
        out.write(value >> 8);
        out.write(value);
    }
}
