package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public class AfpWriterTest {

    @Test
    public void writtenFieldIsReadBackIdentically() throws IOException {
        byte[] tle = TleEncoder.encode("zip", "80202");
        // An extension: its length byte (3, counting itself) then 2 bytes, then the data.
        byte[] withExtension = {3, 0x11, 0x22, 0x33, 0x44};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (AfpWriter writer = new AfpWriter(out)) {
            writer.begin(FieldType.BNG, "S0000001")
                    .write(FieldType.TLE, tle)
                    .write(FieldType.NOP.id(), StructuredField.FLAG_EXTENSION, withExtension)
                    .end(FieldType.ENG);
        }

        List<StructuredField> fields = AfpReader.readAll(new ByteArrayInputStream(out.toByteArray()));

        assertThat(fields).extracting(StructuredField::type)
                .containsExactly(FieldType.BNG, FieldType.TLE, FieldType.NOP, FieldType.ENG);
        assertEquals("BNG name", "S0000001", Ebcdic.decode(fields.get(0).data()));
        assertArrayEquals("TLE data", tle, fields.get(1).data());
        assertEquals("TLE offset", 1 + 8 + 8, fields.get(1).offset());
        assertEquals("NOP flags", StructuredField.FLAG_EXTENSION, fields.get(2).flags());
        assertArrayEquals("NOP extension", new byte[] {0x11, 0x22}, fields.get(2).extension());
        assertArrayEquals("NOP data", new byte[] {0x33, 0x44}, fields.get(2).data());
        assertEquals("ENG data", 0, fields.get(3).data().length);
    }

    @Test
    public void copiedFieldKeepsItsExtensionAndDropsPadding() throws IOException {
        // NOP, flags extension + segmented + padding: extension (3, AA, BB), data (01 02),
        // then 2 bytes of padding whose last byte is its length.
        byte[] original = AfpBytes.create().field(FieldType.NOP.id(),
                StructuredField.FLAG_EXTENSION | StructuredField.FLAG_SEGMENTED
                        | StructuredField.FLAG_PADDING, 0x0001,
                AfpBytes.bytes(3, 0xAA, 0xBB, 0x01, 0x02, 0x00, 0x02)).bytes();
        StructuredField read = AfpReader.readAll(new ByteArrayInputStream(original)).get(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (AfpWriter writer = new AfpWriter(out)) {
            writer.write(read);
        }

        StructuredField copy = AfpReader.readAll(new ByteArrayInputStream(out.toByteArray())).get(0);
        assertEquals("flags", StructuredField.FLAG_EXTENSION | StructuredField.FLAG_SEGMENTED,
                copy.flags());
        assertArrayEquals("extension", AfpBytes.bytes(0xAA, 0xBB), copy.extension());
        assertArrayEquals("data", AfpBytes.bytes(0x01, 0x02), copy.data());
        assertEquals("reserved", 0, copy.reserved());
        assertEquals("length", 8 + 3 + 2, copy.length());
    }

    @Test
    public void writesTheSameBytesAsTheTestBuilder() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AfpWriter(out).begin(FieldType.BNG, "G1").write(FieldType.TLE,
                TleEncoder.encode("zip", "80202")).end(FieldType.ENG).flush();

        byte[] expected = AfpBytes.create()
                .begin(FieldType.BNG, "G1").tle("zip", "80202").end(FieldType.ENG).bytes();

        assertArrayEquals(expected, out.toByteArray());
    }

    @Test
    public void lengthIsComputedFromData() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AfpWriter writer = new AfpWriter(out);
        writer.write(FieldType.NOP, new byte[300]).flush();

        byte[] bytes = out.toByteArray();
        assertEquals("0x5A + header + data", 1 + 8 + 300, bytes.length);
        assertEquals("introducer", 0x5A, bytes[0] & 0xFF);
        assertEquals("SFLength counts the header and data, not the 0x5A",
                308, ((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF));
        assertEquals("position", bytes.length, writer.position());
    }

    @Test
    public void acceptsDataOfExactlyTheMaxFieldLength() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AfpWriter(out).write(FieldType.NOP, new byte[AfpWriter.MAX_DATA_LENGTH]).flush();

        StructuredField field = AfpReader.readAll(new ByteArrayInputStream(out.toByteArray()))
                .get(0);

        assertEquals("SFLength", AfpReader.MAX_LENGTH, field.length());
    }

    @Test
    public void rejectsDataLongerThanMaxFieldLength() {
        AfpWriter writer = new AfpWriter(new ByteArrayOutputStream());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> writer.write(FieldType.PTX, new byte[32_760]));

        assertThat(e.getMessage()).contains("PTX").contains("32760").contains("32759");
        assertEquals("nothing written", 0, writer.position());
    }

    @Test
    public void reservedBytesAreZero() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AfpWriter(out).begin(FieldType.BDT, "DOC").begin(FieldType.BPG, "P1")
                .end(FieldType.EPG).end(FieldType.EDT).flush();

        List<StructuredField> fields = AfpReader.readAll(new ByteArrayInputStream(out.toByteArray()));

        assertThat(fields).hasSize(4).allSatisfy(f -> assertEquals(f + " reserved", 0, f.reserved()));
    }

    @Test
    public void namesArePaddedWithEbcdicSpaces() {
        assertArrayEquals(new byte[] {(byte) 0xC4, (byte) 0xD6, (byte) 0xC3, 0x40, 0x40, 0x40,
                0x40, 0x40}, AfpWriter.name("DOC"));
    }

    @Test
    public void rejectsNamesThatDontFitTheToken() {
        assertThrows(IllegalArgumentException.class, () -> AfpWriter.name(""));
        assertThrows(IllegalArgumentException.class, () -> AfpWriter.name("NINECHARS"));
        assertThrows(IllegalArgumentException.class, () -> AfpWriter.name("P€1"));
    }

    @Test
    public void rejectsTypeIdsAndFlagsThatDontFit() {
        AfpWriter writer = new AfpWriter(new ByteArrayOutputStream());

        assertThrows(IllegalArgumentException.class,
                () -> writer.write(0x01D3A8A8, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(FieldType.NOP.id(), 0x100, new byte[0]));
    }
}
