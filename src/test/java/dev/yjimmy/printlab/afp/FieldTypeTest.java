package dev.yjimmy.printlab.afp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FieldTypeTest {

    @Test
    public void knownIdsMapToNames() {
        assertEquals("BDT", "BDT", FieldType.nameOf(0xD3A8A8));
        assertEquals("ENG", "ENG", FieldType.nameOf(0xD3A9AD));
        assertEquals("TLE", "TLE", FieldType.nameOf(0xD3A090));
        assertEquals("PTX", "PTX", FieldType.nameOf(0xD3EE9B));
        assertEquals("description", "Begin Named Page Group", FieldType.BNG.description());
        assertThat(FieldType.forId(0xD3A6AF)).contains(FieldType.PGD);
    }

    @Test
    public void listsEveryStructuredFieldInTheReference() {
        // AFPC-0004-11, Appendix E, "MO:DCA Structured Fields Sorted by Identifier": 98 fields.
        assertEquals("count", 98, FieldType.values().length);
        assertEquals("print file", "Begin Print File", FieldType.BPF.description());
        assertEquals("print file end", FieldType.EPF, FieldType.forId(0xD3A9A5).orElseThrow());
        assertEquals("data", "OCD", FieldType.nameOf(0xD3EE92));
    }

    @Test
    public void format1FieldsUseTheReferenceAcronym() {
        assertEquals("PTD-1", "PTD-1", FieldType.nameOf(0xD3A69B));
        assertEquals("PTD", "PTD", FieldType.nameOf(0xD3B19B));
        assertEquals("MCF-1", "MCF-1", FieldType.MCF1.acronym());
        assertThat(FieldType.PGP1.description()).contains("Format-1").contains("coexistence");
    }

    @Test
    public void idsAreInTheReferenceOrder() {
        FieldType[] types = FieldType.values();
        for (int i = 1; i < types.length; i++) {
            assertTrue(types[i] + " after " + types[i - 1], types[i].id() > types[i - 1].id());
        }
    }

    @Test
    public void unknownIdRendersAsHex() {
        assertEquals("unknown", "D3EEFF", FieldType.nameOf(0xD3EEFF));
        assertEquals("leading zeros kept", "00A001", FieldType.nameOf(0x00A001));
        assertThat(FieldType.forId(0xD3EEFF)).isEmpty();
    }

    @Test
    public void everyBeginTypeHasItsEndType() {
        for (FieldType type : FieldType.values()) {
            StructuredField field = new StructuredField(0, 8, type.id(), 0, 0, new byte[0],
                    new byte[0]);
            if (field.isBegin()) {
                assertTrue(type + " has an End type",
                        FieldType.forId(field.matchingEndId()).isPresent());
            }
        }
    }

    @Test
    public void everyIdIsInTheModcaClass() {
        for (FieldType type : FieldType.values()) {
            assertEquals(type + " class byte", 0xD3, type.id() >>> 16);
        }
    }

    @Test
    public void beginAndEndAreRecognizedByTypeByte() {
        StructuredField bng = field(FieldType.BNG);
        StructuredField eng = field(FieldType.ENG);
        StructuredField tle = field(FieldType.TLE);

        assertTrue("BNG is a Begin", bng.isBegin());
        assertTrue("ENG is an End", eng.isEnd());
        assertFalse("TLE is neither", tle.isBegin() || tle.isEnd());
        assertEquals("BNG closes with ENG", FieldType.ENG.id(), bng.matchingEndId());
        assertThrows(IllegalStateException.class, tle::matchingEndId);
    }

    private static StructuredField field(FieldType type) {
        return new StructuredField(0, 8, type.id(), 0, 0, new byte[0], new byte[0]);
    }
}
