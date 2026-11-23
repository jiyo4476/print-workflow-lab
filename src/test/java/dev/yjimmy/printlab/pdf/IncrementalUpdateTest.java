package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.ClassicPdf;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Incremental updates (ISO 32000-1, 7.5.6): an edit appends changed objects and a new
 * cross-reference section whose {@code /Prev} points at the old one, leaving the old bytes in
 * the file.
 */
public class IncrementalUpdateTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A statement PDF titled "Original", then saved incrementally with the title "Edited". */
    private Path edited(boolean compressed) throws IOException {
        Path original = tmp.getRoot().toPath().resolve((compressed ? "compressed" : "classic") + ".pdf");
        if (compressed) {
            try (PDDocument doc = new StatementPdfGenerator(7).document(1)) {
                title(doc, "Original");
                doc.save(original.toFile());
            }
        } else {
            ClassicPdf.write(7, 1, original, doc -> title(doc, "Original"));
        }
        Path edited = tmp.getRoot().toPath().resolve((compressed ? "compressed" : "classic") + "-edited.pdf");
        try (PDDocument doc = Loader.loadPDF(original.toFile());
                OutputStream out = Files.newOutputStream(edited)) {
            doc.getDocumentInformation().setTitle("Edited");
            doc.getDocumentInformation().getCOSObject().setNeedToBeUpdated(true);
            // Writes the whole file: the original bytes unchanged, then the update.
            doc.saveIncremental(out);
        }
        return edited;
    }

    private static void title(PDDocument doc, String title) {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(title);
        doc.setDocumentInformation(info);
    }

    private static String title(PdfDocumentReader pdf) {
        PdfDictionary info = (PdfDictionary) pdf.resolve(pdf.trailer().get("Info"));
        return ((PdfString) pdf.resolve(info.get("Title"))).text();
    }

    @Test
    public void newestRevisionWins() throws IOException {
        for (boolean compressed : new boolean[] {false, true}) {
            PdfDocumentReader pdf = PdfDocumentReader.open(edited(compressed));

            assertEquals("revisions, compressed " + compressed, 2, pdf.revisions());
            assertEquals("seen by default, compressed " + compressed, 2, pdf.revision());
            assertEquals("title, compressed " + compressed, "Edited", title(pdf));
            assertEquals("pages, compressed " + compressed, 3, pdf.pages().size());
        }
    }

    @Test
    public void previousRevisionStillReadable() throws IOException {
        for (boolean compressed : new boolean[] {false, true}) {
            PdfDocumentReader pdf = PdfDocumentReader.open(edited(compressed));

            PdfDocumentReader before = pdf.revision(1);

            assertEquals("title, compressed " + compressed, "Original", title(before));
            assertEquals("pages, compressed " + compressed, 3, before.pages().size());
            assertSame("revision 2 is the reader itself", pdf, pdf.revision(2));
            assertTrue("the original section is a stream iff compressed",
                    before.xref().sections().get(0).isStream() == compressed);
        }
    }

    @Test
    public void xrefDumpOfAPdfBoxUpdateNamesTheChangedInfo() throws IOException {
        PdfDocumentReader pdf = PdfDocumentReader.open(edited(false));
        PdfReference info = (PdfReference) pdf.trailer().get("Info");
        StringBuilder out = new StringBuilder();

        new PdfDump().dumpXref(pdf, out);

        assertThat(out.toString()).contains("Revision 1  table at ").contains("Revision 2  table at ")
                .containsPattern("  Changed   ([0-9]+, )*" + info.objectNumber() + "(,|\n)");
    }

    /**
     * A hand-built file, so its bytes are the same every run: revision 2 changes the info
     * dictionary (3), adds object 6 and frees object 5.
     */
    private static PdfBytes twoRevisions() {
        PdfBytes pdf = new PdfBytes("1.4")
                .object(1, "<< /Type /Catalog /Pages 2 0 R >>")
                .object(2, "<< /Type /Pages /Kids [] /Count 0 >>")
                .object(3, "<< /Title (Original) >>")
                .object(4, "(unchanged)")
                .object(5, "(deleted later)");
        pdf.build("/Root 1 0 R /Info 3 0 R");
        pdf.object(3, "<< /Title (Edited) >>").object(6, "(added)").free(5);
        pdf.update("/Root 1 0 R /Info 3 0 R");
        return pdf;
    }

    @Test
    public void xrefDumpShowsTwoSectionsAndChangedObjects() throws IOException {
        PdfDocumentReader pdf = new PdfDocumentReader(twoRevisions().bytes());
        StringBuilder out = new StringBuilder();

        new PdfDump().dumpXref(pdf, out);

        try (InputStream golden = getClass().getResourceAsStream("/fixtures/pdf/two-revisions.xref.dump.txt")) {
            assertEquals("dump-pdf --xref", new String(golden.readAllBytes(), StandardCharsets.UTF_8), out.toString());
        }
    }

    @Test
    public void objectFreedInAnUpdateResolvesToNullButIsInTheEarlierRevision() {
        PdfDocumentReader pdf = new PdfDocumentReader(twoRevisions().bytes());

        assertEquals("now", PdfNull.INSTANCE, pdf.resolve(new PdfReference(5, 0)));
        assertEquals("before", PdfString.of("deleted later"), pdf.revision(1).resolve(new PdfReference(5, 0)));
        assertEquals("added", PdfString.of("added"), pdf.resolve(new PdfReference(6, 0)));
        assertEquals("not yet added", PdfNull.INSTANCE, pdf.revision(1).resolve(new PdfReference(6, 0)));
        assertEquals("unchanged, from the first section", PdfString.of("unchanged"), pdf.resolve(new PdfReference(4, 0)));
    }

    @Test
    public void dumpSaysHowManyRevisionsThereAre() {
        PdfDocumentReader pdf = new PdfDocumentReader(twoRevisions().bytes());
        StringBuilder now = new StringBuilder();
        StringBuilder before = new StringBuilder();

        new PdfDump().dump(pdf, now);
        new PdfDump().dump(pdf.revision(1), before);

        assertThat(now.toString()).contains("Revisions 2, showing 2; dump-pdf --xref lists what each update changed\n")
                .contains("<</Size 7 /Prev ");
        assertThat(before.toString()).contains("Revisions 2, showing 1;").doesNotContain("/Prev");
    }

    @Test
    public void prevLoopFails() {
        PdfBytes pdf = new PdfBytes("1.4").object(1, "<< /Type /Catalog /Pages 2 0 R >>");
        int xref = pdf.size();
        byte[] bytes = pdf.build("/Root 1 0 R /Prev " + xref);

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new PdfDocumentReader(bytes));

        assertThat(e.getMessage()).contains("the /Prev chain loops back to the section at offset " + xref);
    }

    @Test
    public void revisionOutOfRangeFails() {
        PdfDocumentReader pdf = new PdfDocumentReader(twoRevisions().bytes());

        assertThat(assertThrows(IllegalArgumentException.class, () -> pdf.revision(3)).getMessage())
                .isEqualTo("revision 3 doesn't exist: the file has 2");
        assertThrows(IllegalArgumentException.class, () -> pdf.revision(0));
    }
}
