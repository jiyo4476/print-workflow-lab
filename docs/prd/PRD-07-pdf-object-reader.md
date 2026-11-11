# PRD-07 — PDF object reader + dump tool

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| PDF | L | 00 | `feature/07-pdf-object-reader` |

## Summary

Open a PDF without PDFBox: tokenize it, parse objects, find the cross-reference table, resolve
indirect objects, decode Flate streams and walk the page tree. `App dump-pdf` prints what it
finds. PDFBox stays in the project as the **test oracle**.

## Why

The skeleton uses PDFBox's high-level API, which hides how a PDF is built. Print workflow
engineers regularly debug PDFs that print wrong, and that means understanding objects,
references, the xref table and streams. Reading them directly, the same way PRD-03 reads AFP
structured fields, gives you the vocabulary to explain PDF's structure and how it differs from
AFP.

## Users and scenarios

- **Developer** runs `dump-pdf run.pdf` and sees the version, trailer, and each page's MediaBox,
  font names and content-stream length.
- **Developer** runs `dump-pdf run.pdf 12` to inspect object 12, with its decoded stream if
  it's text.

## Goals

- `PdfLexer` and `PdfParser` producing a sealed `PdfObject` hierarchy.
- `XrefReader` for the classic `xref` table and `trailer`, with lazy object resolution.
- `StreamDecoder` for `FlateDecode` and unfiltered streams.
- `PdfDocumentReader` walking catalog → page tree → pages, resolving inherited attributes.
- `dump-pdf` CLI.

## Non-goals

- Xref streams, object streams and incremental updates (PRD-08).
- Content-stream operators and text positions (PRD-09).
- Encryption (PRD-11 only detects it), repairing broken files, writing PDFs.
- Filters other than Flate. Others are reported as unsupported, not decoded.

## Requirements

**Functional**
- **FR-1** The lexer handles numbers (`42`, `-3`, `+7`, `3.14`, `-.5`, `4.`), names with `#xx`
  escapes, literal strings (nested parentheses; `\n`, `\)`, `\\` and octal escapes), hex strings
  (odd digit count pads with zero), arrays, dictionaries, keywords and comments.
- **FR-2** The parser tells `12 0 R` (a reference) apart from two numbers.
- **FR-3** Streams work with a direct `/Length` or one that is an indirect reference.
- **FR-4** `startxref` is found from the end of the file, tolerating `\n`, `\r\n` and a missing
  final newline. Multiple xref subsections parse; free entries resolve to null.
- **FR-5** An xref offset pointing at the wrong object fails with a message naming both object
  numbers.
- **FR-6** Page attributes `MediaBox` and `Resources` are inherited from ancestor page-tree
  nodes.
- **FR-7** Unknown filters are reported as unsupported; other objects stay readable.
- **FR-8** `dump-pdf <file>` prints header, trailer and page tree; `dump-pdf <file> <objNum>`
  prints one object.

**Non-functional**
- **NFR-1** Objects resolve lazily via the xref; the reader doesn't parse every object up front.
- **NFR-2** Parse errors report the byte offset.

## Design notes

- Package `dev.yjimmy.printlab.pdf`. Java 21 sealed interfaces and records for `PdfObject`.
- Test fixtures are saved with PDFBox `CompressParameters.NO_COMPRESSION`, so they use a classic
  xref table.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | All token forms lex correctly | `PdfLexerTest` (parameterized) |
| AC-2 | Nested structures, references vs numbers, and both `/Length` forms parse; bad input fails with offset | `PdfParserTest.*` |
| AC-3 | xref edge cases handled; a wrong offset gives a clear error | `XrefReaderTest.*` |
| AC-4 | Flate round-trips; unknown filters are reported, not crashed on | `StreamDecoderTest.*` |
| AC-5 | Page count, MediaBoxes, font names and decoded content match PDFBox | `PdfReaderVsPdfBoxTest` |
| AC-6 | Dump output matches the golden file | `DumpPdfTest` |

## Test plan

- Unit — `PdfLexerTest` (parameterized numbers, `nameWithHexEscapeDecodes`,
  `literalStringWithNestedParentheses`, `literalStringEscapes`,
  `hexStringWithOddDigitCountPadsZero`, `commentsAreSkipped`, `keywordsAreRecognized`)
- Unit — `PdfParserTest`: `nestedDictionariesAndArrays`, `indirectReferenceVsTwoNumbers`,
  `streamWithDirectLength`, `streamWithIndirectLength`, `unterminatedDictionaryFailsWithOffset`
- Unit — `XrefReaderTest`: `findsStartxrefWithTrailingWhitespaceVariants`,
  `parsesMultipleSubsections`, `freeEntriesResolveToNull`,
  `offsetPointingAtWrongObjectFailsWithMessage`
- Unit — `StreamDecoderTest`: `flateRoundTrip`, `unknownFilterReportedAsUnsupported`
- Differential — `PdfReaderVsPdfBoxTest` (several seeds), including
  `decodedContentStreamsMatchPdfBoxByteForByte`
- Golden-file — `DumpPdfTest` (`fixtures/pdf/two-statements.pdf`)
- Manual: compare `dump-pdf` with `qpdf --qdf` or `mutool show` on a real-world PDF.

## Risks and open questions

- Size **L**: if it runs long, split the PR into (a) lexer + parser and (b) xref + document
  reader + dump. Each half has its own tests.

## Definition of done

- Shared DoD, plus: README "Run it" shows `dump-pdf`.
