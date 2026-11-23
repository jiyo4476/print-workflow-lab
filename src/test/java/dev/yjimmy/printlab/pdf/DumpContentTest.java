package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** {@code App dump-content}: a page's operators, indented and described. */
public class DumpContentTest {

    private static final String FIXTURE = "/fixtures/pdf/two-statements.pdf";

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = DumpContentTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    /** A one-page PDF whose page has {@code pageExtra} entries and the given content stream objects. */
    private static PdfDocumentReader page(String pageExtra, PdfBytes withObjects) {
        return new PdfDocumentReader(withObjects
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " + pageExtra + " >>")
                .build("/Root 1 0 R"));
    }

    private static PdfDocumentReader content(String content) {
        return page("/Contents 4 0 R", new PdfBytes("1.4")
                .stream(4, "", content.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String dump(PdfDocumentReader pdf, int pageNumber, boolean expected) {
        StringBuilder out = new StringBuilder();
        assertEquals("returned", expected, new PdfDump().dumpContent(pdf, pageNumber, out));
        return out.toString();
    }

    @Test
    public void twoStatementsPageOneMatchesGoldenDump() throws IOException {
        String golden = new String(resource("/fixtures/pdf/two-statements.content-1.dump.txt"), StandardCharsets.UTF_8);

        assertEquals("dump-content page 1", golden, dump(new PdfDocumentReader(resource(FIXTURE)), 1, true));
    }

    @Test
    public void nestingIsIndentedAndResourcesNamed() {
        PdfDocumentReader pdf = page("/Contents 4 0 R /Resources << /Font << /F1 5 0 R >>"
                        + " /XObject << /Im1 6 0 R /Fm1 7 0 R >> >>",
                new PdfBytes("1.4")
                        .stream(4, "", ("q 1 0 0 1 72 72 cm BT /F1 12 Tf (Hi) Tj ET"
                                + " /Im1 Do /Fm1 Do /Nope Do BI /W 2 /H 1 /BPC 8 /CS /G ID xy\nEI"
                                + " /P <</MCID 0>> BDC EMC Q").getBytes(StandardCharsets.ISO_8859_1))
                        .object(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
                        .stream(6, "/Type /XObject /Subtype /Image /Width 2 /Height 1", new byte[2])
                        .stream(7, "/Type /XObject /Subtype /Form /BBox [0 0 1 1]", new byte[0]));

        assertEquals("dump", """
                Page 1 of 1  3 0 R
                  Contents  4 0 R  128 bytes
                q                                       % save graphics state
                  1 0 0 1 72 72 cm                      % concatenate matrix to the CTM
                  BT                                    % begin text
                    /F1 12 Tf                           % font and size: Helvetica
                    (Hi) Tj                             % show text
                  ET                                    % end text
                  /Im1 Do                               % paint XObject: image 2 x 1
                  /Fm1 Do                               % paint XObject: form, its content not shown here
                  /Nope Do                              % paint XObject: not in the page's /XObject resources
                  BI <</W 2 /H 1 /BPC 8 /CS /G>> ID 2 bytes EI  % inline image: 2 x 1
                  /P <</MCID 0>> BDC                    % begin marked content with properties
                  EMC                                   % end marked content
                Q                                       % restore graphics state
                13 operations
                """, dump(pdf, 1, true));
    }

    @Test
    public void unmatchedAndUnknownOperatorsAreNoted() {
        String out = dump(content("Q ET EMC BX foo EX bar q BT BMC"), 1, true);

        assertThat(out).contains("Q                                       % Q without a q before it\n")
                .contains("ET                                      % ET without a BT before it\n")
                .contains("EMC                                     % EMC without a BMC or BDC before it\n")
                .contains("foo                                     % not a PDF operator; ignored inside BX/EX\n")
                .contains("bar                                     % not a PDF operator\n")
                .endsWith("10 operations\n% 1 BMC/BDC not closed by EMC\n% 1 BT not closed by ET\n"
                        + "% 1 q not closed by Q\n");
    }

    @Test
    public void imagesWithoutASizeSayItsUnknown() {
        PdfDocumentReader pdf = page("/Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> >>", new PdfBytes("1.4")
                .stream(4, "", "/Im1 Do BI /F /Fl ID xy EI".getBytes(StandardCharsets.US_ASCII))
                .stream(5, "/Type /XObject /Subtype /Image", new byte[0]));

        assertThat(dump(pdf, 1, true)).contains("% paint XObject: image size unknown\n")
                .contains("% inline image: size unknown\n");
    }

    @Test
    public void escapeBytesInAnOperatorDontReachTheOutput() {
        String out = dump(content("x" + (char) 0x1B + "[2J"), 1, false);

        assertThat(out).doesNotContain(String.valueOf((char) 0x1B));
        assertThat(dump(content("x" + (char) 0x1B + "y"), 1, true)).contains("x#1By ").doesNotContain(String.valueOf((char) 0x1B));
    }

    @Test
    public void unparseableContentStopsTheDumpWithANote() {
        String out = dump(content("q (open"), 1, false);

        assertThat(out).contains("q                                       % save graphics state\n")
                .endsWith("% stopped: At byte offset 0x00000002 (2): literal string is not closed with ')'\n");
    }

    @Test
    public void streamThatCantBeDecodedIsReported() {
        PdfDocumentReader pdf = page("/Contents 4 0 R", new PdfBytes("1.4").stream(4, "/Filter /LZWDecode", new byte[3]));

        assertThat(dump(pdf, 1, false)).isEqualTo("""
                Page 1 of 1  3 0 R
                  Contents  4 0 R  /LZWDecode, 3 bytes, not decoded: /LZWDecode is not supported; only /FlateDecode is decoded
                % the content can't be shown: a stream above can't be decoded
                """);
    }

    @Test
    public void contentArrayIsJoinedAndAnEmptyPageHasNoOperations() {
        PdfDocumentReader joined = page("/Contents [4 0 R 5 0 R]", new PdfBytes("1.4")
                .stream(4, "", "q 1 0 0".getBytes(StandardCharsets.US_ASCII))
                .stream(5, "", "1 0 0 cm Q".getBytes(StandardCharsets.US_ASCII)));
        assertThat(joined.content(joined.pages().get(0))).asString().isEqualTo("q 1 0 0\n1 0 0 cm Q");
        assertThat(dump(joined, 1, true)).contains("  1 0 0 1 0 0 cm").endsWith("3 operations\n");

        String empty = dump(page("", new PdfBytes("1.4")), 1, true);
        assertEquals("no content", "Page 1 of 1  3 0 R\n  Contents  none\n0 operations\n", empty);
    }

    @Test
    public void pageOutsideTheDocumentIsReported() throws IOException {
        PdfDocumentReader pdf = new PdfDocumentReader(resource(FIXTURE));

        assertEquals("past the end", "Page 7 is not in the document (it has 6 pages)\n", dump(pdf, 7, false));
        assertEquals("zero", "Page 0 is not in the document (it has 6 pages)\n", dump(pdf, 0, false));
        assertTrue("one page", dump(content(""), 2, false).endsWith("(it has 1 page)\n"));
        assertFalse("not found", new PdfDump().dumpContent(pdf, -1, new StringBuilder()));
    }
}
