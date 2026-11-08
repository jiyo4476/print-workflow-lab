/**
 * AFP (MO:DCA) support — milestone 2, not built yet.
 *
 * <p>Plan:
 * <ol>
 *   <li>{@code StructuredField} record and a reader that walks a file record by record: the
 *       {@code 0x5A} introducer, a 2-byte length (which counts itself but not the 0x5A), a 3-byte
 *       type ID such as {@code D3A8A8} (Begin Document), a flags byte, 2 reserved/sequence bytes,
 *       then data.</li>
 *   <li>A dump tool that prints each field's name, to check files against a known AFP viewer.</li>
 *   <li>An {@code AfpWriter} for test data: pages wrapped in named page groups (BNG/ENG) with Tag
 *       Logical Elements (TLE) carrying accountNumber, name and zip — EBCDIC-encoded (IBM037).</li>
 *   <li>A {@code SplitAfp} step that splits on page groups and turns TLEs into document
 *       properties — no text-position guessing like the PDF path needs.</li>
 * </ol>
 * The MO:DCA reference is published free by the AFP Consortium (afpcinc.org).
 */
package dev.yjimmy.printlab.afp;
