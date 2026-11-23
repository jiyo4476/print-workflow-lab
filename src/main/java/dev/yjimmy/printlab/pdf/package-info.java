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
 *   <li>{@link dev.yjimmy.printlab.pdf.XrefReader} reads the cross-reference table and trailer,
 *       and reads objects through it when they're asked for.</li>
 *   <li>{@link dev.yjimmy.printlab.pdf.StreamDecoder} undoes a stream's Flate filter.</li>
 *   <li>{@link dev.yjimmy.printlab.pdf.PdfDocumentReader} gives the version, the catalog and the
 *       pages, with inherited attributes resolved; {@link dev.yjimmy.printlab.pdf.PdfDump}
 *       prints them for {@code App dump-pdf}.</li>
 * </ul>
 *
 * <p>{@code XrefReader} reads classic tables and cross-reference streams, objects packed in
 * object streams, and every revision of an incrementally updated file (PR 8). Next: content
 * streams (PR 9). PDFBox stays in the project as the test oracle: tests check this package
 * against what PDFBox reports for the same file.
 *
 * <p>The whole file is held in memory as a byte array, which keeps random access simple; a
 * production reader would map the file instead.
 */
package dev.yjimmy.printlab.pdf;
