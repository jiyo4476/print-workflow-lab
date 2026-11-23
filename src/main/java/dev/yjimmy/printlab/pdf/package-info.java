/**
 * Reading PDF files directly, without PDFBox (ISO 32000-1, section 7: syntax).
 *
 * <p>A PDF is a header ({@code %PDF-1.7}), a body of numbered <i>indirect objects</i>
 * ({@code 12 0 obj ... endobj}), a cross-reference table giving each object's byte offset, and a
 * trailer pointing at the document catalog. Objects are the eight basic types of
 * {@link dev.yjimmy.printlab.pdf.PdfObject} (dictionaries, arrays, names, strings, numbers,
 * booleans, null and streams) plus references ({@code 12 0 R}) between them. Unlike AFP, which
 * is read front to back as a flat sequence of structured fields, a PDF is read from the end: find
 * the cross-reference table, then jump to the objects you need.
 *
 * <ul>
 *   <li>{@link dev.yjimmy.printlab.pdf.PdfLexer} splits bytes into tokens, decoding name and
 *       string escapes.</li>
 *   <li>{@link dev.yjimmy.printlab.pdf.PdfParser} builds objects from tokens: direct objects,
 *       and indirect objects with their stream data.</li>
 * </ul>
 *
 * <p>Planned next (PR 7b): the cross-reference table and trailer, Flate stream decoding, the
 * page tree, and {@code App dump-pdf}. Then xref and object streams (PR 8) and content streams
 * (PR 9). PDFBox stays in the project as the test oracle: tests check this package against what
 * PDFBox reports for the same file.
 *
 * <p>The whole file is held in memory as a byte array, which keeps random access simple; a
 * production reader would map the file instead.
 */
package dev.yjimmy.printlab.pdf;
