package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.ClassicPdf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

    /** A generated file, and a label for messages: its seed and how it was saved. */
    private record Run(String label, Path file) {
    }

    /**
     * Each seed's statement run, twice: with a classic xref table, and as PDFBox saves by
     * default (a cross-reference stream and object streams, PDF 1.5).
     */
    private List<Run> runs() throws IOException {
        List<Run> runs = new ArrayList<>();
        for (long seed : SEEDS) {
            Path classic = tmp.getRoot().toPath().resolve("classic-" + seed + ".pdf");
            ClassicPdf.write(seed, 3, classic);
            runs.add(new Run(seed + " (classic)", classic));
            Path compressed = tmp.getRoot().toPath().resolve("compressed-" + seed + ".pdf");
            new StatementPdfGenerator(seed).generate(3, compressed);
            runs.add(new Run(seed + " (compressed)", compressed));
        }
        // A real PDF another tool wrote, when it's been downloaded (fixtures/README.md, "Local-only
        // samples"); it has no redistribution license, so it isn't checked in.
        Path sample = Path.of("samples", "afpworld", "01_Health_Coverage.pdf");
        if (Files.isRegularFile(sample)) {
            runs.add(new Run("AFPWorld sample", sample));
        }
        return runs;
    }

    @Test
    public void xrefOffsetsMatchPdfBox() throws IOException {
        for (Run run : runs()) {
            String seed = run.label();
            Path file = run.file();
            PdfDocumentReader ours = PdfDocumentReader.open(file);
            try (PDDocument theirs = Loader.loadPDF(file.toFile())) {
                Map<PdfReference, Integer> expected = new HashMap<>();
                theirs.getDocument().getXrefTable().forEach((key, offset) -> expected.put(
                        new PdfReference((int) key.getNumber(), key.getGeneration()), offset.intValue()));
                Map<PdfReference, Integer> actual = new HashMap<>();
                for (XrefReader.Entry entry : ours.xref().entries()) {
                    PdfReference ref = new PdfReference(entry.objectNumber(), entry.generation());
                    switch (entry) {
                        case XrefReader.InFile inFile -> actual.put(ref, inFile.offset());
                        // PDFBox records an object in an object stream as minus the stream's number.
                        case XrefReader.Compressed compressed -> actual.put(ref, -compressed.streamNumber());
                        case XrefReader.Free free -> { }
                    }
                }

                assertEquals("offsets, seed " + seed, expected, actual);
            }
        }
    }

    @Test
    public void pagesMediaBoxesAndFontNamesMatchPdfBox() throws IOException {
        for (Run run : runs()) {
            String seed = run.label();
            Path file = run.file();
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
        for (Run run : runs()) {
            String seed = run.label();
            Path file = run.file();
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

                        assertThat(ours.decode(ourStream)).as("seed %s, %s", seed, ref).isEqualTo(expected);
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

    /** Resource name to base font, as PDFBox reads them; a map, so the order doesn't matter. */
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
