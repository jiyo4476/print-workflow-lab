package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testsupport.ClassicPdf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reads generated statement PDFs with {@link PdfDocumentReader} and checks what it finds
 * against PDFBox: the cross-reference table, the pages, and their decoded content.
 */
public class PdfReaderVsPdfBoxTest {

    private static final long[] SEEDS = {1, 7, 42};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path generate(long seed) throws IOException {
        Path file = tmp.getRoot().toPath().resolve("classic-" + seed + ".pdf");
        ClassicPdf.write(seed, 3, file);
        return file;
    }

    @Test
    public void xrefOffsetsMatchPdfBox() throws IOException {
        for (long seed : SEEDS) {
            Path file = generate(seed);
            PdfDocumentReader ours = PdfDocumentReader.open(file);
            try (PDDocument theirs = Loader.loadPDF(file.toFile())) {
                Map<PdfReference, Integer> expected = new HashMap<>();
                theirs.getDocument().getXrefTable().forEach((key, offset) -> expected.put(
                        new PdfReference((int) key.getNumber(), key.getGeneration()), offset.intValue()));
                Map<PdfReference, Integer> actual = new HashMap<>();
                for (XrefReader.Entry entry : ours.xref().entries()) {
                    if (entry.inUse()) {
                        actual.put(new PdfReference(entry.objectNumber(), entry.generation()), entry.offset());
                    }
                }

                assertEquals("offsets, seed " + seed, expected, actual);
            }
        }
    }

    @Test
    public void pagesMediaBoxesAndFontNamesMatchPdfBox() throws IOException {
        for (long seed : SEEDS) {
            Path file = generate(seed);
            PdfDocumentReader ours = PdfDocumentReader.open(file);
            try (PDDocument theirs = Loader.loadPDF(file.toFile())) {
                List<PdfPage> pages = ours.pages();
                assertEquals("pages, seed " + seed, theirs.getNumberOfPages(), pages.size());
                for (int i = 0; i < pages.size(); i++) {
                    String where = "seed " + seed + " page " + (i + 1);
                    PDPage page = theirs.getPage(i);
                    PdfPage ourPage = pages.get(i);

                    PDRectangle box = page.getMediaBox();
                    assertThat(numbers(ourPage.mediaBox().orElseThrow())).as(where + " MediaBox")
                            .containsExactly((double) box.getLowerLeftX(), (double) box.getLowerLeftY(),
                                    (double) box.getUpperRightX(), (double) box.getUpperRightY());
                    assertEquals(where + " fonts", fontsPdfBox(page), fontsOurs(ours, ourPage));
                }
            }
        }
    }

    @Test
    public void decodedContentStreamsMatchPdfBoxByteForByte() throws IOException {
        for (long seed : SEEDS) {
            Path file = generate(seed);
            PdfDocumentReader ours = PdfDocumentReader.open(file);
            try (PDDocument theirs = Loader.loadPDF(file.toFile())) {
                int streams = 0;
                for (PdfPage page : ours.pages()) {
                    for (PdfIndirectObject content : ours.contentStreams(page)) {
                        PdfReference ref = content.reference();
                        COSStream stream = (COSStream) theirs.getDocument()
                                .getObjectFromPool(new COSObjectKey(ref.objectNumber(), ref.generation()))
                                .getObject();
                        byte[] expected;
                        try (InputStream in = new PDStream(stream).createInputStream()) {
                            expected = in.readAllBytes();
                        }
                        PdfStream ourStream = (PdfStream) content.object();

                        assertThat(ours.decode(ourStream)).as("seed %d, %s", seed, ref).isEqualTo(expected);
                        assertThat(ours.filters(ourStream)).as("filters").containsExactly("FlateDecode");
                        streams++;
                    }
                }
                assertEquals("one content stream per page, seed " + seed, theirs.getNumberOfPages(), streams);
            }
        }
    }

    private static List<Double> numbers(PdfArray array) {
        List<Double> values = new ArrayList<>();
        for (PdfObject item : array.items()) {
            values.add(((PdfNumber) item).doubleValue());
        }
        return values;
    }

    /** Resource name to base font, in PDFBox's order (sorted, to compare as a map). */
    private static Map<String, String> fontsPdfBox(PDPage page) throws IOException {
        Map<String, String> fonts = new HashMap<>();
        for (COSName name : page.getResources().getFontNames()) {
            PDFont font = page.getResources().getFont(name);
            fonts.put(name.getName(), font.getName());
        }
        return fonts;
    }

    private static Map<String, String> fontsOurs(PdfDocumentReader pdf, PdfPage page) {
        Map<String, String> fonts = new HashMap<>();
        PdfDictionary dictionary = (PdfDictionary) pdf.resolve(page.resources().get("Font"));
        dictionary.entries().forEach((name, font) -> fonts.put(name,
                ((PdfName) ((PdfDictionary) pdf.resolve(font)).get("BaseFont")).name()));
        return fonts;
    }
}
