package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/** Objects stored inside object streams ({@code /Type /ObjStm}, ISO 32000-1, 7.5.7). */
public class ObjectStreamTest {

    private static Map<Integer, String> objects(Object... numbersAndText) {
        Map<Integer, String> objects = new TreeMap<>();
        for (int i = 0; i < numbersAndText.length; i += 2) {
            objects.put((Integer) numbersAndText[i], (String) numbersAndText[i + 1]);
        }
        return objects;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void objectInsideObjectStreamResolves() {
        PdfBytes pdf = new PdfBytes("1.5")
                .objectStream(3, objects(
                        1, "<< /Type /Catalog /Pages 2 0 R >>",
                        2, "<< /Type /Pages /Kids [5 0 R] /Count 1 /MediaBox [0 0 612 792] >>",
                        4, "<< /Title (Stored compressed) >>"))
                .object(5, "<< /Type /Page /Parent 2 0 R >>");
        PdfDocumentReader reader = new PdfDocumentReader(pdf.buildWithXrefStream(6, "/Root 1 0 R /Info 4 0 R"));
        XrefReader xref = reader.xref();

        assertEquals("entry", new XrefReader.Compressed(4, 3, 2), xref.entry(4).orElseThrow());
        PdfDictionary info = (PdfDictionary) reader.resolve(reader.trailer().get("Info"));
        assertEquals("title", "Stored compressed", ((PdfString) info.get("Title")).text());
        assertEquals("its offset is the object stream's", pdf.offset(3),
                xref.indirectObject(new PdfReference(4, 0)).orElseThrow().offset());
        assertEquals("the catalog and page tree are compressed too", 1, reader.pages().size());
        assertEquals("inherited MediaBox", "[0 0 612 792]", reader.pages().get(0).mediaBox().orElseThrow().toString());
        assertEquals("generation other than 0", PdfNull.INSTANCE, xref.resolve(new PdfReference(4, 1)));

        StringBuilder dump = new StringBuilder();
        new PdfDump().dumpObject(reader, 4, dump);
        new PdfDump().dump(reader, dump);
        assertThat(dump.toString()).startsWith("4 0 obj  in object stream 3, index 2\n<<\n  /Title (Stored compressed)\n>>\n")
                .contains("Objects   6 in use (3 in object streams), 1 free; xref stream 6 0 R at offset");
    }

    @Test
    public void objectStreamWithBadFirstOffsetFailsWithMessage() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "/Type /ObjStm /N 1 /First 999", ascii("4 0\n42"))
                .inObjectStream(4, 3, 0);
        XrefReader xref = new XrefReader(pdf.buildWithXrefStream(5, "/Root 1 0 R"));

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(4, 0)));

        assertThat(e.getMessage()).contains("object stream 3 0 R has /First 999, past the end of its 6 decoded bytes");
        assertEquals("offset of the object stream", pdf.offset(3), e.offset());
    }

    @Test
    public void entryNamingTheWrongObjectOrIndexFails() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "/Type /ObjStm /N 1 /First 4", ascii("7 0\n42"))
                .inObjectStream(4, 3, 0)
                .inObjectStream(8, 3, 5);
        XrefReader xref = new XrefReader(pdf.buildWithXrefStream(9, "/Root 1 0 R"));

        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(4, 0))).getMessage())
                .contains("object stream 3 0 R holds object 7 at index 0, not object 4");
        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(8, 0))).getMessage())
                .contains("object 8 0 R in object stream 3 0 R is at index 5, but the stream holds 1 objects");
    }

    @Test
    public void objectStreamInsideAnObjectStreamFails() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .inObjectStream(3, 9, 0)
                .inObjectStream(4, 3, 0);
        XrefReader xref = new XrefReader(pdf.buildWithXrefStream(5, "/Root 1 0 R"));

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(4, 0)));

        assertThat(e.getMessage()).contains("object stream 3 is itself inside an object stream, which isn't allowed");
    }

    @Test
    public void entryPointingAtSomethingThatIsNotAnObjectStreamFails() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "/Type /XObject", ascii("4 0\n42"))
                .inObjectStream(4, 3, 0)
                .inObjectStream(6, 7, 0);
        XrefReader xref = new XrefReader(pdf.buildWithXrefStream(5, "/Root 1 0 R"));

        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(4, 0))).getMessage())
                .contains("object 3 0 R holds compressed objects, but is not an object stream (/Type /ObjStm)");
        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(6, 0))).getMessage())
                .contains("object stream 7 isn't in the file: its entry is null");
    }

    @Test
    public void badHeadersFailNamingTheStream() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "/Type /ObjStm /N 2 /First 4", ascii("4 0\n42 5 9"))
                .inObjectStream(4, 3, 0)
                .stream(6, "/Type /ObjStm /N 1 /First 8", ascii("4 9999\n42"))
                .inObjectStream(7, 6, 0)
                .stream(8, "/Type /ObjStm /N 1 /First 4", ascii("9 0\n<< /A"))
                .inObjectStream(9, 8, 0);
        XrefReader xref = new XrefReader(pdf.buildWithXrefStream(10, "/Root 1 0 R"));

        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(4, 0))).getMessage())
                .contains("object stream 3 0 R has a bad header").contains("the header runs past /First 4");
        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(7, 0))).getMessage())
                .contains("object stream 6 0 R has a bad header")
                .contains("offset 9999 is past the end of the decoded data");
        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(9, 0))).getMessage())
                .contains("object 9 0 R in object stream 8 0 R, at offset 4 of its decoded data");
    }

    @Test
    public void tableOrObjectStreamCountPastTheLimitFails() {
        PdfBytes table = new PdfBytes("1.4").object(1, "<< /Type /Catalog /Pages 2 0 R >>");
        int xref = table.size();
        table.raw("xref\n0 9000000\n");
        assertThat(assertThrows(PdfFormatException.class, () -> new XrefReader(table.raw("startxref\n" + xref
                + "\n%%EOF\n").bytes())).getMessage())
                .contains("a subsection of 9000000 entries is more than the " + XrefReader.MAX_OBJECTS);

        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "/Type /ObjStm /N 9000000 /First 4", ascii("4 0\n42"))
                .inObjectStream(4, 3, 0);
        XrefReader xrefReader = new XrefReader(pdf.buildWithXrefStream(5, "/Root 1 0 R"));
        assertThat(assertThrows(PdfFormatException.class, () -> xrefReader.resolve(new PdfReference(4, 0)))
                .getMessage()).contains("object stream 3 0 R has /N 9000000, more than the "
                        + XrefReader.MAX_OBJECTS + " objects a PDF may have");
    }

    @Test
    public void objectStreamThatCantBeDecodedFails() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "/Type /ObjStm /N 1 /First 4 /Filter /LZWDecode", ascii("4 0\n42"))
                .inObjectStream(4, 3, 0);
        XrefReader xref = new XrefReader(pdf.buildWithXrefStream(5, "/Root 1 0 R"));

        assertThat(assertThrows(PdfFormatException.class, () -> xref.resolve(new PdfReference(4, 0))).getMessage())
                .contains("object stream 3 0 R can't be decoded: /LZWDecode is not supported");
    }
}
