package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testdata.StatementAfpGenerator;
import dev.yjimmy.printlab.testdata.StatementDataGenerator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AfpReprintMarkTest {

    private static final String PARENT = "job-parent";

    /** An AFP document of {@code statements} statements, as the generator writes it. */
    private static byte[] generated(int statements, long seed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StatementAfpGenerator.write(new StatementDataGenerator(seed).generate(statements, 0), out);
        return out.toByteArray();
    }

    private static List<StructuredField> fields(byte[] afp) throws IOException {
        List<StructuredField> fields = new ArrayList<>();
        try (AfpReader reader = new AfpReader(new ByteArrayInputStream(afp))) {
            reader.forEachRemaining(fields::add);
        }
        return fields;
    }

    private static byte[] mark(byte[] afp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AfpReprintMark.mark(new ByteArrayInputStream(afp), out, PARENT, "REPRINT",
                AfpReprintMark.Position.DEFAULT);
        return out.toByteArray();
    }

    /** The indexes of the PTX fields that carry the reprint mark. */
    private static List<Integer> markIndexes(List<StructuredField> fields) {
        byte[] mark = Ebcdic.encodeStrict("REPRINT", Ebcdic.IBM037);
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            StructuredField f = fields.get(i);
            if (f.type() == FieldType.PTX && contains(f.data(), mark)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static int count(List<StructuredField> fields, FieldType type) {
        return (int) fields.stream().filter(f -> f.type() == type).count();
    }

    /** Whether the bytes hold the sequence. */
    private static boolean contains(byte[] haystack, byte... needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** A PGD in the given units per inch (10-inch base), for a page of the given size in units. */
    private static byte[] pgd(int unitsPer10Inches, int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(unitsPer10Inches >> 8);
        out.write(unitsPer10Inches);
        out.write(unitsPer10Inches >> 8);
        out.write(unitsPer10Inches);
        out.write(width >> 16);
        out.write(width >> 8);
        out.write(width);
        out.write(height >> 16);
        out.write(height >> 8);
        out.write(height);
        out.writeBytes(new byte[3]);
        return out.toByteArray();
    }

    /** A one-page document whose page has the given PGD and, if {@code mcf}, a font map. */
    private static byte[] onePage(byte[] pgd, boolean mcf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AfpWriter afp = new AfpWriter(out);
        afp.begin(FieldType.BDT, "RUN");
        afp.begin(FieldType.BNG, "S0000001");
        afp.begin(FieldType.BPG, "P0000001");
        afp.write(FieldType.BAG, new byte[0]);
        if (mcf) {
            afp.write(FieldType.MCF, PageBuilder.mapCodedFont(PageBuilder.FONT_LOCAL_ID));
        }
        afp.write(FieldType.PGD, pgd);
        afp.end(FieldType.EAG);
        afp.end(FieldType.EPG);
        afp.end(FieldType.ENG);
        afp.end(FieldType.EDT);
        afp.flush();
        return out.toByteArray();
    }

    @Test
    public void reprintedAfpHasReprintTleOnEveryDocument() throws IOException {
        byte[] marked = mark(generated(3, 7));

        List<Tle> tles = fields(marked).stream().filter(f -> f.type() == FieldType.TLE)
                .map(TleDecoder::decode).filter(t -> t.name().equals("Reprint")).toList();
        assertThat(tles).hasSize(1);
        assertThat(tles.get(0).value()).isEqualTo(PARENT);
    }

    @Test
    public void everyPageGetsOneReprintTextObject() throws IOException {
        byte[] original = generated(3, 7);
        int pages = count(fields(original), FieldType.BPG);

        List<Integer> marks = markIndexes(fields(mark(original)));

        assertThat(pages).isGreaterThan(0);
        assertThat(marks).as("one mark per page").hasSize(pages);
    }

    @Test
    public void theMarkSitsInItsOwnObjectBeforeEachPagesEpg() throws IOException {
        List<StructuredField> after = fields(mark(generated(2, 3)));

        for (int p : markIndexes(after)) {
            assertThat(after.get(p - 1).type()).isEqualTo(FieldType.BPT);
            assertThat(after.get(p + 1).type()).isEqualTo(FieldType.EPT);
            assertThat(after.get(p + 2).type()).isEqualTo(FieldType.EPG);
        }
    }

    @Test
    public void markPositionUsesPageUnitsFromPgd() throws IOException {
        // 240 units per inch, a 8.5 x 11 inch page: the default mark is right-aligned 2.5 inches
        // from the edge and 0.2 inches down, so it's at x = 1440 units and y = 48 units.
        byte[] afp = onePage(pgd(2_400, 2_040, 2_640), true);

        byte[] marked = mark(afp);

        StructuredField ptx = fields(marked).stream()
                .filter(f -> f.type() == FieldType.PTX).findFirst().orElseThrow();
        assertThat(contains(ptx.data(), (byte) 0x04, (byte) 0xD3, (byte) 0x00, (byte) 0x30))
                .as("baseline at 48 units").isTrue();
        assertThat(contains(ptx.data(), (byte) 0x04, (byte) 0xC7, (byte) 0x05, (byte) 0xA0))
                .as("inline position at 1440 units").isTrue();
    }

    @Test
    public void pageWithoutFontMappingGetsMcfEntry() throws IOException {
        byte[] afp = onePage(pgd(14_400, 12_240, 15_840), false);

        List<StructuredField> after = fields(mark(afp));

        StructuredField mcf = after.stream().filter(f -> f.type() == FieldType.MCF)
                .findFirst().orElseThrow();
        assertThat(PageBuilder.codedFontLocalId(mcf.data()).getAsInt()).isEqualTo(1);
    }

    @Test
    public void originalPageFieldsAreUnchanged() throws IOException {
        byte[] original = generated(3, 7);
        List<StructuredField> before = fields(original);

        List<StructuredField> after = fields(mark(original));

        // Drop what the mark adds, and the original fields come back in the same order.
        List<StructuredField> kept = new ArrayList<>();
        for (StructuredField f : after) {
            if (f.type() != FieldType.TLE || !isReprintTle(f)) {
                kept.add(f);
            }
        }
        List<StructuredField> remaining = new ArrayList<>(kept);
        int next = 0;
        for (StructuredField f : before) {
            while (next < remaining.size() && !sameField(remaining.get(next), f)) {
                next++;
            }
            assertThat(next).as("original field %s is still there, in order", f.type())
                    .isLessThan(remaining.size());
            next++;
        }
    }

    @Test
    public void reprintedAfpReadsBackCleanly() throws IOException {
        List<StructuredField> after = fields(mark(generated(4, 11)));

        assertThat(after).isNotEmpty();
        assertThat(after.get(0).type()).isEqualTo(FieldType.BDT);
        assertThat(after.get(after.size() - 1).type()).isEqualTo(FieldType.EDT);
    }

    @Test
    public void documentWithoutAPageGroupIsRefused() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AfpWriter afp = new AfpWriter(out);
        afp.begin(FieldType.BDT, "RUN");
        afp.end(FieldType.EDT);
        afp.flush();

        assertThrows(IllegalStateException.class, () -> mark(out.toByteArray()));
    }

    private static boolean isReprintTle(StructuredField f) {
        return TleDecoder.decode(f).name().equals("Reprint");
    }

    private static boolean sameField(StructuredField a, StructuredField b) {
        return a.type() == b.type() && java.util.Arrays.equals(a.data(), b.data());
    }
}
