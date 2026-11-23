package dev.yjimmy.printlab.testdata;

import java.io.IOException;
import java.nio.file.Path;
import dev.yjimmy.printlab.testdata.StatementLayout.TextLine;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Generates a fake "statement print run": many customer statements concatenated into one PDF,
 * like the output of a billing system. Every value sits at a fixed position so the workflow can
 * find it. The statements come from {@link StatementDataGenerator}, so
 * {@link StatementAfpGenerator} writes the same ones as AFP for the same seed.
 * {@link StatementLayout} says where each piece of text goes.
 *
 * <p>The file is saved the way PDFBox saves by default, as most current tools do: a
 * cross-reference stream and object streams (PDF 1.5). {@link #document} gives the document
 * unsaved, for a test that saves it another way.
 */
public final class StatementPdfGenerator {

    static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();

    private final StatementDataGenerator data;

    public StatementPdfGenerator(long seed) {
        this.data = new StatementDataGenerator(seed);
    }

    public void generate(int statements, Path out) throws IOException {
        generate(statements, 0, out);
    }

    /**
     * @param badAddresses how many statements get a blank name or a 4-digit zip (alternately, in
     *     statement order), at most {@code statements}
     */
    public void generate(int statements, int badAddresses, Path out) throws IOException {
        write(data.generate(statements, badAddresses), out);
    }

    /** Writes {@code statements} as one PDF. */
    public static void write(List<Statement> statements, Path out) throws IOException {
        try (PDDocument doc = document(statements)) {
            doc.save(out.toFile());
        }
    }

    /** The run of {@code statements} for this seed, not yet saved; the caller closes it. */
    public PDDocument document(int statements) throws IOException {
        return document(data.generate(statements, 0));
    }

    /** {@code statements} as one document, not yet saved; the caller closes it. */
    public static PDDocument document(List<Statement> statements) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDDocument doc = new PDDocument();
        try {
            for (Statement statement : statements) {
                int pages = statement.pageCount();
                for (int p = 1; p <= pages; p++) {
                    PDPage page = new PDPage(PDRectangle.LETTER);
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        for (TextLine line : StatementLayout.lines(statement, p)) {
                            text(cs, line.bold() ? bold : font, line.size(), line.x(), line.y(),
                                    line.text());
                        }
                    }
                }
            }
            return doc;
        } catch (IOException | RuntimeException e) {
            doc.close();
            throw e;
        }
    }

    /** Draws text with its baseline at {@code yFromTop} points below the top of the page. */
    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x,
            float yFromTop, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, PAGE_HEIGHT - yFromTop);
        cs.showText(value);
        cs.endText();
    }
}
