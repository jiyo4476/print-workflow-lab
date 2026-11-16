package dev.yjimmy.printlab.afp;

import static dev.yjimmy.printlab.testsupport.AfpBytes.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.AfpBytes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Test;

public class AfpReaderTest {

    @Test
    public void readsSingleField() {
        // 5A | length 000B | ID D3EEEE (NOP) | flags 00 | sequence 0007 | data C1 C2 F1
        byte[] afp = bytes(0x5A, 0x00, 0x0B, 0xD3, 0xEE, 0xEE, 0x00, 0x00, 0x07, 0xC1, 0xC2, 0xF1);

        List<StructuredField> fields = read(afp);

        assertEquals("field count", 1, fields.size());
        StructuredField nop = fields.get(0);
        assertEquals("offset", 0, nop.offset());
        assertEquals("length", 11, nop.length());
        assertEquals("type ID", 0xD3EEEE, nop.typeId());
        assertEquals("type", FieldType.NOP, nop.type());
        assertEquals("flags", 0, nop.flags());
        assertEquals("sequence", 7, nop.sequence());
        assertThat(nop.data()).containsExactly(0xC1, 0xC2, 0xF1);
        assertThat(nop.extension()).isEmpty();
    }

    @Test
    public void readsMinimalDocument() {
        byte[] afp = AfpBytes.create()
                .begin(FieldType.BDT, "DOC1")
                .begin(FieldType.BPG, "PAGE1")
                .end(FieldType.EPG)
                .end(FieldType.EDT)
                .bytes();

        List<StructuredField> fields = read(afp);

        assertThat(fields).extracting(StructuredField::type)
                .containsExactly(FieldType.BDT, FieldType.BPG, FieldType.EPG, FieldType.EDT);
        // Begin fields are 9 + 8 name bytes; End fields are 9 bytes.
        assertThat(fields).extracting(StructuredField::offset).containsExactly(0L, 17L, 34L, 43L);
        assertEquals("BDT name", "DOC1    ", Ebcdic.decode(fields.get(0).data()));
    }

    @Test
    public void lengthCountsItselfButNotIntroducer() {
        // Length 0x000A = 8 header bytes + 2 data bytes. If the reader counted the 0x5A it would
        // read one data byte, then fail on 0x02 as the next introducer.
        byte[] afp = AfpBytes.create()
                .raw(0x5A, 0x00, 0x0A, 0xD3, 0xEE, 0xEE, 0x00, 0x00, 0x00, 0x01, 0x02)
                .end(FieldType.EDT)
                .bytes();

        List<StructuredField> fields = read(afp);

        assertThat(fields.get(0).data()).containsExactly(0x01, 0x02);
        assertEquals("next field offset", 11, fields.get(1).offset());
        assertEquals("next field", FieldType.EDT, fields.get(1).type());
    }

    @Test
    public void zeroLengthDataIsAllowed() {
        List<StructuredField> fields = read(AfpBytes.create().end(FieldType.EPG).bytes());

        assertEquals("length", 8, fields.get(0).length());
        assertThat(fields.get(0).data()).isEmpty();
    }

    @Test
    public void missingIntroducerFailsWithOffset() {
        // A valid 9-byte field, then 0x40 (an EBCDIC space) where the next 0x5A should be.
        byte[] afp = AfpBytes.create().end(FieldType.EPG).raw(0x40, 0x00).bytes();
        AfpReader reader = new AfpReader(new ByteArrayInputStream(afp));
        reader.next();

        AfpFormatException e = assertThrows(AfpFormatException.class, reader::next);

        assertEquals("offset", 9, e.offset());
        assertThat(e.getMessage()).contains("0x00000009 (9)").contains("found 0x40");
        assertFalse("no fields after an error", reader.hasNext());
    }

    @Test
    public void truncatedFieldFailsWithOffset() {
        // Says 8 + 10 data bytes, but only 3 data bytes follow.
        byte[] afp = AfpBytes.create()
                .end(FieldType.EPG)
                .raw(0x5A, 0x00, 0x12, 0xD3, 0xEE, 0xEE, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03)
                .bytes();

        AfpFormatException e = assertThrows(AfpFormatException.class, () -> read(afp));

        assertEquals("offset of the truncated field", 9, e.offset());
        assertThat(e.getMessage()).contains("truncated").contains("19 bytes")
                .contains("ends after 12");
    }

    @Test
    public void truncatedHeaderFailsWithOffset() {
        AfpFormatException e = assertThrows(AfpFormatException.class,
                () -> read(bytes(0x5A, 0x00, 0x08, 0xD3)));

        assertEquals("offset", 0, e.offset());
        assertThat(e.getMessage()).contains("header");
    }

    @Test
    public void lengthShorterThanHeaderFails() {
        AfpFormatException e = assertThrows(AfpFormatException.class,
                () -> read(bytes(0x5A, 0x00, 0x07, 0xD3, 0xEE, 0xEE, 0x00, 0x00, 0x00)));

        assertThat(e.getMessage()).contains("length 7");
    }

    @Test
    public void emptyFileYieldsNoFields() {
        AfpReader reader = new AfpReader(new ByteArrayInputStream(new byte[0]));

        assertFalse("hasNext", reader.hasNext());
        assertThrows(NoSuchElementException.class, reader::next);
    }

    @Test
    public void extensionIsSeparatedFromData() {
        // Flags 0x80: the data starts with an extension whose first byte (3) counts itself.
        byte[] afp = AfpBytes.create()
                .field(FieldType.NOP.id(), StructuredField.FLAG_EXTENSION, 0,
                        bytes(0x03, 0xAA, 0xBB, 0xC1))
                .bytes();

        StructuredField field = read(afp).get(0);

        assertThat(field.extension()).containsExactly(0xAA, 0xBB);
        assertThat(field.data()).containsExactly(0xC1);
    }

    @Test
    public void extensionLongerThanFieldFails() {
        byte[] afp = AfpBytes.create()
                .field(FieldType.NOP.id(), StructuredField.FLAG_EXTENSION, 0, bytes(0x09, 0x00))
                .bytes();

        AfpFormatException e = assertThrows(AfpFormatException.class, () -> read(afp));

        assertThat(e.getMessage()).contains("extension");
    }

    @Test
    public void extensionFlagWithNoDataFails() {
        byte[] afp = AfpBytes.create()
                .field(FieldType.NOP.id(), StructuredField.FLAG_EXTENSION, 0)
                .bytes();

        AfpFormatException e = assertThrows(AfpFormatException.class, () -> read(afp));

        assertThat(e.getMessage()).contains("extension length (0)");
    }

    @Test
    public void extensionLengthZeroFails() {
        // The extension length counts itself, so 0 can't be right (and would loop if trusted).
        byte[] afp = AfpBytes.create()
                .field(FieldType.NOP.id(), StructuredField.FLAG_EXTENSION, 0, bytes(0x00, 0xC1))
                .bytes();

        AfpFormatException e = assertThrows(AfpFormatException.class, () -> read(afp));

        assertThat(e.getMessage()).contains("extension length (0)");
    }

    @Test
    public void extensionOfJustItsLengthByteIsEmpty() {
        byte[] afp = AfpBytes.create()
                .field(FieldType.NOP.id(), StructuredField.FLAG_EXTENSION, 0, bytes(0x01, 0xC1))
                .bytes();

        StructuredField field = read(afp).get(0);

        assertThat(field.extension()).isEmpty();
        assertThat(field.data()).containsExactly(0xC1);
    }

    @Test
    public void readsOneFieldAtATime() {
        // Garbage follows the first field. A reader that parsed ahead (to build a list, say)
        // would throw before returning anything; a streaming one returns the first field.
        byte[] afp = AfpBytes.create().end(FieldType.EPG).raw(0xFF, 0xFF, 0xFF).bytes();
        AfpReader reader = new AfpReader(new ByteArrayInputStream(afp));

        assertEquals("first field", FieldType.EPG, reader.next().type());
        assertThrows("the garbage, once asked for", AfpFormatException.class, reader::next);
    }

    @Test
    public void ioErrorsAreUnchecked() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk gone");
            }
        };

        assertThrows(UncheckedIOException.class, () -> new AfpReader(broken).hasNext());
    }

    @Test
    public void ioErrorPartWayThroughAFieldIsUnchecked() {
        // The introducer reads fine; the stream breaks while the header is being read.
        InputStream breaksAfterIntroducer = new InputStream() {
            private boolean introducerSent;

            @Override
            public int read() throws IOException {
                if (!introducerSent) {
                    introducerSent = true;
                    return 0x5A;
                }
                throw new IOException("disk gone");
            }
        };
        AfpReader reader = new AfpReader(breaksAfterIntroducer);

        assertThrows(UncheckedIOException.class, reader::hasNext);
        assertFalse("no fields after an error", reader.hasNext());
    }

    @Test
    public void closeClosesTheStream() throws IOException {
        boolean[] closed = {false};
        InputStream in = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };

        try (AfpReader reader = new AfpReader(in)) {
            assertFalse("hasNext", reader.hasNext());
        }

        assertThat(closed[0]).as("stream closed").isTrue();
    }

    private static List<StructuredField> read(byte[] afp) {
        return AfpReader.readAll(new ByteArrayInputStream(afp));
    }
}
