package dev.yjimmy.printlab.afp;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.OptionalInt;

/**
 * The MO:DCA and PTOCA encoders a page needs: the Map Coded Font, the page and text descriptors,
 * and the presentation text. The statement generator and the reprint mark both use them (PRD-15),
 * and this class reads the same structures back: the page descriptor's units and size, and a
 * map coded font's local IDs.
 *
 * <p>Units are the ones the page descriptor sets. Every page this project writes uses 1440 units
 * per inch, with the origin at the top-left of the page.
 */
public final class PageBuilder {

    /** The code page and character set of the font the statements (and reprint marks) use. */
    public static final String CODE_PAGE = "T1V10037";
    public static final String CHARACTER_SET = "C0D0GT10";
    /** The local ID the generator maps that font under. */
    public static final int FONT_LOCAL_ID = 1;
    /** Units per 10 inches for the descriptors this project writes: 1440 units per inch. */
    public static final int UNITS_PER_10_INCHES = 14_400;

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
    public static final int MAX_TRN_TEXT = 253;

    private PageBuilder() {}

    /** One piece of presentation text: where its baseline starts, and its EBCDIC bytes. */
    public record TextRun(int x, int y, byte[] text) {}

    /** A page's size in units, and how many units make an inch on each axis. */
    public record PageSize(double xUnitsPerInch, double yUnitsPerInch, int width, int height) {}

    /**
     * One MCF repeating group that maps a coded font: a 2-byte length counting itself, then the
     * code page name, the character set name, and the local ID that PTOCA's SCFL refers to.
     */
    public static byte[] mapCodedFont(int localId) {
        ByteArrayOutputStream group = new ByteArrayOutputStream();
        group.writeBytes(nameTriplet(FQN_CODE_PAGE_NAME, CODE_PAGE));
        group.writeBytes(nameTriplet(FQN_CHARACTER_SET_NAME, CHARACTER_SET));
        group.writeBytes(new byte[] {
            4, TRIPLET_RESOURCE_LOCAL_ID, RESOURCE_TYPE_CODED_FONT, (byte) localId});
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

    /** PGD: 10-inch unit bases on both axes, units per base, page size, 3 reserved bytes. */
    public static byte[] pageDescriptor(int width, int height) {
        ByteArrayOutputStream out = unitsAndSize(width, height);
        out.writeBytes(new byte[3]);
        return out.toByteArray();
    }

    /** PTD (format 2): the same units and size as the page, then 2 reserved bytes. */
    public static byte[] textDescriptor(int width, int height) {
        ByteArrayOutputStream out = unitsAndSize(width, height);
        out.writeBytes(new byte[2]);
        return out.toByteArray();
    }

    private static ByteArrayOutputStream unitsAndSize(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); // X unit base: 10 inches
        out.write(0x00); // Y unit base: 10 inches
        write16(out, UNITS_PER_10_INCHES);
        write16(out, UNITS_PER_10_INCHES);
        write24(out, width);
        write24(out, height);
        return out;
    }

    /**
     * Presentation text (PTOCA) for {@code runs}, with font {@code fontLocalId} selected first.
     * Each run moves to its baseline and inline position and writes its text with Transparent
     * Data (TRN). The page's origin is the top-left corner, so {@code y} counts down the page.
     *
     * @throws IllegalArgumentException if a run's text is too long for one TRN
     */
    public static byte[] ptoca(int fontLocalId, List<TextRun> runs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PTOCA_ESCAPE_1);
        out.write(PTOCA_ESCAPE_2);
        out.writeBytes(new byte[] {3, (byte) SCFL_CHAINED, (byte) fontLocalId});
        boolean firstChain = true;
        for (TextRun run : runs) {
            if (run.text().length > MAX_TRN_TEXT) {
                throw new IllegalArgumentException("text run is " + run.text().length
                        + " bytes; one TRN holds at most " + MAX_TRN_TEXT);
            }
            if (!firstChain) {
                out.write(PTOCA_ESCAPE_1);
                out.write(PTOCA_ESCAPE_2);
            }
            firstChain = false;
            out.write(4);
            out.write(AMB_CHAINED);
            write16(out, run.y());
            out.write(4);
            out.write(AMI_CHAINED);
            write16(out, run.x());
            out.write(2 + run.text().length);
            out.write(TRN_UNCHAINED);
            out.writeBytes(run.text());
        }
        return out.toByteArray();
    }

    /**
     * Reads a page descriptor (PGD): the unit base, units per base and page size. Only the 10-inch
     * base and the 10-centimetre base are supported.
     *
     * @throws IllegalArgumentException if the data is too short or the unit base is unknown
     */
    public static PageSize decodePgd(byte[] data) {
        if (data.length < 12) {
            throw new IllegalArgumentException("a PGD is 12 bytes or more, not " + data.length);
        }
        double xPerInch = unitsPerInch(data[0], u16(data, 2));
        double yPerInch = unitsPerInch(data[1], u16(data, 4));
        return new PageSize(xPerInch, yPerInch, u24(data, 6), u24(data, 9));
    }

    private static double unitsPerInch(byte unitBase, int unitsPerBase) {
        return switch (unitBase) {
            case 0x00 -> unitsPerBase / 10.0;               // 10 inches
            case 0x01 -> unitsPerBase / (10 / 2.54);        // 10 centimetres
            default -> throw new IllegalArgumentException(
                    "unsupported unit base " + (unitBase & 0xFF));
        };
    }

    /**
     * The local ID of the first coded font mapped in an MCF's data, if any. Each repeating group
     * starts with its 2-byte length, which counts itself; a coded font's local ID is in the 0x24
     * triplet.
     */
    public static OptionalInt codedFontLocalId(byte[] mcf) {
        for (int group = 0; group + 2 <= mcf.length; ) {
            int length = u16(mcf, group);
            if (length < 2 || group + length > mcf.length) {
                break;
            }
            int end = group + length;
            for (int t = group + 2; t + 2 <= end; ) {
                int triplet = mcf[t] & 0xFF;
                if (triplet < 2 || t + triplet > end) {
                    break;
                }
                if ((mcf[t + 1] & 0xFF) == TRIPLET_RESOURCE_LOCAL_ID && triplet >= 4
                        && (mcf[t + 2] & 0xFF) == RESOURCE_TYPE_CODED_FONT) {
                    return OptionalInt.of(mcf[t + 3] & 0xFF);
                }
                t += triplet;
            }
            group = end;
        }
        return OptionalInt.empty();
    }

    /**
     * The MCF data with one more repeating group mapping the generator's font under a local ID no
     * group in {@code mcf} uses yet. Returns the new data and the local ID it chose.
     */
    public static McfWithFont withFontAdded(byte[] mcf) {
        boolean[] used = new boolean[256];
        for (int group = 0; group + 2 <= mcf.length; ) {
            int length = u16(mcf, group);
            if (length < 2 || group + length > mcf.length) {
                break;
            }
            for (int t = group + 2; t + 2 <= group + length; ) {
                int triplet = mcf[t] & 0xFF;
                if (triplet < 2 || t + triplet > group + length) {
                    break;
                }
                if ((mcf[t + 1] & 0xFF) == TRIPLET_RESOURCE_LOCAL_ID && triplet >= 4) {
                    used[mcf[t + 3] & 0xFF] = true;
                }
                t += triplet;
            }
            group += length;
        }
        int localId = 1;
        while (localId < 256 && used[localId]) {
            localId++;
        }
        if (localId == 256) {
            throw new IllegalStateException("every local ID of the map coded font is in use");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(mcf);
        out.writeBytes(mapCodedFont(localId));
        return new McfWithFont(out.toByteArray(), localId);
    }

    /** MCF data with a font added, and the local ID the font is mapped under. */
    public record McfWithFont(byte[] data, int localId) {}

    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static int u24(byte[] data, int at) {
        return ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
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
