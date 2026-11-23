package dev.yjimmy.printlab.pdf;

/**
 * {@code 12 0 obj ... endobj}: an object with a number, which other objects reach through a
 * {@link PdfReference}. {@code offset} is where its {@code 12} starts in the file: the offset the
 * cross-reference table records for it.
 */
public record PdfIndirectObject(PdfReference reference, PdfObject object, long offset) {
}
