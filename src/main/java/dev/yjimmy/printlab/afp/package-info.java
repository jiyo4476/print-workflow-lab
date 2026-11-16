/**
 * AFP (MO:DCA) support.
 *
 * <p>An AFP file is a flat sequence of structured fields. Each starts with {@code 0x5A}, a 2-byte
 * length, and a 3-byte type ID ({@link dev.yjimmy.printlab.afp.StructuredField} has the byte
 * layout). Nesting comes from Begin/End pairs: a document (BDT ... EDT) holds named page groups
 * (BNG ... ENG), each holding pages (BPG ... EPG). In a transactional print run each page group is
 * one customer's statement, and the Tag Logical Elements (TLE) at the start of the group are its
 * index: {@code accountNumber}, {@code name}, {@code zip}. Text inside AFP is EBCDIC.
 *
 * <ul>
 *   <li>{@link dev.yjimmy.printlab.afp.AfpReader} streams the fields of a file;
 *       {@link dev.yjimmy.printlab.afp.FieldType} names the common IDs.</li>
 *   <li>{@link dev.yjimmy.printlab.afp.TleDecoder} decodes a TLE's name and value triplets;
 *       {@link dev.yjimmy.printlab.afp.Ebcdic} converts text (code page IBM037 by default).</li>
 *   <li>{@link dev.yjimmy.printlab.afp.AfpDump} prints the structure: {@code App dump-afp}.</li>
 * </ul>
 *
 * <p>Not handled: page content (PTOCA text, images, graphics), resources (fonts, overlays),
 * segmented fields and padding (the flags are kept but the data isn't joined or stripped), and
 * line data. Planned next: a writer for test data (PR 4) and a {@code SplitAfp} step that turns
 * page groups into documents and TLEs into document properties (PR 5).
 *
 * <p>Reference: <i>Mixed Object Document Content Architecture (MO:DCA) Reference</i>, published
 * free by the AFP Consortium (afpcinc.org).
 */
package dev.yjimmy.printlab.afp;
