/**
 * AFP (MO:DCA) support.
 *
 * <p>An AFP file is a flat sequence of MO:DCA structured fields, each preceded by a {@code 0x5A}
 * byte. A field is a 2-byte length, a 3-byte type ID, a flag byte, two reserved bytes, then data
 * ({@link dev.yjimmy.printlab.afp.StructuredField} has the byte layout). Nesting comes from Begin/End pairs: a document (BDT ... EDT) holds named page groups
 * (BNG ... ENG), each holding pages (BPG ... EPG). In a transactional print run each page group is
 * one customer's statement, and the Tag Logical Elements (TLE) at the start of the group are its
 * index: {@code accountNumber}, {@code name}, {@code zip}. Text inside AFP is EBCDIC.
 *
 * <ul>
 *   <li>{@link dev.yjimmy.printlab.afp.AfpReader} streams the fields of a file;
 *       {@link dev.yjimmy.printlab.afp.FieldType} names every structured field in the
 *       reference.</li>
 *   <li>{@link dev.yjimmy.printlab.afp.TleDecoder} decodes a TLE's name and value triplets,
 *       honouring code page (X'01') triplets; {@link dev.yjimmy.printlab.afp.Ebcdic} converts
 *       text (code page IBM037 by default).</li>
 *   <li>{@link dev.yjimmy.printlab.afp.AfpDump} prints the structure: {@code App dump-afp}.</li>
 * </ul>
 *
 * <p>Not handled: page content (PTOCA text, images, graphics), resources (fonts, overlays),
 * joining segmented fields (the reader returns each segment; the dump checks they're
 * consecutive), checking which fields each Begin/End envelope may contain, code page triplets on
 * Begin fields, and line data. Planned next: a writer for test data (PR 4) and a {@code SplitAfp} step that turns
 * page groups into documents and TLEs into document properties (PR 5).
 *
 * <p>Reference: <i>Mixed Object Document Content Architecture (MO:DCA) Reference</i>,
 * AFPC-0004-11 (August 2026), published free by the AFP Consortium (afpcinc.org).
 */
package dev.yjimmy.printlab.afp;
