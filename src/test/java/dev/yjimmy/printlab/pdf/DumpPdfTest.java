package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testsupport.ClassicPdf;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DumpPdfTest {

    private static final String FIXTURE = "/fixtures/pdf/two-statements.pdf";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = DumpPdfTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    private static String resourceText(String name) throws IOException {
        return new String(resource(name), StandardCharsets.UTF_8);
    }

    @Test
    public void twoStatementsFixtureMatchesGoldenDump() throws IOException {
        PdfDocumentReader pdf = new PdfDocumentReader(resource(FIXTURE));
        StringBuilder out = new StringBuilder();

        new PdfDump().dump(pdf, out);

        assertEquals("dump of " + FIXTURE, resourceText("/fixtures/pdf/two-statements.dump.txt"), out.toString());
    }

    @Test
    public void contentStreamObjectMatchesGoldenDump() throws IOException {
        PdfDocumentReader pdf = new PdfDocumentReader(resource(FIXTURE));
        StringBuilder out = new StringBuilder();

        boolean found = new PdfDump().dumpObject(pdf, 38, out);

        assertTrue("found", found);
        assertEquals("dump of object 38", resourceText("/fixtures/pdf/two-statements.object-38.dump.txt"),
                out.toString());
    }

    /** The fixture is what {@link ClassicPdf} writes for seed 7 and two statements. */
    @Test
    public void fixtureIsWhatClassicPdfWritesForSeed7() throws IOException {
        Path file = tmp.getRoot().toPath().resolve("two-statements.pdf");

        ClassicPdf.write(7, 2, file);

        assertArrayEquals("bytes of " + FIXTURE, resource(FIXTURE), Files.readAllBytes(file));
    }

    @Test
    public void pageDictionaryIsOneEntryPerLine() throws IOException {
        StringBuilder out = new StringBuilder();

        new PdfDump().dumpObject(new PdfDocumentReader(resource(FIXTURE)), 31, out);

        assertThat(out.toString()).startsWith("31 0 obj  at offset 0x000000AE (174)\n<<\n  /Type /Page\n")
                .endsWith("  /Contents 38 0 R\n>>\n");
    }

    @Test
    public void freeAndMissingObjectsAreReported() throws IOException {
        PdfDocumentReader pdf = new PdfDocumentReader(resource(FIXTURE));
        StringBuilder out = new StringBuilder();

        assertFalse("free", new PdfDump().dumpObject(pdf, 5, out));
        assertFalse("missing", new PdfDump().dumpObject(pdf, 999, out));

        assertEquals("output", "Object 5 is free\nObject 999 is not in the cross-reference table\n", out.toString());
    }

    private static PdfDocumentReader unusualFile() {
        byte[] binary = {0, 1, 2, (byte) 0xFF};
        return new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Version /1.7 /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
                .object(3, "<< /Type /Page /Contents [5 0 R 6 0 R 7 0 R] /Resources << /Font << /F1 8 0 R >> >> >>")
                .object(4, "<< /Type /Page >>")
                .stream(5, "/Filter /DCTDecode", binary)
                .stream(6, "/Filter /FlateDecode", binary)
                .stream(7, "", binary)
                .object(8, "<< /Type /Font /Subtype /Type3 >>")
                .object(9, "[1 (two) /Th#20ree]")
                .object(10, "<< >>")
                .stream(11, "/Filter 7", binary)
                .build("/Root 1 0 R /Encrypt 10 0 R"));
    }

    @Test
    public void streamsThatCantBeDecodedAreReportedAndTheDumpGoesOn() {
        StringBuilder out = new StringBuilder();

        new PdfDump().dump(unusualFile(), out);

        assertThat(out.toString())
                .contains("PDF       1.7 (header 1.4, raised by the catalog's /Version)\n")
                .contains("Encrypted yes: strings and streams are shown as stored, not decrypted\n")
                .contains("  Fonts     /F1\n")
                .contains("  Contents  5 0 R  /DCTDecode, 4 bytes, not decoded: /DCTDecode is not supported")
                .contains("  Contents  6 0 R  /FlateDecode, 4 bytes, can't decode: ")
                .contains("  Contents  7 0 R  4 bytes\n")
                .contains("Page 2    4 0 R\n  MediaBox  none\n  Fonts     none\n  Contents  none\n");
    }

    @Test
    public void objectsThatAreNotDictionariesOrTextArePrintedSafely() {
        PdfDocumentReader pdf = unusualFile();
        StringBuilder out = new StringBuilder();

        new PdfDump().dumpObject(pdf, 9, out);
        new PdfDump().dumpObject(pdf, 10, out);
        new PdfDump().dumpObject(pdf, 7, out);
        new PdfDump().dumpObject(pdf, 5, out);
        new PdfDump().dumpObject(pdf, 11, out);

        assertThat(out.toString())
                .contains("stream  4 bytes, can't decode: ")
                .contains("/Filter must be a name or an array of names, found 7")
                .contains("[1 (two) /Th#20ree]\n")
                .contains("<<>>\n")
                .contains("stream  4 bytes, binary, not shown\n")
                .contains("stream  /DCTDecode, 4 bytes, not decoded: /DCTDecode is not supported");
    }

    @Test
    public void laterVersionIsReportedNotRejected() {
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("2.1")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .build("/Root 1 0 R"));
        StringBuilder out = new StringBuilder();

        new PdfDump().dump(pdf, out);

        assertThat(out.toString()).startsWith(
                "PDF       2.1 (not a version this reader knows; read as PDF 2.0)\n");
    }

    @Test
    public void textStreamWithCrLfIsPrintedWithLineFeeds() {
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .stream(3, "", "q\r\nQ".getBytes(StandardCharsets.US_ASCII))
                .build("/Root 1 0 R"));
        StringBuilder out = new StringBuilder();

        new PdfDump().dumpObject(pdf, 3, out);

        assertThat(out.toString()).endsWith("stream  4 bytes:\nq\nQ\nendstream\n");
    }

    @Test
    public void textIsPrintableAsciiAndLineBreaks() {
        assertTrue("text", PdfDump.isText("BT\t(x) Tj\r\nET".getBytes(StandardCharsets.US_ASCII)));
        assertFalse("control character", PdfDump.isText(new byte[] {'a', 0x1B}));
        assertFalse("high byte", PdfDump.isText(new byte[] {(byte) 0xC3}));
    }
}
