package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testsupport.ClassicPdf;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PdfDocumentReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PAGES = "<< /Type /Pages /Kids [] /Count 0 >>";

    private static PdfDocumentReader reader(String header, String catalogExtra) {
        return new PdfDocumentReader(new PdfBytes(header)
                .object(1, "<< /Type /Catalog /Pages 2 0 R " + catalogExtra + " >>")
                .object(2, PAGES)
                .build("/Root 1 0 R"));
    }

    @Test
    public void versionFromHeader() {
        PdfDocumentReader pdf = reader("1.4", "");

        assertEquals("header", new PdfVersion(1, 4), pdf.headerVersion());
        assertEquals("version", new PdfVersion(1, 4), pdf.version());
        assertEquals("catalog", Optional.empty(), pdf.catalogVersion());
    }

    @Test
    public void catalogVersionOverridesAnEarlierHeader() {
        PdfDocumentReader pdf = reader("1.4", "/Version /1.7");

        assertEquals("version", new PdfVersion(1, 7), pdf.version());
        assertEquals("header", new PdfVersion(1, 4), pdf.headerVersion());
    }

    @Test
    public void anEarlierCatalogVersionDoesNotLowerTheHeader() {
        assertEquals("version", new PdfVersion(1, 7), reader("1.7", "/Version /1.3").version());
        assertEquals("unparsable /Version is ignored", new PdfVersion(1, 7),
                reader("1.7", "/Version /latest").version());
    }

    @Test
    public void aLaterVersionIsReadNotRejected() {
        PdfDocumentReader pdf = reader("2.1", "");

        assertEquals("version", new PdfVersion(2, 1), pdf.version());
        assertFalse("2.1 is not a version this reader knows", pdf.version().isKnown());
        assertThat(pdf.pages()).isEmpty();
    }

    @Test
    public void versionsCompareAndParse() {
        assertTrue("1.0 known", new PdfVersion(1, 0).isKnown());
        assertTrue("2.0 known", PdfVersion.LATEST_KNOWN.isKnown());
        assertFalse("0.9 unknown", new PdfVersion(0, 9).isKnown());
        assertThat(new PdfVersion(1, 10)).isGreaterThan(new PdfVersion(1, 9)).isLessThan(new PdfVersion(2, 0));
        assertEquals("parse", Optional.of(new PdfVersion(1, 7)), PdfVersion.parse("1.7"));
        assertEquals("not a version", Optional.empty(), PdfVersion.parse("1.x"));
        assertThrows(IllegalArgumentException.class, () -> new PdfVersion(-1, 0));
    }

    @Test
    public void headerMayFollowOtherBytesButMustBeThere() {
        byte[] junkFirst = new PdfBytes("junk from a mail gateway\n", "1.3")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>").object(2, PAGES).build("/Root 1 0 R");
        assertEquals("header after junk", new PdfVersion(1, 3), new PdfDocumentReader(junkFirst).version());

        byte[] notPdf = "hello, this is not a PDF".getBytes(StandardCharsets.US_ASCII);
        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new PdfDocumentReader(notPdf));
        assertThat(e.getMessage()).contains("no '%PDF-' header in the first 1024 bytes");

        byte[] badVersion = "%PDF-x\n".getBytes(StandardCharsets.US_ASCII);
        assertThat(assertThrows(PdfFormatException.class, () -> new PdfDocumentReader(badVersion))
                .getMessage()).contains("the header '%PDF-' has no version 'major.minor'");
    }

    @Test
    public void rootMustBeADictionary() {
        byte[] bytes = new PdfBytes("1.4").object(1, "[]").build("/Root 1 0 R");

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new PdfDocumentReader(bytes));

        assertThat(e.getMessage()).contains("the trailer's /Root must be the catalog dictionary, found []");
    }

    @Test
    public void pagesInheritMediaBoxAndResourcesFromTheirAncestors() {
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 3 /MediaBox [0 0 612 792]"
                        + " /Resources 9 0 R /Rotate 90 >>")
                .object(3, "<< /Type /Page /Parent 2 0 R >>")
                .object(4, "<< /Type /Pages /Parent 2 0 R /Kids [5 0 R] /Count 1 /MediaBox [0 0 595 842] >>")
                .object(5, "<< /Type /Page /Parent 4 0 R /Resources << /Font << /F9 10 0 R >> >> /Rotate 0 >>")
                .object(9, "<< /Font << /F1 10 0 R >> >>")
                .object(10, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
                .build("/Root 1 0 R"));

        List<PdfPage> pages = pdf.pages();

        assertThat(pages).extracting(PdfPage::number).containsExactly(1, 2);
        assertThat(pages).extracting(PdfPage::reference).containsExactly(new PdfReference(3, 0), new PdfReference(5, 0));
        PdfPage first = pages.get(0);
        assertEquals("page 1 MediaBox from the root", "[0 0 612 792]", first.mediaBox().orElseThrow().toString());
        assertThat(first.resources().get("Font").toString()).as("page 1 resources, resolved").isEqualTo("<</F1 10 0 R>>");
        assertEquals("page 1 Rotate", PdfNumber.of(90), first.dictionary().get("Rotate"));
        PdfPage second = pages.get(1);
        assertEquals("page 2 MediaBox from its parent", "[0 0 595 842]", second.mediaBox().orElseThrow().toString());
        assertThat(second.resources().get("Font").toString()).as("page 2's own resources").isEqualTo("<</F9 10 0 R>>");
        assertEquals("page 2's own Rotate", PdfNumber.of(0), second.dictionary().get("Rotate"));
        assertEquals("the page's own entries are kept", PdfName.of("Page"), second.dictionary().get("Type"));
    }

    @Test
    public void aPageWithoutMediaBoxOrResourcesHasNone() {
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Kids [3 0 R] /Count 1 >>") // no /Type: a node because it has /Kids
                .object(3, "<< /Type /Page >>")
                .build("/Root 1 0 R"));

        PdfPage page = pdf.pages().get(0);

        assertEquals("MediaBox", Optional.empty(), page.mediaBox());
        assertEquals("Resources", PdfDictionary.of(), page.resources());
        assertThat(pdf.contentStreams(page)).isEmpty();
    }

    @Test
    public void pageTreeLoopFails() {
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Pages /Kids [2 0 R] /Count 1 >>")
                .build("/Root 1 0 R"));

        PdfFormatException e = assertThrows(PdfFormatException.class, pdf::pages);

        assertThat(e.getMessage()).contains("object 2 0 R appears more than once in the page tree");
    }

    @Test
    public void badPageTreeNodesFail() {
        assertThat(assertThrows(PdfFormatException.class, () -> new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Count 0 >>")
                .build("/Root 1 0 R")).pages()).getMessage()).contains("page tree node 2 0 R has no /Kids array");
        assertThat(assertThrows(PdfFormatException.class, () -> new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [7] /Count 1 >>")
                .build("/Root 1 0 R")).pages()).getMessage()).contains("page tree node 7 is not a dictionary: 7");
    }

    @Test
    public void contentsMayBeOneStreamOrAnArray() {
        byte[] one = "BT ET".getBytes(StandardCharsets.US_ASCII);
        byte[] two = "q Q".getBytes(StandardCharsets.US_ASCII);
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>")
                .object(3, "<< /Type /Page /Contents 6 0 R >>")
                .object(4, "<< /Type /Page /Contents [6 0 R 7 0 R] >>")
                .object(5, "<< /Type /Page /Contents 8 0 R >>")
                .stream(6, "", one)
                .stream(7, "", two)
                .object(8, "[7 0 R]")
                .build("/Root 1 0 R"));
        List<PdfPage> pages = pdf.pages();

        assertThat(pdf.contentStreams(pages.get(0))).extracting(PdfIndirectObject::reference)
                .containsExactly(new PdfReference(6, 0));
        assertThat(pdf.contentStreams(pages.get(1))).extracting(PdfIndirectObject::reference)
                .containsExactly(new PdfReference(6, 0), new PdfReference(7, 0));
        assertThat(pdf.contentStreams(pages.get(2))).as("an indirect array").extracting(PdfIndirectObject::reference)
                .containsExactly(new PdfReference(7, 0));
        assertThat(pdf.decode((PdfStream) pdf.contentStreams(pages.get(1)).get(1).object())).isEqualTo(two);
    }

    @Test
    public void contentsThatAreNotStreamsFail() {
        PdfDocumentReader pdf = new PdfDocumentReader(new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                .object(3, "<< /Type /Page /Contents [4 0 R 9 0 R] >>")
                .object(4, "(not a stream)")
                .build("/Root 1 0 R"));
        PdfPage page = pdf.pages().get(0);

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> pdf.contentStreams(page));

        assertThat(e.getMessage()).contains("page 1's /Contents must be streams, found 4 0 R");
    }

    @Test
    public void encryptionIsDetected() {
        byte[] bytes = new PdfBytes("1.4").object(1, "<< /Type /Catalog /Pages 2 0 R >>").object(2, PAGES)
                .object(3, "<< /Filter /Standard /V 2 >>").build("/Root 1 0 R /Encrypt 3 0 R");

        assertTrue("encrypted", new PdfDocumentReader(bytes).isEncrypted());
        assertFalse("not encrypted", reader("1.4", "").isEncrypted());
    }

    /**
     * A PDF 2.0 file written by PDFBox, whose {@code /Info} title is a UTF-8 text string (new in
     * 2.0): the header and catalog say 2.0, and the pages and title read as usual.
     */
    @Test
    public void pdf20FileReads() throws IOException {
        String title = "Statements " + (char) 0x2013 + " Z" + (char) 0xFC + "rich";
        byte[] utf8 = title.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[utf8.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        Path file = tmp.getRoot().toPath().resolve("pdf20.pdf");
        ClassicPdf.write(7, 1, file, doc -> {
            doc.setVersion(2.0f); // the catalog's /Version
            doc.getDocument().setVersion(2.0f); // the header
            PDDocumentInformation info = new PDDocumentInformation();
            info.getCOSObject().setItem(COSName.TITLE, new COSString(withBom));
            doc.setDocumentInformation(info);
        });

        PdfDocumentReader pdf = PdfDocumentReader.open(file);

        assertEquals("version", PdfVersion.LATEST_KNOWN, pdf.version());
        assertEquals("header", PdfVersion.LATEST_KNOWN, pdf.headerVersion());
        PdfDictionary info = (PdfDictionary) pdf.resolve(pdf.trailer().get("Info"));
        assertEquals("UTF-8 title", title, ((PdfString) info.get("Title")).text());
        assertThat(pdf.pages()).hasSize(3);
        assertNull("no /Encrypt", pdf.trailer().get("Encrypt"));
    }
}
