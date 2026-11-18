package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class StructuredFieldTest {

    @Test
    public void equalFieldsCompareArrayContents() {
        StructuredField a = field(0, bytes(0xAA), bytes(0xC1, 0xC2));
        StructuredField b = field(0, bytes(0xAA), bytes(0xC1, 0xC2));

        assertEquals("equal", a, b);
        assertEquals("hashCode", a.hashCode(), b.hashCode());
    }

    @Test
    public void everyComponentTakesPartInEquals() {
        StructuredField base = field(0, bytes(0xAA), bytes(0xC1));

        assertNotEquals("offset", base, field(1, bytes(0xAA), bytes(0xC1)));
        assertNotEquals("length", base,
                new StructuredField(0, 99, FieldType.NOP.id(), 0x80, 1, bytes(0xAA), bytes(0xC1)));
        assertNotEquals("type", base,
                new StructuredField(0, 11, FieldType.TLE.id(), 0x80, 1, bytes(0xAA), bytes(0xC1)));
        assertNotEquals("flags", base,
                new StructuredField(0, 11, FieldType.NOP.id(), 0x00, 1, bytes(0xAA), bytes(0xC1)));
        assertNotEquals("sequence", base,
                new StructuredField(0, 11, FieldType.NOP.id(), 0x80, 2, bytes(0xAA), bytes(0xC1)));
        assertNotEquals("extension", base, field(0, bytes(0xBB), bytes(0xC1)));
        assertNotEquals("data", base, field(0, bytes(0xAA), bytes(0xC2)));
        assertNotEquals("other class", base, "NOP");
    }

    @Test
    public void arraysAreCopiedInAndOut() {
        byte[] data = bytes(0xC1);
        StructuredField field = field(0, new byte[0], data);

        data[0] = 0x00;
        field.data()[0] = 0x00;

        assertThat(field.data()).containsExactly(0xC1);
    }

    @Test
    public void unknownTypeHasNoEnumButAHexName() {
        StructuredField field = new StructuredField(0, 8, 0xD3EEFF, 0, 0, new byte[0], new byte[0]);

        assertThat(field.type()).isNull();
        assertEquals("typeName", "D3EEFF", field.typeName());
    }

    @Test
    public void toStringShowsTypeOffsetAndSizes() {
        assertEquals("toString", "NOP at 0x00000010 (length 11, 1 data bytes)",
                field(16, bytes(0xAA), bytes(0xC1)).toString());
    }

    private static StructuredField field(long offset, byte[] extension, byte[] data) {
        return new StructuredField(offset, 11, FieldType.NOP.id(), 0x80, 1, extension, data);
    }
}
