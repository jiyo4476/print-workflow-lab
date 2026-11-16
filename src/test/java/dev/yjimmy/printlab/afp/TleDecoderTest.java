package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static dev.yjimmy.printlab.testsupport.AfpBytes.concat;
import static dev.yjimmy.printlab.testsupport.AfpBytes.triplet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.io.ByteArrayInputStream;
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
    public void nonTleFieldIsRejected() {
        StructuredField nop = new StructuredField(0, 8, FieldType.NOP.id(), 0, 0, new byte[0],
                new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> TleDecoder.decode(nop));
    }
}
