# PRD-03 — AFP structured-field reader + dump tool

| Phase | Size | Depends on | Branch |
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
- **FR-1** Each field: offset, length, 3-byte type ID, flags, sequence number, data.
- **FR-2** The reader expects `0x5A`, then a 2-byte big-endian length that **counts itself but
  not the `0x5A`**, then ID (3), flags (1), sequence (2), and `length - 8` data bytes.
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

- Real AFP files may use a different code page than IBM037. Make the code page a parameter, with
  IBM037 as the default.
- Finding a freely shareable real AFP sample may be hard. If so, the manual check uses PRD-04
  output, and the PR says so.

## Definition of done

- Shared DoD, plus: README "Run it" shows `dump-afp`, and the concept map links AFP page groups
  and TLEs to this package.
