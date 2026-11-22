package dev.yjimmy.printlab.pdf;

/**
 * A PDF object (ISO 32000-1, 7.3). PDF has eight basic types: booleans, numbers, strings, names,
 * arrays, dictionaries, streams and null. A {@link PdfReference} ({@code 12 0 R}) points at an
 * indirect object; the cross-reference table resolves it, not the parser.
 */
public sealed interface PdfObject
        permits PdfBoolean, PdfNumber, PdfString, PdfName, PdfArray, PdfDictionary, PdfStream,
                PdfNull, PdfReference {
}
