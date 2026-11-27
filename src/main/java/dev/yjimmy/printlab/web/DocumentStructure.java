package dev.yjimmy.printlab.web;

import dev.yjimmy.printlab.afp.AfpFormatException;
import dev.yjimmy.printlab.afp.AfpReader;
import dev.yjimmy.printlab.afp.StructuredField;
import dev.yjimmy.printlab.pdf.ContentStreamParser;
import dev.yjimmy.printlab.pdf.PdfDocumentReader;
import dev.yjimmy.printlab.pdf.PdfFormatException;
import dev.yjimmy.printlab.pdf.PdfPage;
import dev.yjimmy.printlab.pdf.UnsupportedContentException;
import dev.yjimmy.printlab.pdf.UnsupportedFilterException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The structure endpoint's bodies (PRD-14 FR-6): an AFP file's structured fields, or a PDF's pages
 * with the operators of each page's content. A file that can't be read is a 422 with its byte
 * offset where the reader knows it.
 *
 * <p>A response holds at most {@link #MAX_ITEMS} fields or operators in all. Past that the body
 * says it was {@code truncated}, so a very large file can't exhaust the heap.
 */
final class DocumentStructure {

    /** The most fields, or operators across all pages, one response holds. */
    static final int MAX_ITEMS = 100_000;

    private DocumentStructure() {}

    /** One structured field: where it is, its type, and how big its data is. */
    record Field(long offset, int length, String type, String typeId, int flags, int dataLength) {}

    record AfpStructure(String format, List<Field> fields, boolean truncated) {}

    /** One content-stream operator, at {@code offset} in the decoded content, with its operands. */
    record Operator(int offset, String operator, int operands) {}

    record Page(int number, List<Operator> operators) {}

    record PdfStructure(String format, List<Page> pages, boolean truncated) {}

    static AfpStructure afp(Path file) throws IOException {
        return afp(file, MAX_ITEMS);
    }

    /** The AFP structure, with at most {@code maxItems} fields. */
    static AfpStructure afp(Path file, int maxItems) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            AfpReader reader = new AfpReader(in);
            List<Field> fields = new ArrayList<>();
            while (reader.hasNext()) {
                if (fields.size() == maxItems) {
                    return new AfpStructure("afp", fields, true);
                }
                StructuredField f = reader.next();
                fields.add(new Field(f.offset(), f.length(), f.typeName(),
                        String.format("%06X", f.typeId()), f.flags(), f.data().length));
            }
            return new AfpStructure("afp", fields, false);
        } catch (AfpFormatException e) {
            throw new ApiException(422, "the AFP file is corrupt: " + e.getMessage(), e.offset());
        }
    }

    static PdfStructure pdf(Path file) throws IOException {
        return pdf(file, MAX_ITEMS);
    }

    /** The PDF structure, with at most {@code maxItems} operators across its pages. */
    static PdfStructure pdf(Path file, int maxItems) throws IOException {
        try {
            PdfDocumentReader pdf = PdfDocumentReader.open(file);
            List<Page> pages = new ArrayList<>();
            int total = 0;
            for (PdfPage page : pdf.pages()) {
                List<Operator> operators = new ArrayList<>();
                ContentStreamParser parser = new ContentStreamParser(pdf.content(page));
                for (var op = parser.next(); op != null; op = parser.next()) {
                    if (total == maxItems) {
                        pages.add(new Page(page.number(), operators));
                        return new PdfStructure("pdf", pages, true);
                    }
                    operators.add(new Operator(op.offset(), op.operator(), op.operands().size()));
                    total++;
                }
                pages.add(new Page(page.number(), operators));
            }
            return new PdfStructure("pdf", pages, false);
        } catch (PdfFormatException e) {
            throw new ApiException(422, "the PDF is corrupt: " + e.getMessage(), e.offset());
        } catch (UnsupportedFilterException | UnsupportedContentException e) {
            throw new ApiException(422,
                    "the PDF uses something the reader doesn't support: " + e.getMessage());
        }
    }
}
