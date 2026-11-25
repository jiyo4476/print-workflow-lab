package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testdata.AfpFramer;
import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** What {@link AfpReader} accepts between fields, and what it says when a file isn't framed so. */
public class AfpFramingTest {

    /** BDT, BNG, TLE, ENG, EDT: the first two fields are 17 bytes each with their 0x5A. */
    private static byte[] strict() {
        return AfpBytes.create().begin(FieldType.BDT, "RUN").begin(FieldType.BNG, "G1")
                .tle("accountNumber", "111").end(FieldType.ENG).end(FieldType.EDT).bytes();
    }

    private static final List<FieldType> TYPES = List.of(FieldType.BDT, FieldType.BNG,
            FieldType.TLE, FieldType.ENG, FieldType.EDT);

    private static List<StructuredField> read(byte[] bytes, AfpFraming framing) {
        return AfpReader.readAll(new ByteArrayInputStream(bytes), framing);
    }

    private static List<FieldType> types(List<StructuredField> fields) {
        return fields.stream().map(StructuredField::type).toList();
    }

    private static AfpFormatException failure(byte[] bytes, AfpFraming framing) {
        return assertThrows(AfpFormatException.class, () -> read(bytes, framing));
    }

    // Every framing, same fields.

    @Test
    public void everyFramingReadsTheSameFields() {
        List<StructuredField> expected = read(strict(), AfpFraming.STRICT);

        for (AfpFraming framing : List.of(AfpFraming.PADDED, AfpFraming.RDW, AfpFraming.BDW)) {
            byte[] framed = AfpFramer.frame(strict(), framing);
            List<StructuredField> fields = read(framed, framing);
            assertThat(types(fields)).as(framing.toString()).isEqualTo(TYPES);
            assertThat(fields).as(framing.toString()).extracting(StructuredField::data)
                    .containsExactlyElementsOf(expected.stream().map(StructuredField::data).toList());
        }
    }

    @Test
    public void strictIsTheDefaultAndKeepsAllItsChecks() {
        byte[] padded = AfpFramer.frame(strict(), AfpFraming.PADDED);

        AfpFormatException e = assertThrows(AfpFormatException.class,
                () -> AfpReader.readAll(new ByteArrayInputStream(padded)));

        assertEquals("offset of the first line ending", 17, e.offset());
        assertThat(e.getMessage()).contains("found 0x0D").contains("padded, rdw, bdw or auto");
    }

    // Padded.

    @Test
    public void paddedSkipsLineEndingsAndZeroBytesBetweenFieldsAndAtTheEnd() {
        byte[] framed = AfpFramer.frame(strict(), AfpFraming.PADDED);

        assertThat(framed[17]).isEqualTo((byte) 0x0D);
        assertThat(framed[framed.length - 1]).isEqualTo((byte) 0x00);
        assertThat(types(read(framed, AfpFraming.PADDED))).isEqualTo(TYPES);
    }

    @Test
    public void paddedStillRejectsOtherBytesBetweenFields() {
        byte[] afp = AfpBytes.create().end(FieldType.EPG).raw(0x0D, 0x0A, 0x40).bytes();

        AfpFormatException e = failure(afp, AfpFraming.PADDED);

        assertEquals("offset of the 0x40", 11, e.offset());
        assertThat(e.getMessage()).contains("found 0x40");
    }

    // RDW.

    @Test
    public void rdwOffsetsAreFileOffsets() {
        byte[] framed = AfpFramer.frame(strict(), AfpFraming.RDW);

        List<StructuredField> fields = read(framed, AfpFraming.RDW);

        long expected = 4;
        for (StructuredField field : fields) {
            assertEquals(field.typeName() + " offset", expected, field.offset());
            expected += (1 + field.length()) + 4;
        }
        assertEquals("the whole file was read", framed.length + 4, expected);
    }

    @Test
    public void rdwWordMustEndInTwoZeroBytes() {
        byte[] bytes = AfpBytes.bytes(0x00, 0x0D, 0x00, 0x01, 0x5A, 0x00, 0x08, 0xD3, 0xA8, 0xA8,
                0x00, 0x00, 0x00);

        AfpFormatException e = failure(bytes, AfpFraming.RDW);

        assertEquals("offset", 0, e.offset());
        assertThat(e.getMessage()).contains("record descriptor word (RDW)")
                .contains("two zero bytes").contains("found 0x0001");
    }

    @Test
    public void rdwRecordTooShortForAFieldFails() {
        AfpFormatException e = failure(AfpBytes.bytes(0x00, 0x08, 0x00, 0x00, 0x5A, 0, 0, 0),
                AfpFraming.RDW);

        assertThat(e.getMessage()).contains("record length 8 is too short");
    }

    @Test
    public void rdwWordCutShortFails() {
        AfpFormatException e = failure(AfpBytes.bytes(0x00, 0x11), AfpFraming.RDW);

        assertThat(e.getMessage()).contains("truncated record descriptor word")
                .contains("ends 2 byte(s) into its 4");
    }

    @Test
    public void rdwRecordCutShortFails() {
        AfpFormatException e = failure(AfpBytes.bytes(0x00, 21, 0x00, 0x00, 0x5A, 0x00, 0x10,
                0xD3, 0xA8), AfpFraming.RDW);

        assertEquals("offset", 0, e.offset());
        assertThat(e.getMessage()).contains("truncated record").contains("says 21 bytes")
                .contains("ends after 9");
    }

    @Test
    public void rdwRecordWithBytesAfterItsFieldFails() {
        byte[] field = AfpBytes.create().begin(FieldType.BDT, "RUN").bytes();
        byte[] record = AfpBytes.concat(AfpBytes.bytes(0x00, 4 + field.length + 3, 0x00, 0x00),
                field, AfpBytes.bytes(0x00, 0x00, 0x00));

        AfpFormatException e = failure(record, AfpFraming.RDW);

        assertEquals("offset of the field", 4, e.offset());
        assertThat(e.getMessage()).contains("3 byte(s) of the record follow")
                .contains("exactly one");
    }

    @Test
    public void rdwRecordMustStartWithTheIntroducer() {
        byte[] record = AfpBytes.concat(AfpBytes.bytes(0x00, 4 + 16, 0x00, 0x00),
                AfpBytes.bytes(0x40, 0x00, 0x10, 0xD3, 0xA8, 0xA8, 0x00, 0x00),
                AfpBytes.name8("RUN"));

        AfpFormatException e = failure(record, AfpFraming.RDW);

        assertThat(e.getMessage()).contains("found 0x40").contains("start of a record's field");
    }

    @Test
    public void rdwFieldLongerThanItsRecordFails() {
        // The field says 48 bytes, but the record holds 13 bytes after its word.
        byte[] record = AfpBytes.concat(AfpBytes.bytes(0x00, 4 + 13, 0x00, 0x00),
                AfpBytes.bytes(0x5A, 0x00, 0x30, 0xD3, 0xA8, 0xA8, 0x00, 0x00, 1, 2, 3, 4, 5));

        AfpFormatException e = failure(record, AfpFraming.RDW);

        assertThat(e.getMessage()).contains("truncated field").contains("the record ends");
    }

    @Test
    public void rdwEmptyFileHasNoFields() {
        assertThat(read(new byte[0], AfpFraming.RDW)).isEmpty();
    }

    // BDW.

    @Test
    public void bdwReadsAFileOfSeveralBlocks() {
        AfpBytes afp = AfpBytes.create().begin(FieldType.BDT, "RUN");
        for (int i = 0; i < 3000; i++) {
            afp.begin(FieldType.BNG, "G" + i).end(FieldType.ENG);
        }
        byte[] strict = afp.end(FieldType.EDT).bytes();
        byte[] framed = AfpFramer.frame(strict, AfpFraming.BDW);

        List<StructuredField> fields = read(framed, AfpFraming.BDW);

        assertThat(strict.length).as("more than one block's worth").isGreaterThan(32_760 * 2);
        assertThat(fields).hasSize(2 + 2 * 3000);
        assertThat(fields.get(fields.size() - 1).type()).isEqualTo(FieldType.EDT);
    }

    @Test
    public void bdwWordMustEndInTwoZeroBytes() {
        AfpFormatException e = failure(AfpBytes.bytes(0x00, 0x20, 0x00, 0x09), AfpFraming.BDW);

        assertThat(e.getMessage()).contains("block descriptor word (BDW)").contains("two zero bytes");
    }

    @Test
    public void bdwBlockTooShortToHoldARecordFails() {
        AfpFormatException e = failure(AfpBytes.bytes(0x00, 0x0A, 0x00, 0x00, 0, 0, 0, 0, 0, 0),
                AfpFraming.BDW);

        assertThat(e.getMessage()).contains("block length 10 is too short");
    }

    @Test
    public void bdwRecordRunningPastItsBlockFails() {
        byte[] field = AfpBytes.create().begin(FieldType.BDT, "RUN").bytes();
        // The block says 22 bytes, but its one record is 4 + 17 = 21 bytes after the 4-byte BDW.
        byte[] bytes = AfpBytes.concat(AfpBytes.bytes(0x00, 22, 0x00, 0x00),
                AfpBytes.bytes(0x00, 4 + field.length, 0x00, 0x00), field);

        AfpFormatException e = failure(bytes, AfpFraming.BDW);

        assertEquals("offset of the record", 4, e.offset());
        assertThat(e.getMessage()).contains("runs 3 byte(s) past the end of its block");
    }

    @Test
    public void bdwFileEndingBeforeItsBlockDoesFails() {
        byte[] field = AfpBytes.create().begin(FieldType.BDT, "RUN").bytes();
        byte[] bytes = AfpBytes.concat(AfpBytes.bytes(0x00, 100, 0x00, 0x00),
                AfpBytes.bytes(0x00, 4 + field.length, 0x00, 0x00), field);

        AfpFormatException e = failure(bytes, AfpFraming.BDW);

        assertThat(e.getMessage()).contains("truncated block").contains("75 byte(s)");
    }

    // Auto.

    @Test
    public void autoFindsEachFraming() {
        for (AfpFraming framing : List.of(AfpFraming.STRICT, AfpFraming.PADDED, AfpFraming.RDW,
                AfpFraming.BDW)) {
            byte[] framed = AfpFramer.frame(strict(), framing);

            assertThat(types(read(framed, AfpFraming.AUTO))).as(framing.toString())
                    .isEqualTo(TYPES);
        }
    }

    @Test
    public void autoOnAnEmptyFileReadsNothing() {
        assertThat(read(new byte[0], AfpFraming.AUTO)).isEmpty();
    }

    @Test
    public void autoOnAFileItCannotPlaceSaysWhatItLooksLike() {
        AfpFormatException e = failure("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n"
                .getBytes(StandardCharsets.US_ASCII), AfpFraming.AUTO);

        assertEquals("offset", 0, e.offset());
        assertThat(e.getMessage()).contains("can't tell how this file is framed")
                .contains("%PDF-").contains("SplitPdf");
    }

    @Test
    public void autoOnUnrecognisedBytesStillFailsWithoutAGuess() {
        AfpFormatException e = failure(AfpBytes.bytes(0xFF, 0xFE, 0x01, 0x02, 0x03, 0x04),
                AfpFraming.AUTO);

        assertThat(e.getMessage()).contains("can't tell how this file is framed")
                .doesNotContain("looks like");
    }

    // What a wrong file says.

    @Test
    public void strictFailureAtTheStartSaysWhatTheFileLooksLike() {
        AfpFormatException pdf = failure("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII),
                AfpFraming.STRICT);
        AfpFormatException text = failure("Dear customer, your statement is ready.\n"
                .getBytes(StandardCharsets.US_ASCII), AfpFraming.STRICT);

        assertThat(pdf.getMessage()).contains("found 0x25").contains("is a PDF")
                .contains("padded, rdw, bdw or auto");
        assertThat(text.getMessage()).contains("printable ASCII text");
    }

    @Test
    public void failureAfterTheStartDoesNotGuessAtTheFile() {
        byte[] afp = AfpBytes.create().end(FieldType.EPG).raw(0x25).bytes();

        AfpFormatException e = failure(afp, AfpFraming.STRICT);

        assertThat(e.getMessage()).contains("found 0x25").doesNotContain("is a PDF");
    }

    @Test
    public void dumpReadsFramedFilesAndReportsFileOffsets() {
        byte[] framed = AfpFramer.frame(strict(), AfpFraming.RDW);
        StringBuilder out = new StringBuilder();

        int problems = new AfpDump(Ebcdic.IBM037, AfpFraming.RDW)
                .dump(new ByteArrayInputStream(framed), out);

        assertEquals("problems", 0, problems);
        // The first field's 0x5A is at offset 4, after the first record descriptor word.
        assertThat(out.toString()).contains("00000004").contains("BDT").contains("EDT");
    }

    @Test
    public void dumpOfAFileInTheWrongFramingReportsTheProblem() {
        byte[] framed = AfpFramer.frame(strict(), AfpFraming.RDW);
        StringBuilder out = new StringBuilder();

        int problems = new AfpDump().dump(new ByteArrayInputStream(framed), out);

        assertThat(problems).isGreaterThan(0);
        assertThat(out.toString()).contains("!!").contains("expected 0x5A");
    }

    @Test
    public void everyFramingIsNamedInWorkflowsInAnyCase() {
        List<AfpFraming> found = new ArrayList<>();
        for (String name : List.of("strict", "Padded", " RDW ", "bdw", "AUTO")) {
            found.add(AfpFraming.named(name));
        }

        assertThat(found).containsExactly(AfpFraming.STRICT, AfpFraming.PADDED, AfpFraming.RDW,
                AfpFraming.BDW, AfpFraming.AUTO);
    }
}
