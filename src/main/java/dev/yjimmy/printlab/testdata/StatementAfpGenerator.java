package dev.yjimmy.printlab.testdata;

import dev.yjimmy.printlab.afp.AfpWriter;
import dev.yjimmy.printlab.afp.PageBuilder;
import dev.yjimmy.printlab.afp.Ebcdic;
import dev.yjimmy.printlab.afp.FieldType;
import dev.yjimmy.printlab.afp.TleEncoder;
import dev.yjimmy.printlab.testdata.StatementLayout.TextLine;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 *       TLE zip = ...               (or ACCT_NO, CUST_NAME, ZIP_CODE: see TleNames)
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

    /** Units per point, at 1440 units per inch. */
    static final int UNITS_PER_POINT = 20;
    /** US Letter, 8.5 x 11 inches, in units. */
    static final int PAGE_WIDTH = 12_240;
    static final int PAGE_HEIGHT = 15_840;

    /** The code page and character set of the one font every page uses (local ID 1). */
    static final String CODE_PAGE = PageBuilder.CODE_PAGE;
    static final String CHARACTER_SET = PageBuilder.CHARACTER_SET;
    private static final int FONT_LOCAL_ID = PageBuilder.FONT_LOCAL_ID;

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
        generate(statements, badAddresses, TleNames.PROPERTY_NAMES, out);
    }

    /** As {@link #generate(int, int, Path)}, with the TLEs named {@code names}. */
    public void generate(int statements, int badAddresses, TleNames names, Path out)
            throws IOException {
        List<Statement> run = data.generate(statements, badAddresses);
        try (OutputStream file = Files.newOutputStream(out)) {
            write(run, names, file);
        }
    }

    /**
     * The names of the three TLEs in each page group. Real files name the same index values
     * differently depending on the application that composed them.
     */
    public record TleNames(String accountNumber, String name, String zip) {

        /** The document property names the workflows use: the default. */
        public static final TleNames PROPERTY_NAMES = new TleNames("accountNumber", "name", "zip");
        /** Production-style names, which {@code statements-afp-legacy.yaml} maps back. */
        public static final TleNames LEGACY = new TleNames("ACCT_NO", "CUST_NAME", "ZIP_CODE");

        /** {@code property} or {@code legacy}, as {@code generate-afp --tle-names} takes them. */
        public static TleNames named(String option) {
            return switch (option) {
                case "property" -> PROPERTY_NAMES;
                case "legacy" -> LEGACY;
                default -> throw new IllegalArgumentException("--tle-names is 'property' or"
                        + " 'legacy', got '" + option + "'");
            };
        }
    }

    /** Writes {@code statements} as one AFP document to {@code out}, without closing it. */
    public static void write(List<Statement> statements, OutputStream out) throws IOException {
        write(statements, TleNames.PROPERTY_NAMES, out);
    }

    /** As {@link #write(List, OutputStream)}, with the TLEs named {@code names}. */
    public static void write(List<Statement> statements, TleNames names, OutputStream out)
            throws IOException {
        AfpWriter afp = new AfpWriter(out);
        afp.begin(FieldType.BDT, "PRINTRUN");
        int pageNumber = 0;
        for (int s = 0; s < statements.size(); s++) {
            Statement statement = statements.get(s);
            afp.begin(FieldType.BNG, token('S', s + 1));
            afp.write(FieldType.TLE, TleEncoder.encode(names.accountNumber(),
                    statement.accountNumber()));
            afp.write(FieldType.TLE, TleEncoder.encode(names.name(), statement.name()));
            afp.write(FieldType.TLE, TleEncoder.encode(names.zip(), statement.zip()));
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
        afp.write(FieldType.MCF, PageBuilder.mapCodedFont(FONT_LOCAL_ID));
        afp.write(FieldType.PGD, PageBuilder.pageDescriptor(PAGE_WIDTH, PAGE_HEIGHT));
        afp.write(FieldType.PTD, PageBuilder.textDescriptor(PAGE_WIDTH, PAGE_HEIGHT));
        afp.end(FieldType.EAG);
    }

    /**
     * The page's PTOCA: select font 1, then one run per text line, placed where
     * {@link StatementLayout} puts it, converted from points to units.
     */
    static byte[] pageText(Statement statement, int page) {
        List<PageBuilder.TextRun> runs = new ArrayList<>();
        for (TextLine line : StatementLayout.lines(statement, page)) {
            byte[] text = Ebcdic.encodeStrict(line.text(), Ebcdic.IBM037);
            if (text.length > PageBuilder.MAX_TRN_TEXT) {
                throw new IllegalArgumentException("text line is " + text.length
                        + " bytes; one TRN holds at most " + PageBuilder.MAX_TRN_TEXT + ": "
                        + line.text());
            }
            runs.add(new PageBuilder.TextRun(line.x() * UNITS_PER_POINT,
                    line.y() * UNITS_PER_POINT, text));
        }
        return PageBuilder.ptoca(FONT_LOCAL_ID, runs);
    }
}
