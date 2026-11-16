package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static dev.yjimmy.printlab.testsupport.AfpBytes.concat;
import static dev.yjimmy.printlab.testsupport.AfpBytes.triplet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TleDecoderTest {

    @Test
    public void decodesNameAndValue() {
        // 11 02 0B 00 + "accountNumber" (13 bytes) | 0E 36 00 00 + "1234567890" (10 bytes)
        byte[] data = AfpBytes.tleData("accountNumber", "1234567890");
        assertEquals("name triplet length", 0x11, data[0]);

        Tle tle = TleDecoder.decode(data, Ebcdic.IBM037);

        assertEquals("TLE", new Tle("accountNumber", "1234567890"), tle);
    }

    @Test
    public void decodesTleFieldReadFromAFile() {
        byte[] afp = AfpBytes.create().tle("zip", "80202").bytes();
        StructuredField field = AfpReader.readAll(new ByteArrayInputStream(afp)).get(0);

        assertEquals("TLE", new Tle("zip", "80202"), TleDecoder.decode(field));
    }

    @Test
    public void ignoresUnknownTriplets() {
        byte[] data = concat(
                // Attribute Qualifier: sequence number and level. Real TLEs often carry one.
                triplet(0x80, bytes(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01)),
                // An FQN of another type (not an attribute name) is not the name.
                triplet(0x02, concat(bytes(0x01, 0x00), Ebcdic.encode("other"))),
                AfpBytes.tleData("name", "AVERY NGUYEN"));

        Tle tle = TleDecoder.decode(data, Ebcdic.IBM037);

        assertEquals("TLE", new Tle("name", "AVERY NGUYEN"), tle);
    }

    @Test
    public void missingValueTripletFails() {
        byte[] data = triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("zip")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(data, Ebcdic.IBM037));

        assertThat(e.getMessage()).contains("'zip'").contains("value");
    }

    @Test
    public void missingNameTripletFails() {
        byte[] data = triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("80202")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(data, Ebcdic.IBM037));

        assertThat(e.getMessage()).contains("name");
    }

    @Test
    public void nameThatIsNotACharacterStringFails() {
        // FQN format 10 is an ASN.1 object identifier, not text; decoding it would give garbage.
        byte[] data = concat(
                triplet(0x02, bytes(0x0B, 0x10, 0x06, 0x03, 0x2B, 0x0C, 0x02)),
                triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("80202"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(data, Ebcdic.IBM037));

        assertThat(e.getMessage()).contains("data offset 0").contains("FQN format 10");
    }

    @Test
    public void tripletRunningPastTheDataFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(bytes(0x20, 0x02, 0x0B), Ebcdic.IBM037));

        assertThat(e.getMessage()).contains("data offset 0").contains("length 32");
    }

    @Test
    public void tripletLengthsOutsideTheModcaRangeFail() {
        // MO:DCA triplets are 3-254 bytes. Length 0 or 1 would also never advance the loop.
        byte[] valid = AfpBytes.tleData("zip", "80202");
        for (int length : new int[] {0, 1, 2, 255}) {
            byte[] data = concat(bytes(length, 0x80, 0x00), valid);

            IllegalArgumentException e = assertThrows("length " + length,
                    IllegalArgumentException.class, () -> TleDecoder.decode(data, Ebcdic.IBM037));

            assertThat(e.getMessage()).contains("data offset 0").contains("length " + length)
                    .contains("3-254");
        }
    }

    @Test
    public void tripletsOfTheBoundaryLengthsAreAccepted() {
        byte[] shortest = triplet(0x80, bytes(0x00));
        byte[] longest = triplet(0x80, new byte[252]);
        byte[] data = concat(shortest, longest, AfpBytes.tleData("zip", "80202"));
        assertEquals("shortest", 3, shortest[0]);
        assertEquals("longest", 254, longest[0] & 0xFF);

        assertEquals("TLE", new Tle("zip", "80202"), TleDecoder.decode(data, Ebcdic.IBM037));
    }

    @Test
    public void tripletsShorterThanTheirMinimumFail() {
        // MO:DCA: an FQN is 5-254 bytes (at least one name byte), an Attribute Value 4-254.
        byte[] value = triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("80202")));
        byte[] name = triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("zip")));

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(concat(bytes(0x04, 0x02, 0x0B, 0x00), value),
                        Ebcdic.IBM037)).getMessage())
                .isEqualTo("Fully Qualified Name triplet at data offset 0 has length 4;"
                        + " the minimum is 5");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(concat(name, bytes(0x03, 0x36, 0x00)),
                        Ebcdic.IBM037)).getMessage())
                .isEqualTo("Attribute Value triplet at data offset 7 has length 3;"
                        + " the minimum is 4");
    }

    @Test
    public void nameAndValueMustEachOccurOnce() {
        byte[] name = triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("zip")));
        byte[] value = triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("80202")));

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(concat(name, name, value), Ebcdic.IBM037)).getMessage())
                .isEqualTo("second attribute name triplet at data offset 7; a TLE has exactly one");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(concat(name, value, value), Ebcdic.IBM037)).getMessage())
                .isEqualTo("second attribute value triplet at data offset 16; a TLE has exactly"
                        + " one");
    }

    @Test
    public void otherFqnTypesAreIgnored() {
        // X'0C' Process Element Name (the TLE's own name) and X'87' Begin Page Reference.
        byte[] data = concat(
                triplet(0x02, concat(bytes(0x0C, 0x00), Ebcdic.encode("TAG1"))),
                triplet(0x02, concat(bytes(0x87, 0x00), Ebcdic.encode("P0000001"))),
                AfpBytes.tleData("zip", "80202"));

        assertEquals("TLE", new Tle("zip", "80202"), TleDecoder.decode(data, Ebcdic.IBM037));
    }

    @Test
    public void codePageTripletAppliesToTheTripletsAfterIt() {
        // '[' is 0x4A in IBM500 but 0xBA in IBM037. X'01' GCSGID FFFF, CPGID 500 (X'01F4').
        Charset ibm500 = Charset.forName("IBM500");
        byte[] data = concat(
                triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("a[1]"))),
                bytes(0x06, 0x01, 0xFF, 0xFF, 0x01, 0xF4),
                triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("x[y]", ibm500))));

        assertEquals("name in IBM037, value in IBM500", new Tle("a[1]", "x[y]"),
                TleDecoder.decode(data, Ebcdic.IBM037));
    }

    @Test
    public void codePageTripletCanGiveACcsid() {
        // CCSID form: GCSGID 0000, then CCSID 1208 (UTF-8).
        byte[] data = concat(
                bytes(0x06, 0x01, 0x00, 0x00, 0x04, 0xB8),
                triplet(0x02, concat(bytes(0x0B, 0x00), "city".getBytes(StandardCharsets.UTF_8))),
                triplet(0x36, concat(bytes(0x00, 0x00), "Zürich".getBytes(StandardCharsets.UTF_8))));

        assertEquals("UTF-8", new Tle("city", "Zürich"), TleDecoder.decode(data, Ebcdic.IBM037));
    }

    @Test
    public void badCodePageTripletFails() {
        byte[] valid = AfpBytes.tleData("zip", "80202");

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(concat(bytes(0x05, 0x01, 0xFF, 0xFF, 0x01), valid),
                        Ebcdic.IBM037)).getMessage())
                .isEqualTo("CGCSGID triplet at data offset 0 has length 5; it must be 6");
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> TleDecoder.decode(concat(bytes(0x06, 0x01, 0xFF, 0xFF, 0xFF, 0xFE), valid),
                        Ebcdic.IBM037)).getMessage())
                .startsWith("CGCSGID triplet at data offset 0: code page 65534");
    }

    @Test
    public void emptyValueIsAllowed() {
        byte[] data = concat(
                triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("suite"))),
                triplet(0x36, bytes(0x00, 0x00)));

        assertEquals("TLE", new Tle("suite", ""), TleDecoder.decode(data, Ebcdic.IBM037));
    }

    @Test
    public void codePageIsUsedForNameAndValue() {
        // '[' is 0x4A in IBM500 (in IBM037 0x4A is a cent sign).
        Charset ibm500 = Charset.forName("IBM500");
        byte[] asIbm500 = concat(
                triplet(0x02, concat(bytes(0x0B, 0x00), Ebcdic.encode("a[1]", ibm500))),
                triplet(0x36, concat(bytes(0x00, 0x00), Ebcdic.encode("x[y]", ibm500))));

        assertEquals("IBM500", new Tle("a[1]", "x[y]"), TleDecoder.decode(asIbm500, ibm500));
        assertThat(TleDecoder.decode(asIbm500, Ebcdic.IBM037).value())
                .as("same bytes, wrong code page").isNotEqualTo("x[y]");
    }

    @Test
    public void nonTleFieldIsRejected() {
        StructuredField nop = new StructuredField(0, 8, FieldType.NOP.id(), 0, 0, new byte[0],
                new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> TleDecoder.decode(nop));
    }
}
