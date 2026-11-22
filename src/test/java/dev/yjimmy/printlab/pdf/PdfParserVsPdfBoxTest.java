package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Parses every object of a generated statement PDF at the offset PDFBox's cross-reference table
 * gives, and checks it against the object PDFBox read there. Until PR 7b's {@code XrefReader},
 * PDFBox supplies the offsets.
 */
public class PdfParserVsPdfBoxTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void everyObjectMatchesPdfBoxForSeveralSeeds() throws IOException {
        for (long seed : new long[] {1, 7, 42}) {
            int objects = compareAllObjects(seed, 3);

            assertThat(objects).as("objects compared, seed %d", seed).isGreaterThan(10);
        }
    }

    /** Returns how many objects were compared. */
    private int compareAllObjects(long seed, int statements) throws IOException {
        Path generated = tmp.newFile("run-" + seed + ".pdf").toPath();
        new StatementPdfGenerator(seed).generate(statements, generated);
        // Classic xref table and no object streams: the structure this PR reads (PR 8 does the rest).
        Path classic = tmp.newFile("classic-" + seed + ".pdf").toPath();
        try (PDDocument doc = Loader.loadPDF(generated.toFile())) {
            doc.save(classic.toFile(), CompressParameters.NO_COMPRESSION);
        }
        byte[] bytes = Files.readAllBytes(classic);

        try (PDDocument doc = Loader.loadPDF(classic.toFile())) {
            Map<COSObjectKey, Long> xref = doc.getDocument().getXrefTable();
            Map<PdfReference, Integer> offsets = new HashMap<>();
            xref.forEach((key, offset) -> offsets.put(
                    new PdfReference((int) key.getNumber(), key.getGeneration()), offset.intValue()));
            PdfParser parser = new PdfParser(bytes, ref -> {
                PdfParser lengthParser = new PdfParser(bytes);
                lengthParser.seek(offsets.get(ref));
                return lengthParser.parseIndirectObject().object();
            });

            int pages = 0;
            int streams = 0;
            for (Map.Entry<COSObjectKey, Long> entry : xref.entrySet()) {
                parser.seek(entry.getValue().intValue());
                PdfIndirectObject ours = parser.parseIndirectObject();
                COSObjectKey key = entry.getKey();
                assertEquals("object number at offset " + entry.getValue(),
                        new PdfReference((int) key.getNumber(), key.getGeneration()), ours.reference());
                COSObject theirs = doc.getDocument().getObjectFromPool(key);
                assertSame(key.toString(), theirs.getObject(), ours.object());
                if (ours.object() instanceof PdfDictionary d && PdfName.of("Page").equals(d.get("Type"))) {
                    pages++;
                }
                if (ours.object() instanceof PdfStream) {
                    streams++;
                }
            }
            assertEquals("pages, seed " + seed, doc.getNumberOfPages(), pages);
            assertThat(streams).as("streams, seed %d", seed).isGreaterThanOrEqualTo(pages);
            return xref.size();
        }
    }

    /** Fails unless {@code ours} is the same object as PDFBox's {@code theirs}, all the way down. */
    private static void assertSame(String where, COSBase theirs, PdfObject ours) throws IOException {
        switch (theirs) {
            case COSObject ref -> assertEquals(where, new PdfReference((int) ref.getObjectNumber(),
                    ref.getGenerationNumber()), ours);
            case COSStream stream -> {
                PdfStream ourStream = (PdfStream) ours;
                assertSameDictionary(where, stream, ourStream.dictionary());
                try (InputStream raw = stream.createRawInputStream()) {
                    assertThat(ourStream.data()).as(where + " stream data").isEqualTo(raw.readAllBytes());
                }
            }
            case COSDictionary dictionary -> assertSameDictionary(where, dictionary, (PdfDictionary) ours);
            case COSArray array -> {
                PdfArray ourArray = (PdfArray) ours;
                assertEquals(where + " array size", array.size(), ourArray.size());
                for (int i = 0; i < array.size(); i++) {
                    // get(i), not getObject(i): keep references as references.
                    assertSame(where + "[" + i + "]", array.get(i), ourArray.get(i));
                }
            }
            case COSName name -> assertEquals(where, PdfName.of(name.getName()), ours);
            case COSString string -> assertThat(((PdfString) ours).bytes()).as(where)
                    .isEqualTo(string.getBytes());
            case COSNumber number -> assertEquals(where, number.floatValue(),
                    ((PdfNumber) ours).doubleValue(), 1e-4);
            case COSBoolean bool -> assertEquals(where, PdfBoolean.of(bool.getValue()), ours);
            case COSNull ignored -> assertEquals(where, PdfNull.INSTANCE, ours);
            default -> throw new AssertionError(where + ": unexpected PDFBox type " + theirs.getClass());
        }
    }

    private static void assertSameDictionary(String where, COSDictionary theirs, PdfDictionary ours)
            throws IOException {
        assertThat(ours.entries().keySet()).as(where + " keys")
                .containsExactlyInAnyOrderElementsOf(theirs.keySet().stream().map(COSName::getName).toList());
        for (COSName key : theirs.keySet()) {
            assertSame(where + " /" + key.getName(), theirs.getItem(key), ours.get(key.getName()));
        }
    }
}
