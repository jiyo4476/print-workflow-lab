# PRD-03 — AFP structured-field reader + dump tool

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | M | 00 | `feature/03-afp-reader` |

## Summary

Read any AFP (MO:DCA) file as a sequence of structured fields, decode the EBCDIC index tags
(TLEs) inside them, and print the whole structure with `App dump-afp <file>`.

## Why

AFP is the native format of high-volume transactional print, and enterprise print workflow
systems are built around it. Reading the format byte by byte is the fastest way to understand
it, and the dump tool becomes the everyday debugging aid for every later AFP PR. In an
interview, "I wrote a structured-field parser and can show you a file's page groups and TLEs" is
concrete evidence of AFP knowledge.

## Users and scenarios

- **Developer** runs `dump-afp statement.afp` and sees BDT → BNG → TLE (`accountNumber =
  1234567890`) → BPG … EPG → ENG … EDT, indented by nesting.
- **Operator / support** dumps a file a customer says "won't split" and spots a missing ENG or
  a TLE with no value.

## Goals

- `StructuredField` record and an `AfpReader` iterator over an `InputStream`.
- `FieldType` names for the common IDs; unknown IDs shown as hex.
- `Ebcdic` helper (code page IBM037) and a TLE decoder.
- `dump-afp` CLI with nesting indentation and inline TLE name/value pairs.

## Non-goals

- Writing AFP (PRD-04), splitting (PRD-05).
- Decoding page content: PTOCA text, IOCA images, GOCA graphics.
- Resource resolution (fonts, overlays) and IPDS.
- Line data and mixed-mode input (MO:DCA only).

## Requirements

**Functional**
- **FR-1** Each field: offset, length, 3-byte type ID, flags, the 2 reserved bytes, extension,
  data. *(Revised: was "sequence number"; MO:DCA reserves bytes 6-7.)*
- **FR-2** The reader expects `0x5A`, then a 2-byte big-endian length (8-32,767) that **counts
  itself but not the `0x5A`**, then ID (3), flags (1), reserved (2), and `length - 8` bytes of
  extension, data and padding. *(Revised: added the length range, extension and padding.)*
- **FR-3** Zero-length data is valid.
- **FR-4** A missing introducer or a truncated field throws an exception naming the byte offset.
- **FR-5** An empty file yields no fields.
- **FR-6** `FieldType` maps at least: BDT/EDT, BNG/ENG, BPG/EPG, BAG/EAG, BRG/ERG, TLE, PTX, NOP,
  IMM, IPO, MCF, PGD.
- **FR-7** TLE decoding reads triplet `0x02` (attribute name) and `0x36` (attribute value),
  ignores unknown triplets, and fails if the value triplet is missing.
- **FR-8** `dump-afp` prints one line per field (offset, name or hex ID, length), indented between
  Begin and End fields, with TLE pairs inline.

**Non-functional**
- **NFR-1** Streams the input; memory doesn't grow with file size.
- **NFR-2** Dump output is stable and diffable (it's used as golden-file test output).

## Design notes

- Package `dev.yjimmy.printlab.afp`. Replace the plan in `afp/package-info.java` with real docs.
- A tiny test-only `AfpBytes` builder makes byte-array inputs readable in tests.
- Reference: the MO:DCA reference from the AFP Consortium (afpcinc.org).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | A single field and a minimal document parse with every attribute correct | `AfpReaderTest.readsSingleField`, `readsMinimalDocument` |
| AC-2 | Length counts itself but not `0x5A` (off-by-one guard) | `lengthCountsItselfButNotIntroducer` |
| AC-3 | Bad input fails with the byte offset | `missingIntroducerFailsWithOffset`, `truncatedFieldFailsWithOffset` |
| AC-4 | EBCDIC decodes known bytes (`C1 C2 F1 40` → `"AB1 "`) | `EbcdicTest.*` |
| AC-5 | TLE name/value decode; unknown triplets ignored | `TleDecoderTest.*` |
| AC-6 | Dump of the minimal fixture matches the golden file | `DumpAfpTest.*` |

## Test plan

- Unit — `AfpReaderTest`: `readsSingleField`, `readsMinimalDocument`,
  `lengthCountsItselfButNotIntroducer`, `zeroLengthDataIsAllowed`,
  `missingIntroducerFailsWithOffset`, `truncatedFieldFailsWithOffset`, `emptyFileYieldsNoFields`
- Unit — `FieldTypeTest`: `knownIdsMapToNames`, `unknownIdRendersAsHex`
- Unit — `EbcdicTest`: `decodesUppercaseDigitsAndSpace`, `roundTripsAscii`
- Unit — `TleDecoderTest`: `decodesNameAndValue`, `ignoresUnknownTriplets`,
  `missingValueTripletFails`
- Golden-file — `DumpAfpTest`: minimal fixture vs `minimal.dump.txt`,
  `nestingIndentsBetweenBeginAndEnd`
- Manual: dump a real sample AFP file and compare with an AFP viewer; note the viewer and result
  in the PR.

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-02). Record the answers there and here.

- Real AFP files may use a different code page than IBM037. Make the code page a parameter, with
  IBM037 as the default.
- Finding a freely shareable real AFP sample may be hard. If so, the manual check uses PRD-04
  output, and the PR says so. **Resolved (Q-02):** a public sample is used but kept local-only;
  see "Manual check" below.

## Implementation notes

Decisions made while building this PR, beyond the requirements above:

- `AfpReader` is an `Iterator<StructuredField>`, so it throws unchecked exceptions:
  `AfpFormatException` (with `offset()`) for bad bytes, `UncheckedIOException` for I/O. After an
  error `hasNext()` is false. `readAll` is a convenience for small inputs.
- Checked against the MO:DCA Reference, AFPC-0004-11 (August 2026), "MO:DCA Structured Field
  Syntax". That changed FR-1 and FR-2: bytes 6-7 are **reserved** (MO:DCA calls a sequence
  number there unarchitected), so the record component is `reserved()`; the `0x5A` is a
  platform carriage-control byte, not part of MO:DCA; SFLength must be 8-32,767.
- The extension flag (`0x80`) is handled: the extension is split off into
  `StructuredField.extension()`. Padding (`0x08`) is stripped, using the 1-byte or 3-byte
  padding length at the end of the field, so `data()` is always the field's real data.
  Segmented fields (`0x20`) are returned one by one, not joined; `dump-afp` reports a segment
  that isn't directly followed by the next one, as MO:DCA requires.
- Begin/End is decided by the middle ID byte (`A8` Begin, `A9` End), not by the `FieldType`
  list, so nesting works for fields the enum doesn't name. `matchingEndId()` gives the End for a
  Begin.
- `FieldType` lists all 98 structured fields in the reference's Appendix E table, in its order,
  with the reference's acronyms (`PTD-1`, `MCF-1`, `PGP-1`) and coexistence/obsolete notes.
  `everyBeginTypeHasItsEndType` and `idsAreInTheReferenceOrder` guard the list.
- `TleDecoder` accepts only an attribute name in FQN format `00` (character string); another
  format (an OID or URL) fails with its offset instead of decoding as unreadable text.
- `TleDecoder` follows the TLE and triplet definitions: triplets are 3-254 bytes, an FQN at
  least 5 and an Attribute Value at least 4; the attribute name and value must each occur
  exactly once; a CGCSGID (X'01') triplet sets the code page for the triplets after it (by
  CPGID, or by CCSID, including UTF-8 and UTF-16). IS/3 requires that support. A CGCSGID on an
  enclosing Begin field, which would apply to the whole envelope, is not applied yet.
- `dump-afp` shows each Begin field's 8-character name, and reports problems on `!!` lines
  instead of stopping: an End with no matching Begin, an End whose name differs from its Begin's
  (an End name starting `FFFF` matches any), a Begin never closed, an interrupted segment, a TLE
  that won't decode, and the offset of bytes that aren't a field. It doesn't check which fields
  each envelope may contain (the "data stream states" in the reference). The last line counts fields and problems;
  the exit code is 1 when there are any. This is the "won't split" support scenario.
- A missing-introducer error says files copied from a mainframe sometimes have a record header
  before each field (a common reason a file "isn't AFP").
- The code page is a parameter everywhere (`dump-afp <file> [codePage]`), IBM037 by default.
- Extra tests beyond the plan: `truncatedHeaderFailsWithOffset`, `lengthShorterThanHeaderFails`,
  `extensionIsSeparatedFromData`, `extensionLongerThanFieldFails`, `readsOneFieldAtATime`
  (NFR-1), `ioErrorsAreUnchecked`, `otherCodePagesCanBeUsed`, `missingNameTripletFails`,
  `tripletRunningPastTheDataFails`, and the dump problem cases.
- Golden-file fixture: `minimal.afp` comes from a small Python script (`cp037` codec), not from
  this project's code; a test rebuilds it with `AfpBytes` and compares the bytes. That's an
  independent cross-check of the byte layout, but not the real-world sample Q-02 asks for.

## Manual check (2026-11-18, closes Q-02)

The AFPWorld "Example Healthcare document" (`01_Health_Coverage.afp`, written by Timitoo
Formatter Fox, kept local-only in `samples/afpworld/`) compared with **Papyrus AFP Viewer 7.7
SP3** (free edition):

- `dump-afp` reads all 12,784 bytes as 25 fields with 0 problems: BPF, an inline resource group
  (BRG) with the JPEG logo in an object container, then BDT with one page.
- Papyrus shows the same: one document, one page.
- Papyrus reports `TTF file 'Arial Bold' not found in library ''`. That matches the dump: the
  page's MDR maps TrueType fonts by name (Arial Bold, Segoe UI, Arial) instead of using an MCF.
  It's a viewer setting (no TrueType library path), not a reading error. An older viewer,
  AFPviewer 2.51, fails on the same file with "Missing Character Set Resource" because it
  doesn't support TrueType fonts mapped through an MDR.
- The golden dump is `afpworld-health-coverage.dump.txt`, checked by
  `DumpAfpTest.afpWorldHealthCoverageSampleMatchesGoldenDump` when the sample is present.

The sample has no named page groups or TLEs, so it confirms the reader, not PRD-05's split.

## Definition of done

- Shared DoD, plus: README "Run it" shows `dump-afp`, and the concept map links AFP page groups
  and TLEs to this package.
