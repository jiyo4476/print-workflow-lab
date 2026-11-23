package dev.yjimmy.printlab.pdf;

import static dev.yjimmy.printlab.testsupport.PdfBytes.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class XrefReaderTest {

    private static final String CATALOG = "<< /Type /Catalog /Pages 2 0 R >>";
    private static final String PAGES = "<< /Type /Pages /Kids [] /Count 0 >>";

    private static byte[] concat(byte[] body, String tail) {
        byte[] end = tail.getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[body.length + end.length];
        System.arraycopy(body, 0, all, 0, body.length);
        System.arraycopy(end, 0, all, body.length, end.length);
        return all;
    }

    @Test
    public void findsStartxrefWithTrailingWhitespaceVariants() {
        for (String eol : List.of("\n", "\r\n", "\r")) {
            for (boolean finalNewline : new boolean[] {true, false}) {
                PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES);
                int xref = pdf.size();
                String tail = "xref" + eol + "0 3" + eol + entry(0, 65535, 'f') + entry(pdf.offset(1), 0, 'n')
                        + entry(pdf.offset(2), 0, 'n') + "trailer" + eol + "<< /Size 3 /Root 1 0 R >>" + eol
                        + "startxref" + eol + xref + eol + "%%EOF" + (finalNewline ? eol : "");

                XrefReader reader = new XrefReader(concat(pdf.bytes(), tail));

                String variant = "eol " + eol.replace("\r", "CR").replace("\n", "LF") + ", final newline "
                        + finalNewline;
                assertEquals(variant, xref, reader.startxref());
                assertEquals(variant, new PdfReference(1, 0), reader.trailer().get("Root"));
                assertEquals(variant, PdfName.of("Catalog"),
                        ((PdfDictionary) reader.resolve(new PdfReference(1, 0))).get("Type"));
            }
        }
    }

    @Test
    public void theLastStartxrefInTheFileIsUsed() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, "(startxref 999999 inside a string)").object(2, PAGES);
        int xref = pdf.size();
        byte[] bytes = pdf.build("/Root 1 0 R");

        assertEquals("startxref", xref, new XrefReader(bytes).startxref());
    }

    @Test
    public void parsesMultipleSubsections() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES).object(5, "(five)");
        int xref = pdf.size();
        String tail = "xref\n0 3\n" + entry(0, 65535, 'f') + entry(pdf.offset(1), 0, 'n')
                + entry(pdf.offset(2), 0, 'n') + "5 1\n" + entry(pdf.offset(5), 0, 'n')
                + "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n";

        XrefReader reader = new XrefReader(concat(pdf.bytes(), tail));

        assertEquals("object 5", PdfString.of("five"), reader.resolve(new PdfReference(5, 0)));
        assertEquals("object 3 is in no subsection", PdfNull.INSTANCE, reader.resolve(new PdfReference(3, 0)));
        assertThat(reader.entries()).extracting(XrefReader.Entry::objectNumber).containsExactly(0, 1, 2, 5);
        assertEquals("entry 5", new XrefReader.Entry(5, pdf.offset(5), 0, true), reader.entry(5).orElseThrow());
    }

    @Test
    public void freeEntriesResolveToNull() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES).object(3, "(deleted)");
        int xref = pdf.size();
        String tail = "xref\n0 4\n" + entry(0, 65535, 'f') + entry(pdf.offset(1), 0, 'n')
                + entry(pdf.offset(2), 0, 'n') + entry(0, 1, 'f')
                + "trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n";

        XrefReader reader = new XrefReader(concat(pdf.bytes(), tail));

        assertEquals("free object 3", PdfNull.INSTANCE, reader.resolve(new PdfReference(3, 0)));
        assertEquals("object 0 is always free", PdfNull.INSTANCE, reader.resolve(new PdfReference(0, 0)));
        assertEquals("wrong generation", PdfNull.INSTANCE, reader.resolve(new PdfReference(1, 1)));
        assertEquals("beyond the table", PdfNull.INSTANCE, reader.resolve(new PdfReference(99, 0)));
        assertThat(reader.indirectObject(new PdfReference(3, 0))).isEmpty();
    }

    @Test
    public void offsetPointingAtWrongObjectFailsWithMessage() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES);
        int xref = pdf.size();
        String tail = "xref\n0 3\n" + entry(0, 65535, 'f') + entry(pdf.offset(1), 0, 'n')
                + entry(pdf.offset(1), 0, 'n') // object 2's entry points at object 1
                + "trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n";
        XrefReader reader = new XrefReader(concat(pdf.bytes(), tail));

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> reader.resolve(new PdfReference(2, 0)));

        assertThat(e.getMessage()).contains("the xref entry for object 2 0 R points at offset "
                + pdf.offset(1) + ", but object 1 0 R is there");
        assertEquals("offset", pdf.offset(1), e.offset());
    }

    @Test
    public void offsetInTheMiddleOfAnObjectFailsNamingTheObject() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES);
        int xref = pdf.size();
        String tail = "xref\n0 3\n" + entry(0, 65535, 'f') + entry(pdf.offset(1), 0, 'n')
                + entry(pdf.offset(2) + 2, 0, 'n') + "trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n";
        XrefReader reader = new XrefReader(concat(pdf.bytes(), tail));

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> reader.resolve(new PdfReference(2, 0)));

        assertThat(e.getMessage()).contains("reading object 2 0 R at offset " + (pdf.offset(2) + 2)
                + " from the xref table: expected 'n g obj'");
    }

    @Test
    public void offsetPastTheEndOfTheFileFails() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG);
        int xref = pdf.size();
        String tail = "xref\n0 3\n" + entry(0, 65535, 'f') + entry(pdf.offset(1), 0, 'n')
                + entry(999_999, 0, 'n') + "trailer\n<< /Size 3 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n";
        XrefReader reader = new XrefReader(concat(pdf.bytes(), tail));

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> reader.resolve(new PdfReference(2, 0)));

        assertThat(e.getMessage()).contains("object 2 0 R has offset 999999, past the end of the file");
    }

    @Test
    public void objectsAreReadOnlyWhenResolvedAndThenKept() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES).object(3, "<< /Broken ");
        XrefReader reader = new XrefReader(pdf.build("/Root 1 0 R"));

        PdfObject first = reader.resolve(new PdfReference(2, 0));

        assertSame("the same object the second time", first, reader.resolve(new PdfReference(2, 0)));
        assertThrows("broken object 3 fails only when it's read", PdfFormatException.class,
                () -> reader.resolve(new PdfReference(3, 0)));
    }

    @Test
    public void streamWithIndirectLengthResolvesThroughTheTable() {
        byte[] data = "BT /F1 12 Tf ET".getBytes(StandardCharsets.ISO_8859_1);
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES)
                .object(3, "<< /Length 4 0 R >>\nstream\n" + new String(data, StandardCharsets.ISO_8859_1)
                        + "\nendstream")
                .object(4, String.valueOf(data.length));
        XrefReader reader = new XrefReader(pdf.build("/Root 1 0 R"));

        PdfStream stream = (PdfStream) reader.resolve(new PdfReference(3, 0));

        assertThat(stream.data()).isEqualTo(data);
    }

    @Test
    public void streamWhoseLengthIsItselfFailsCleanly() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG).object(2, PAGES)
                .object(3, "<< /Length 3 0 R >>\nstream\nabc\nendstream");
        XrefReader reader = new XrefReader(pdf.build("/Root 1 0 R"));

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> reader.resolve(new PdfReference(3, 0)));

        assertThat(e.getMessage()).contains("its stream /Length refers back to the object itself");
    }

    @Test
    public void noStartxrefFails() {
        byte[] bytes = new PdfBytes("1.4").object(1, CATALOG).bytes();

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new XrefReader(bytes));

        assertThat(e.getMessage()).contains("no 'startxref' found");
        assertEquals("offset", bytes.length, e.offset());
    }

    @Test
    public void startxrefWithoutANumberOrPastTheEndFails() {
        byte[] body = new PdfBytes("1.4").object(1, CATALOG).bytes();

        assertThat(assertThrows(PdfFormatException.class,
                () -> new XrefReader(concat(body, "startxref\n%%EOF\n"))).getMessage())
                .contains("expected the cross-reference table's offset after 'startxref'");
        assertThat(assertThrows(PdfFormatException.class,
                () -> new XrefReader(concat(body, "startxref\n99999\n%%EOF\n"))).getMessage())
                .contains("startxref 99999 is past the end of the file");
    }

    @Test
    public void crossReferenceStreamIsReportedAsPr8() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG);
        int xref = pdf.size();
        pdf.object(2, "<< /Type /XRef /Size 3 /W [1 2 1] /Length 0 >>\nstream\n\nendstream");

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> new XrefReader(concat(pdf.bytes(), "startxref\n" + xref + "\n%%EOF\n")));

        assertThat(e.getMessage()).contains("probably a cross-reference stream (PDF 1.5), which PR 8 reads");
    }

    @Test
    public void startxrefAtSomethingElseFails() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG);
        int dictionary = pdf.offset(1) + "1 0 obj\n".length();

        PdfFormatException e = assertThrows(PdfFormatException.class,
                () -> new XrefReader(concat(pdf.bytes(), "startxref\n" + dictionary + "\n%%EOF\n")));

        assertThat(e.getMessage()).contains("startxref points at '<<', not an 'xref' table")
                .doesNotContain("PR 8");
        assertEquals("offset", dictionary, e.offset());
    }

    @Test
    public void incrementalUpdateIsReportedAsPr8() {
        byte[] bytes = new PdfBytes("1.4").object(1, CATALOG).build("/Root 1 0 R /Prev 9");

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new XrefReader(bytes));

        assertThat(e.getMessage()).contains("the trailer has /Prev").contains("PR 8");
    }

    @Test
    public void malformedEntriesFail() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG);
        int xref = pdf.size();
        String start = "xref\n0 2\n" + entry(0, 65535, 'f');

        assertThat(assertThrows(PdfFormatException.class, () -> new XrefReader(concat(pdf.bytes(),
                start + "0000000017 00000 x\r\ntrailer\n<<>>\nstartxref\n" + xref + "\n%%EOF\n")))
                .getMessage()).contains("expected 'n' or 'f' ending object 1's entry, found 'x'");
        assertThat(assertThrows(PdfFormatException.class, () -> new XrefReader(concat(pdf.bytes(),
                start + "17.5 00000 n\r\ntrailer\n<<>>\nstartxref\n" + xref + "\n%%EOF\n")))
                .getMessage()).contains("expected object 1's entry 'offset generation n|f', found '17.5'");
        assertThat(assertThrows(PdfFormatException.class, () -> new XrefReader(concat(pdf.bytes(),
                "xref\n/Oops\nstartxref\n" + xref + "\n%%EOF\n")))
                .getMessage()).contains("expected a subsection's first object number, or 'trailer'");
    }

    @Test
    public void trailerMustBeADictionary() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, CATALOG);
        int xref = pdf.size();

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new XrefReader(concat(pdf.bytes(),
                "xref\n0 1\n" + entry(0, 65535, 'f') + "trailer\n[1 2]\nstartxref\n" + xref + "\n%%EOF\n")));

        assertThat(e.getMessage()).contains("'trailer' must be followed by a dictionary, found [1 2]");
    }
}
