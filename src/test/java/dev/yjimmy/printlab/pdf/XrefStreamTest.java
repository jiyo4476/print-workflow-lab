package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import dev.yjimmy.printlab.testsupport.PdfBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Cross-reference streams ({@code /Type /XRef}, ISO 32000-1, 7.5.8), including hybrid files. */
public class XrefStreamTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String CATALOG = "<< /Type /Catalog /Pages 2 0 R >>";
    private static final String PAGES = "<< /Type /Pages /Kids [] /Count 0 >>";

    /**
     * The same document saved as PDFBox does by default (a cross-reference stream and object
     * streams) and with {@code NO_COMPRESSION} (a classic table). The object numbers differ, so
     * the two are compared from the catalog down, following references.
     */
    @Test
    public void compressedAndUncompressedSavesResolveTheSameObjects() throws IOException {
        Path compressed = tmp.getRoot().toPath().resolve("compressed.pdf");
        Path classic = tmp.getRoot().toPath().resolve("classic.pdf");
        try (PDDocument doc = new StatementPdfGenerator(7).document(2)) {
            doc.save(compressed.toFile());
        }
        try (PDDocument doc = new StatementPdfGenerator(7).document(2)) {
            doc.save(classic.toFile(), CompressParameters.NO_COMPRESSION);
        }
        PdfDocumentReader packed = PdfDocumentReader.open(compressed);
        PdfDocumentReader plain = PdfDocumentReader.open(classic);

        assertTrue("compressed: an xref stream", packed.xref().sections().get(0).isStream());
        assertThat(packed.xref().entries()).as("compressed: objects in object streams")
                .anyMatch(e -> e instanceof XrefReader.Compressed);
        assertFalse("classic: a table", plain.xref().sections().get(0).isStream());
        assertThat(plain.xref().entries()).noneMatch(e -> e instanceof XrefReader.Compressed);
        assertEquals("pages", plain.pages().size(), packed.pages().size());
        assertEquals("the catalog, all the way down", canonical(plain, plain.catalog(), new HashSet<>()),
                canonical(packed, packed.catalog(), new HashSet<>()));
        assertThat(Files.size(compressed)).as("compressed is smaller").isLessThan(Files.size(classic));
    }

    /**
     * {@code object} as text with references replaced by what they point to, so files numbered
     * differently compare equal. {@code /Parent} (which points back up) and {@code /Version}
     * (which PDFBox sets per save) are left out, and a stream is its dictionary without
     * {@code /Length} plus its decoded data.
     */
    private static String canonical(PdfDocumentReader pdf, PdfObject object, Set<PdfReference> path) {
        return switch (object) {
            case PdfReference ref -> {
                if (!path.add(ref)) {
                    yield "(loop)";
                }
                String text = canonical(pdf, pdf.resolve(ref), path);
                path.remove(ref);
                yield text;
            }
            case PdfDictionary dictionary -> {
                Map<String, String> sorted = new TreeMap<>();
                dictionary.entries().forEach((key, value) -> {
                    if (!key.equals("Parent") && !key.equals("Version")) {
                        sorted.put(key, canonical(pdf, value, path));
                    }
                });
                yield sorted.toString();
            }
            case PdfArray array -> array.items().stream().map(item -> canonical(pdf, item, path)).toList().toString();
            case PdfStream stream -> {
                Map<String, PdfObject> entries = new TreeMap<>(stream.dictionary().entries());
                entries.remove("Length");
                yield canonical(pdf, new PdfDictionary(entries), path) + " stream "
                        + HexFormat.of().formatHex(pdf.decode(stream));
            }
            default -> object.toString();
        };
    }

    /** {@code /Index [1 2 7 1]} lists objects 1, 2 and 7; {@code /W [0 4 2]} means every row is type 1. */
    @Test
    public void indexRangesAndAMissingTypeField() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG).object(2, PAGES);
        int xref = pdf.size();
        byte[] rows = new byte[18];
        row(rows, 0, pdf.offset(1), 0);
        row(rows, 6, pdf.offset(2), 0);
        row(rows, 12, xref, 0);
        pdf.stream(7, "/Type /XRef /Size 8 /W [0 4 2] /Index [1 2 7 1] /Root 1 0 R", rows)
                .raw("startxref\n" + xref + "\n%%EOF\n");

        XrefReader reader = new XrefReader(pdf.bytes());

        assertThat(reader.entries()).extracting(XrefReader.Entry::objectNumber).containsExactly(1, 2, 7);
        assertEquals("object 2", new XrefReader.InFile(2, pdf.offset(2), 0), reader.entry(2).orElseThrow());
        assertEquals("catalog", PdfName.of("Catalog"), ((PdfDictionary) reader.resolve(new PdfReference(1, 0))).get("Type"));
        assertEquals("the stream's dictionary is the trailer", new PdfReference(1, 0), reader.trailer().get("Root"));
        assertEquals("section", "stream 7 0 R", reader.sections().get(0).kind());
    }

    /** Rows of {@code /W [0 4 2]}: an offset, then a generation. */
    private static void row(byte[] rows, int at, int offset, int generation) {
        byte[] seven = new byte[7];
        PdfBytes.row(seven, 0, 0, offset, generation);
        System.arraycopy(seven, 1, rows, at, 6);
    }

    /** As writers store them: Flate with the PNG Up predictor, rows of {@code /W [1 2 1]}. */
    @Test
    public void flateStreamWithAPngPredictorReads() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG).object(2, PAGES);
        int xref = pdf.size();
        int[][] entries = {{0, 0, 255}, {1, pdf.offset(1), 0}, {1, pdf.offset(2), 0}, {1, xref, 0}};
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int[] previous = new int[4];
        for (int[] entry : entries) {
            int[] row = {entry[0], entry[1] >>> 8 & 0xFF, entry[1] & 0xFF, entry[2]};
            encoded.write(2); // Up
            for (int i = 0; i < 4; i++) {
                encoded.write(row[i] - previous[i]);
            }
            previous = row;
        }
        byte[] compressed = deflate(encoded.toByteArray());
        pdf.stream(3, "/Type /XRef /Size 4 /W [1 2 1] /Root 1 0 R /Filter /FlateDecode"
                + " /DecodeParms << /Predictor 12 /Columns 4 >>", compressed)
                .raw("startxref\n" + xref + "\n%%EOF\n");

        XrefReader reader = new XrefReader(pdf.bytes());

        assertEquals("object 2", new XrefReader.InFile(2, pdf.offset(2), 0), reader.entry(2).orElseThrow());
        assertEquals("object 0", new XrefReader.Free(0, 255), reader.entry(0).orElseThrow());
        assertEquals("pages", PdfName.of("Pages"), ((PdfDictionary) reader.resolve(new PdfReference(2, 0))).get("Type"));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /**
     * A hybrid file (7.5.8.4): a classic table a PDF 1.4 reader can use, whose {@code /XRefStm}
     * points at a stream listing the objects stored in object streams.
     */
    @Test
    public void hybridFileReadsObjectsFromBothTheTableAndTheStream() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG).object(2, PAGES)
                .objectStream(4, Map.of(5, "(hidden from 1.4 readers)"));
        int stream = pdf.size();
        byte[] rows = new byte[7];
        PdfBytes.row(rows, 0, 2, 4, 0);
        pdf.stream(6, "/Type /XRef /Size 7 /W [1 4 2] /Index [5 1]", rows);
        int xref = pdf.size();
        String table = "xref\n0 5\n" + PdfBytes.entry(0, 65535, 'f') + PdfBytes.entry(pdf.offset(1), 0, 'n')
                + PdfBytes.entry(pdf.offset(2), 0, 'n') + PdfBytes.entry(0, 0, 'f')
                + PdfBytes.entry(pdf.offset(4), 0, 'n') + "6 1\n" + PdfBytes.entry(pdf.offset(6), 0, 'n')
                + "trailer\n<< /Size 7 /Root 1 0 R /XRefStm " + stream + " >>\nstartxref\n" + xref + "\n%%EOF\n";
        pdf.raw(table);

        XrefReader reader = new XrefReader(pdf.bytes());

        assertEquals("from the stream", "hidden from 1.4 readers", ((PdfString) reader.resolve(new PdfReference(5, 0))).text());
        assertEquals("from the table", PdfName.of("Catalog"), ((PdfDictionary) reader.resolve(new PdfReference(1, 0))).get("Type"));
        assertEquals("one section", 1, reader.revisions());
        assertEquals("kind", String.format("table + stream at 0x%08X", stream), reader.sections().get(0).kind());
    }

    @Test
    public void badStreamDictionariesFailNamingTheStream() {
        assertThat(error("/Size 3 /W [1 2]")).contains("cross-reference stream 3 0 R needs /W, an array of three widths");
        assertThat(error("/Size 3 /W [1 9 1]")).contains("has a /W field of 9 bytes; at most 8 are read");
        assertThat(error("/Size 3 /W [0 0 0]")).contains("has /W [0 0 0]: rows of no bytes");
        assertThat(error("/Size 3 /W [1 2 1] /Index [0]")).contains("needs /Index, pairs of 'first count'");
        assertThat(error("/W [1 2 1]")).contains("cross-reference stream 3 0 R /Size must be a non-negative integer");
        assertThat(error("/Size 3 /W [1 2 1] /Filter /LZWDecode")).contains("can't be decoded: /LZWDecode");
    }

    /** The message for a cross-reference stream, object 3, with these dictionary entries. */
    private static String error(String entries) {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG).object(2, PAGES);
        int xref = pdf.size();
        pdf.stream(3, "/Type /XRef " + entries, new byte[12]).raw("startxref\n" + xref + "\n%%EOF\n");
        return assertThrows(PdfFormatException.class, () -> new XrefReader(pdf.bytes())).getMessage();
    }

    /**
     * 20 MB of 1-byte rows compresses to about 20 KB, and would be 20 million entries: more than
     * a PDF may have (Annex C), so it fails instead of filling memory (NFR-3).
     */
    @Test
    public void moreEntriesThanAPdfMayHaveFail() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG);
        int xref = pdf.size();
        int rows = 20_000_000;
        pdf.stream(3, "/Type /XRef /Size " + rows + " /W [1 0 0] /Filter /FlateDecode", deflate(new byte[rows]))
                .raw("startxref\n" + xref + "\n%%EOF\n");

        PdfFormatException e = assertThrows(PdfFormatException.class, () -> new XrefReader(pdf.bytes()));

        assertThat(e.getMessage()).contains("cross-reference stream 3 0 R lists 20000000 objects, more than the "
                + XrefReader.MAX_OBJECTS + " a PDF may have");
    }

    @Test
    public void unknownEntryTypesAreFreeAndHugeValuesFail() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG);
        int xref = pdf.size();
        byte[] rows = new byte[21];
        PdfBytes.row(rows, 0, 0, 0, 65535);
        PdfBytes.row(rows, 7, 7, 1234, 0); // type 7: a reference to the null object
        PdfBytes.row(rows, 14, 1, pdf.offset(1), 0);
        pdf.stream(3, "/Type /XRef /Size 3 /W [1 4 2] /Root 2 0 R", rows).raw("startxref\n" + xref + "\n%%EOF\n");

        XrefReader reader = new XrefReader(pdf.bytes());

        assertEquals("type 7", new XrefReader.Free(1, 0), reader.entry(1).orElseThrow());
        assertEquals("resolves to null", PdfNull.INSTANCE, reader.resolve(new PdfReference(1, 0)));

        PdfBytes big = new PdfBytes("1.5").object(1, CATALOG);
        int bigXref = big.size();
        byte[] huge = new byte[7];
        PdfBytes.row(huge, 0, 1, -1, 0); // offset 0xFFFFFFFF
        big.stream(3, "/Type /XRef /Size 1 /W [1 4 2]", huge).raw("startxref\n" + bigXref + "\n%%EOF\n");
        assertThat(assertThrows(PdfFormatException.class, () -> new XrefReader(big.bytes())).getMessage())
                .contains("has a value too large for object 0's entry: 4294967295");
    }

    @Test
    public void builtXrefStreamMatchesTheEntriesWritten() {
        PdfBytes pdf = new PdfBytes("1.5").object(1, CATALOG).object(2, PAGES)
                .objectStream(3, Map.of(4, "(four)"));
        byte[] bytes = pdf.buildWithXrefStream(5, "/Root 1 0 R");

        XrefReader reader = new XrefReader(bytes);

        assertThat(reader.entries()).containsExactly(new XrefReader.Free(0, 65535),
                new XrefReader.InFile(1, pdf.offset(1), 0), new XrefReader.InFile(2, pdf.offset(2), 0),
                new XrefReader.InFile(3, pdf.offset(3), 0), new XrefReader.Compressed(4, 3, 0),
                new XrefReader.InFile(5, pdf.offset(5), 0));
        assertEquals("four", "four", new String(((PdfString) reader.resolve(new PdfReference(4, 0))).bytes(),
                StandardCharsets.US_ASCII));
    }
}
