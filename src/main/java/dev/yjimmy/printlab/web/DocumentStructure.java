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
 */
final class DocumentStructure {

    private DocumentStructure() {}

    /** One structured field: where it is, its type, and how big its data is. */
    record Field(long offset, int length, String type, String typeId, int flags, int dataLength) {}

    record AfpStructure(String format, List<Field> fields) {}

    /** One content-stream operator, at {@code offset} in the decoded content, with its operands. */
    record Operator(int offset, String operator, int operands) {}

    record Page(int number, List<Operator> operators) {}

    record PdfStructure(String format, List<Page> pages) {}

    static AfpStructure afp(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            List<Field> fields = new ArrayList<>();
            for (StructuredField f : AfpReader.readAll(in)) {
                fields.add(new Field(f.offset(), f.length(), f.typeName(),
                        String.format("%06X", f.typeId()), f.flags(), f.data().length));
            }
            return new AfpStructure("afp", fields);
        } catch (AfpFormatException e) {
            throw new ApiException(422, "the AFP file is corrupt: " + e.getMessage(), e.offset());
        }
    }

    static PdfStructure pdf(Path file) throws IOException {
        try {
            PdfDocumentReader pdf = PdfDocumentReader.open(file);
            List<Page> pages = new ArrayList<>();
            for (PdfPage page : pdf.pages()) {
                List<Operator> operators = new ArrayList<>();
                for (var op : ContentStreamParser.parse(pdf.content(page))) {
                    operators.add(new Operator(op.offset(), op.operator(), op.operands().size()));
                }
                pages.add(new Page(page.number(), operators));
            }
            return new PdfStructure("pdf", pages);
        } catch (PdfFormatException e) {
            throw new ApiException(422, "the PDF is corrupt: " + e.getMessage(), e.offset());
        } catch (UnsupportedFilterException | UnsupportedContentException e) {
            throw new ApiException(422,
                    "the PDF uses something the reader doesn't support: " + e.getMessage());
        }
    }
}
