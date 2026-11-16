package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static dev.yjimmy.printlab.testsupport.AfpBytes.concat;
import static dev.yjimmy.printlab.testsupport.AfpBytes.triplet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class DumpAfpTest {

    private static final String MINIMAL = "/fixtures/afp/minimal.afp";
    private static final String MINIMAL_DUMP = "/fixtures/afp/minimal.dump.txt";

    @Test
    public void minimalFixtureMatchesGoldenDump() throws IOException {
        String expected = resourceText(MINIMAL_DUMP);

        StringBuilder out = new StringBuilder();
        int problems;
        try (InputStream in = getClass().getResourceAsStream(MINIMAL)) {
            problems = new AfpDump().dump(in, out);
        }

        assertEquals("dump of " + MINIMAL, expected, out.toString());
        assertEquals("problems", 0, problems);
    }

    /**
     * The fixture was written by {@code make_minimal_afp.py} (Python's own cp037 codec, not this
     * project's code). Building the same structure with {@link AfpBytes} must give the same bytes,
     * which documents what's in the fixture and cross-checks both writers.
     */
    @Test
    public void minimalFixtureIsTheDocumentedStructure() throws IOException {
        byte[] qualifier = triplet(0x80, bytes(0, 0, 0, 1, 0, 0, 0, 1));
        byte[] pageDescriptor = bytes(
                0x00, 0x00,             // units base: 10 inches, both axes
                0x38, 0x40, 0x38, 0x40, // 14400 units per 10 inches (1440 per inch)
                0x00, 0x2F, 0xD0,       // width 12240 units = 8.5 in
                0x00, 0x3D, 0xE0,       // height 15840 units = 11 in
                0x00, 0x00, 0x00);
        byte[] expected = AfpBytes.create()
                .begin(FieldType.BDT, "STMTRUN")
                .begin(FieldType.BNG, "G0000001")
                .field(FieldType.TLE,
                        concat(qualifier, AfpBytes.tleData("accountNumber", "1234567890")))
                .tle("name", "AVERY NGUYEN")
                .tle("zip", "80202")
                .begin(FieldType.BPG, "P0000001")
                .end(FieldType.BAG)
                .field(FieldType.PGD, pageDescriptor)
                .end(FieldType.EAG)
                .end(FieldType.EPG)
                .end(FieldType.ENG)
                .begin(FieldType.BNG, "G0000002")
                .tle("accountNumber", "5550001234")
                .begin(FieldType.BPG, "P0000002")
                .end(FieldType.EPG)
                .end(FieldType.ENG)
                .end(FieldType.EDT)
                .bytes();

        byte[] fixture;
        try (InputStream in = getClass().getResourceAsStream(MINIMAL)) {
            fixture = in.readAllBytes();
        }

        assertArrayEquals(MINIMAL, expected, fixture);
    }

    @Test
    public void nestingIndentsBetweenBeginAndEnd() {
        List<String> lines = dump(AfpBytes.create()
                .begin(FieldType.BDT, "D")
                .begin(FieldType.BNG, "G")
                .begin(FieldType.BPG, "P")
                .end(FieldType.EPG)
                .end(FieldType.ENG)
                .end(FieldType.EDT)
                .bytes());

        assertThat(lines).extracting(DumpAfpTest::fieldColumn).containsExactly(
                "Field",
                "BDT  Begin Document  \"D       \"",
                "  BNG  Begin Named Page Group  \"G       \"",
                "    BPG  Begin Page  \"P       \"",
                "    EPG  End Page",
                "  ENG  End Named Page Group",
                "EDT  End Document",
                "fields, 0 problems");
    }

    @Test
    public void unknownFieldShowsHexId() {
        List<String> lines = dump(AfpBytes.create().field(0xD3EEFF, 0, 0).bytes());

        assertEquals("line", "00000000       8  D3EEFF", lines.get(1));
    }

    @Test
    public void unclosedPageGroupIsReportedAtEndOfFile() {
        List<String> lines = dump(AfpBytes.create()
                .begin(FieldType.BDT, "D")
                .begin(FieldType.BNG, "G")
                .end(FieldType.EDT)
                .bytes());

        assertThat(lines).anyMatch(l -> l.contains("EDT  End Document  !! expected ENG first"));
        assertThat(lines).last().isEqualTo("3 fields, 1 problem");
    }

    @Test
    public void beginNeverClosedIsReported() {
        List<String> lines = dump(AfpBytes.create().begin(FieldType.BNG, "G").bytes());

        assertThat(lines).contains("!! BNG at 00000000 has no ENG before the end of the file");
    }

    @Test
    public void endWithoutBeginIsReported() {
        List<String> lines = dump(AfpBytes.create().end(FieldType.ENG).bytes());

        assertThat(lines.get(1)).endsWith("ENG  End Named Page Group  !! no matching Begin");
    }

    @Test
    public void endNameMustMatchItsBegin() {
        List<String> lines = dump(AfpBytes.create()
                .begin(FieldType.BNG, "G1")
                .field(FieldType.ENG, AfpBytes.name8("G2"))
                .bytes());

        assertThat(lines.get(2)).endsWith("ENG  End Named Page Group  !! name \"G2      \""
                + " doesn't match the BNG at 00000000 (\"G1      \")");
        assertThat(lines).last().isEqualTo("2 fields, 1 problem");
    }

    @Test
    public void endNameThatMatchesOrIsOmittedIsFine() {
        List<String> lines = dump(AfpBytes.create()
                .begin(FieldType.BDT, "D1")
                .begin(FieldType.BNG, "G1")
                .field(FieldType.ENG, AfpBytes.name8("G1"))
                .end(FieldType.EDT)
                .bytes());

        assertThat(lines).last().isEqualTo("4 fields, 0 problems");
    }

    @Test
    public void endNameStartingFfffMatchesAnyBegin() {
        List<String> lines = dump(AfpBytes.create()
                .begin(FieldType.BNG, "G1")
                .field(FieldType.ENG, bytes(0xFF, 0xFF, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40))
                .bytes());

        assertThat(lines).last().isEqualTo("2 fields, 0 problems");
    }

    @Test
    public void namedEndForAnUnnamedBeginIsReported() {
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.BPG, bytes(0xFF, 0xFF, 0, 0, 0, 0, 0, 0))
                .field(FieldType.EPG, AfpBytes.name8("P1"))
                .bytes());

        assertThat(lines.get(2)).endsWith("!! name \"P1      \" doesn't match the BPG at 00000000"
                + " (no name)");
    }

    @Test
    public void segmentsThatFollowEachOtherAreFine() {
        int seg = StructuredField.FLAG_SEGMENTED;
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.IPD.id(), seg, 0, bytes(0x01))
                .field(FieldType.IPD.id(), seg, 0, bytes(0x02))
                .field(FieldType.IPD.id(), 0, 0, bytes(0x03))
                .field(FieldType.NOP)
                .bytes());

        assertThat(lines).last().isEqualTo("4 fields, 0 problems");
    }

    @Test
    public void anotherFieldBetweenSegmentsIsReported() {
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.IPD.id(), StructuredField.FLAG_SEGMENTED, 0, bytes(0x01))
                .field(FieldType.NOP)
                .bytes());

        assertThat(lines.get(2)).endsWith("NOP  No Operation  !! the IPD at 00000000 is segmented,"
                + " but the next field isn't the next IPD segment");
        assertThat(lines).last().isEqualTo("2 fields, 1 problem");
    }

    @Test
    public void segmentedLastFieldIsReported() {
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.IPD.id(), StructuredField.FLAG_SEGMENTED, 0, bytes(0x01))
                .bytes());

        assertThat(lines).contains("!! the IPD at 00000000 is segmented, but the next field"
                + " isn't the next IPD segment");
    }

    @Test
    public void paddedTleDecodes() {
        byte[] afp = AfpBytes.create()
                .field(FieldType.TLE.id(), StructuredField.FLAG_PADDING, 0,
                        AfpBytes.concat(AfpBytes.tleData("zip", "80202"), bytes(0x40, 0x02)))
                .bytes();

        assertThat(dump(afp).get(1)).endsWith("zip = \"80202\"");
    }

    @Test
    public void mismatchedEndWithNoOpenMatchKeepsNesting() {
        List<String> lines = dump(AfpBytes.create()
                .begin(FieldType.BNG, "G")
                .end(FieldType.EPG)
                .end(FieldType.ENG)
                .bytes());

        assertThat(lines.get(2)).endsWith("  EPG  End Page  !! expected ENG first");
        assertThat(fieldColumn(lines.get(3))).isEqualTo("ENG  End Named Page Group");
    }

    @Test
    public void badTleIsReportedAndTheDumpContinues() {
        byte[] nameOnly = triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("zip")));
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.TLE, nameOnly)
                .tle("name", "OK")
                .bytes());

        assertThat(lines.get(1)).contains("!! bad TLE: TLE 'zip' has no attribute value");
        assertThat(lines.get(2)).endsWith("name = \"OK\"");
        assertThat(lines).last().isEqualTo("2 fields, 1 problem");
    }

    @Test
    public void formatErrorEndsTheDumpWithItsOffset() {
        List<String> lines = dump(AfpBytes.create().field(FieldType.NOP).raw(0x40).bytes());

        assertThat(lines.get(2)).startsWith("!! At byte offset 0x00000009 (9)");
        assertThat(lines).last().isEqualTo("1 fields, 1 problem");
    }

    @Test
    public void unprintableTextIsEscaped() {
        assertEquals("quote", "\"a\\\"b\\\\c\\u0007\"", AfpDump.quote("a\"b\\c\u0007"));
    }

    @Test
    public void unnamedBeginShowsNoName() {
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.BPG, bytes(0xFF, 0xFF, 0, 0, 0, 0, 0, 0))
                .bytes());

        assertThat(fieldColumn(lines.get(1))).isEqualTo("BPG  Begin Page");
    }

    @Test
    public void nonAsciiTextIsEscaped() {
        assertEquals("quote", "\"caf\\u00E9 \\u000A\"", AfpDump.quote("café \n"));
    }

    @Test
    public void beginWithFewerThanEightNameBytesShowsNoName() {
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.BPG, Ebcdic.encode("P1"))
                .bytes());

        assertThat(fieldColumn(lines.get(1))).isEqualTo("BPG  Begin Page");
    }

    @Test
    public void beginNameThatIsNotPrintableShowsNoName() {
        // 0x00 and 0x25 decode to NUL and line feed in IBM037.
        List<String> lines = dump(AfpBytes.create()
                .field(FieldType.BPG, bytes(0xC1, 0x00, 0x25, 0x40, 0x40, 0x40, 0x40, 0x40))
                .bytes());

        assertThat(fieldColumn(lines.get(1))).isEqualTo("BPG  Begin Page");
    }

    @Test
    public void codePageIsUsedForNamesAndTles() {
        Charset ibm500 = Charset.forName("IBM500");
        byte[] afp = AfpBytes.create()
                .field(FieldType.BNG, Ebcdic.encode("[G1]    ", ibm500))
                .field(FieldType.TLE, concat(
                        triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("k", ibm500))),
                        triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("[v]", ibm500)))))
                .end(FieldType.ENG)
                .bytes();
        StringBuilder out = new StringBuilder();

        new AfpDump(ibm500).dump(new ByteArrayInputStream(afp), out);

        List<String> lines = out.toString().lines().toList();
        assertThat(lines.get(1)).endsWith("\"[G1]    \"");
        assertThat(lines.get(2)).endsWith("k = \"[v]\"");
    }

    @Test
    public void outputErrorsAreUnchecked() {
        Appendable broken = new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                throw new IOException("pipe closed");
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                throw new IOException("pipe closed");
            }

            @Override
            public Appendable append(char c) throws IOException {
                throw new IOException("pipe closed");
            }
        };

        assertThrows(UncheckedIOException.class, () -> new AfpDump()
                .dump(new ByteArrayInputStream(new byte[0]), broken));
    }

    private static List<String> dump(byte[] afp) {
        StringBuilder out = new StringBuilder();
        new AfpDump().dump(new ByteArrayInputStream(afp), out);
        return out.toString().lines().toList();
    }

    /** The text after the offset and length columns; the summary line loses its count. */
    private static String fieldColumn(String line) {
        if (line.matches("\\d+ fields, .*")) {
            return line.substring(line.indexOf(' ') + 1);
        }
        return line.substring("Offset    Length  ".length());
    }

    private String resourceText(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            // Normalize in case a checkout converted the file to CRLF despite .gitattributes.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
