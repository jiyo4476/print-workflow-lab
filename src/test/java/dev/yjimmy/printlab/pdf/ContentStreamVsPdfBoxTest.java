package dev.yjimmy.printlab.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import dev.yjimmy.printlab.testdata.StatementPdfGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link ContentStreamParser} against PDFBox's {@code PDFStreamParser}: the same operators with
 * the same operands, page by page. Each operation is compared as text, such as
 * {@code Tf[/F2 10]} or {@code BI[8 bytes]}, with strings as hex. Inline images are the
 * exception, below.
 */
public class ContentStreamVsPdfBoxTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Generated statement runs, saved in PDFBox's default (compressed) form. */
    @Test
    public void generatedStatementsParseAsPdfBoxParsesThem() throws IOException {
        for (long seed : new long[] {1, 7, 42}) {
            Path file = tmp.getRoot().toPath().resolve("run-" + seed + ".pdf");
            new StatementPdfGenerator(seed).generate(5, 1, file);

            assertSameOperations("seed " + seed, file);
        }
    }

    /**
     * Inline images PDFBox writes, one with binary data and one whose data is
     * {@code " EI 0 0 "}, are read by their size from the parameters. PDFBox isn't the oracle
     * here: 3.0.3's {@code PDFStreamParser} returns nothing after {@code BI} for either page
     * (its content is {@code q cm BI ... ID <8 bytes> EI Q BT ET}), so the expected operations
     * are written out.
     */
    @Test
    public void inlineImagesPdfBoxWritesAreReadByTheirSize() throws IOException {
        byte[][] images = {
            {0x00, 0x10, (byte) 0x80, (byte) 0xFF, 0x7F, 0x01, 0x02, 0x03},
            " EI 0 0 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        };
        for (byte[] data : images) {
            PdfDocumentReader ours = new PdfDocumentReader(Files.readAllBytes(inlineImagePdf(data)));

            List<Operation> operations = ContentStreamParser.parse(ours.content(ours.pages().get(0)));

            assertEquals("operations", List.of("q[]", "cm[4.0, 0.0, 0.0, 2.0, 10.0, 10.0]", "BI[8 bytes]", "Q[]",
                    "BT[]", "ET[]"), operations.stream().map(ContentStreamVsPdfBoxTest::ours).toList());
            assertThat(((PdfStream) operations.get(2).operands().get(0)).data()).containsExactly(data);
        }
    }

    /** A page with a 4 x 2 gray inline image holding {@code data}, then an empty text object. */
    private Path inlineImagePdf(byte[] data) throws IOException {
        Path file = tmp.newFile().toPath();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary parameters = new COSDictionary();
            parameters.setInt(COSName.W, 4);
            parameters.setInt(COSName.H, 2);
            parameters.setInt(COSName.BPC, 8);
            parameters.setItem(COSName.CS, COSName.getPDFName("G"));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(new PDInlineImage(parameters, data, page.getResources()), 10, 10);
                cs.beginText();
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static void assertSameOperations(String what, Path file) throws IOException {
        PdfDocumentReader ours = new PdfDocumentReader(Files.readAllBytes(file));
        try (PDDocument theirs = Loader.loadPDF(file.toFile())) {
            assertEquals(what + ": pages", theirs.getNumberOfPages(), ours.pages().size());
            for (int i = 0; i < ours.pages().size(); i++) {
                List<String> expected = pdfBox(theirs.getPage(i));
                List<String> actual = ContentStreamParser.parse(ours.content(ours.pages().get(i))).stream()
                        .map(ContentStreamVsPdfBoxTest::ours).toList();
                assertEquals(what + ", page " + (i + 1), expected, actual);
            }
        }
    }

    private static List<String> pdfBox(PDPage page) throws IOException {
        List<String> operations = new ArrayList<>();
        List<String> operands = new ArrayList<>();
        for (Object token : new PDFStreamParser(page).parse()) {
            if (token instanceof Operator operator) {
                operations.add(operator.getName().equals("BI")
                        ? "BI[" + operator.getImageData().length + " bytes]"
                        : operator.getName() + operands);
                operands.clear();
            } else {
                operands.add(cos((COSBase) token));
            }
        }
        return operations;
    }

    private static String cos(COSBase value) {
        if (value instanceof COSString s) {
            return "<" + HexFormat.of().formatHex(s.getBytes()) + ">";
        }
        if (value instanceof COSNumber n) {
            return String.valueOf(n.floatValue());
        }
        if (value instanceof COSName n) {
            return "/" + n.getName();
        }
        if (value instanceof COSBoolean b) {
            return String.valueOf(b.getValue());
        }
        if (value instanceof COSNull) {
            return "null";
        }
        if (value instanceof COSArray a) {
            List<String> items = new ArrayList<>();
            a.forEach(item -> items.add(cos(item)));
            return items.toString();
        }
        return value.getClass().getSimpleName();
    }

    private static String ours(Operation op) {
        if (op.isInlineImage()) {
            return "BI[" + ((PdfStream) op.operands().get(0)).length() + " bytes]";
        }
        return op.operator() + op.operands().stream().map(ContentStreamVsPdfBoxTest::ours)
                .collect(Collectors.toList());
    }

    private static String ours(PdfObject value) {
        return switch (value) {
            case PdfString s -> "<" + HexFormat.of().formatHex(s.bytes()) + ">";
            case PdfNumber n -> String.valueOf(n.value().floatValue());
            case PdfName n -> "/" + n.name();
            case PdfBoolean b -> String.valueOf(b.value());
            case PdfNull n -> "null";
            case PdfArray a -> a.items().stream().map(ContentStreamVsPdfBoxTest::ours).toList().toString();
            default -> value.getClass().getSimpleName();
        };
    }
}
