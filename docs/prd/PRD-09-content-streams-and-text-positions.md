# PRD-09 — Content streams and text positions

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| PDF | L | 07 | `feature/09-content-streams` |

## Summary

Parse page content streams into operators, track the graphics and text state, and compute where
each glyph lands on the page. A built-in region text extractor then lets `SplitPdf` run without
PDFBox (`extractor: builtin`). `App dump-content` prints a page's operators.

## Why

Region-based PDF indexing, which is what the skeleton does, depends entirely on knowing where
text is drawn. Building that from the operators up explains why PDF text extraction is hard:
there are no words, only positioned glyphs. In an interview, it shows why AFP TLEs and PDF/VT
metadata (PRD-10) are more reliable than reading text off the page.

## Users and scenarios

- **Developer** runs `dump-content run.pdf 1` and sees `BT … Tf … Td … Tj … ET` blocks, indented
  inside `q`/`Q`.
- **Workflow designer** sets `extractor: builtin` on `SplitPdf` and gets the same
  `documents.csv` as with PDFBox.

## Goals

- `ContentStreamParser` → `Operation(operator, operands)`, skipping inline images safely.
- `GraphicsState` and text-state tracking for the listed operators.
- `TextPositionExtractor` for simple fonts (Standard 14 metrics, WinAnsiEncoding), in the
  top-left coordinates the workflow regions use.
- `RegionTextExtractor` and `SplitPdf` param `extractor: builtin | pdfbox`.
- `dump-content` CLI.

## Non-goals

- CID / Type 0 fonts and `ToUnicode` maps: detected and reported as unsupported, never garbled.
- Rendering, clipping paths, annotations, form XObjects (beyond noting them in the dump).
- Reading order or word reconstruction beyond what region extraction needs.

## Requirements

**Functional**
- **FR-1** Operands attach to the following operator; `TJ` arrays keep kerning numbers.
- **FR-2** Inline images (`BI … ID … EI`) are skipped correctly even when the image data contains
  the bytes `EI`.
- **FR-3** Tracked state: `q`/`Q` stack, `cm`, `BT`/`ET`, `Tf`, `Td`/`TD`/`Tm`/`T*`, `TL`,
  `Tc`/`Tw`/`Tz`, `Tj`/`TJ`/`'`/`"`.
- **FR-4** `cm` concatenates in the correct order; `T*` uses the current leading.
- **FR-5** Glyph positions are converted to top-left page coordinates in points.
- **FR-6** `TJ` kerning shifts following glyphs by thousandths of text-space units.
- **FR-7** `SplitPdf` with `extractor: builtin` produces the same result as `pdfbox` for
  generated statements.
- **FR-8** A page using a CID font reports "unsupported font" instead of returning wrong text.

**Non-functional**
- **NFR-1** Position accuracy within ±0.5 pt of the generator's coordinates.

## Design notes

- Standard 14 font widths come from the AFM metrics. Bundle the needed tables (Helvetica,
  Helvetica-Bold), or read them from PDFBox's bundled AFM files.
- Keep `pdfbox` as the default extractor until parity holds over many seeds.

## Split into two PRs

- **9a** (`feature/09a-content-stream-parser`): `ContentStreamParser`, `Operation`,
  `ContentOperators` (Annex A's 73 operators with a description each),
  `PdfDocumentReader.content(page)` and `App dump-content`. FR-1, FR-2.
- **9b** (`feature/09b-text-positions`): graphics and text state, glyph positions,
  `RegionTextExtractor` and `extractor: builtin | pdfbox`. FR-3 to FR-8.

## Decided in 9a

- **Operands and operators.** Operands are the ordinary objects, read by the lexer and, for
  arrays and dictionaries, `PdfParser`; `true`, `false` and `null` are operands; any other
  keyword is an operator. Content has no references, so `1 0 R` is two numbers and an operator
  `R`. Operators aren't checked against Annex A by the parser (an unknown one is legal inside
  `BX`/`EX`); `dump-content` notes them. Operands left at the end with no operator, `ID` or
  `EI` outside an inline image, and braces are errors with the offset.
- **An inline image is one `Operation`**, `BI`, whose operand is a `PdfStream` (its parameters
  and its still-encoded bytes), since that's what it is.
- **Where an inline image's data ends**, in order: `/L` or `/Length` (PDF 2.0); for an
  unfiltered image, `ceil(W x components x BPC / 8) x H`, with components from `/CS`
  (`G`, `RGB`, `CMYK`, an `I` indexed array) or 1 for `/IM true`; otherwise the first `EI`
  with white space before it, white space or the end after it, and 32 bytes of text after that.
  A named color space (from the page resources) falls back to the scan; 9b can look it up.
- **PDFBox isn't the oracle for inline images.** 3.0.3's `PDFStreamParser` returns nothing after
  `BI` on a page PDFBox itself wrote (`q cm BI ... ID <8 bytes> EI Q BT ET`). Generated
  statements are compared operator by operator with PDFBox; inline images are checked against
  written-out expectations.
- **Multiple content streams** are joined with a newline between them (7.8.2: a split falls
  between tokens), capped at the decoder's 256 MiB.
- **`dump-content <file> <page>`** prints the page and its streams, then one operation per line,
  indented two spaces inside `q`/`Q`, `BT`/`ET` and `BMC`/`BDC`...`EMC`, with a `%` comment from
  column 40: the Annex A description, plus the base font for `Tf` and the XObject subtype (image
  size, or "form", not followed) for `Do`. Unmatched closers, unclosed openers and unknown
  operators are noted; content that can't be parsed stops the dump with a note. Exit code 1 for
  no such page, a stream that can't be decoded, or content that can't be parsed.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Operators parse correctly, including kerning arrays and tricky inline images | `ContentStreamParserTest.*` |
| AC-2 | Graphics/text state transitions are correct | `GraphicsStateTest.*` |
| AC-3 | The account number is found at the generator's position (±0.5 pt); kerning shifts glyphs | `TextPositionTest.*` |
| AC-4 | Built-in and PDFBox region text identical for 100 statements | `RegionExtractorVsPdfBoxTest` |
| AC-5 | `documents.csv` identical with either extractor | `BuiltinExtractorWorkflowTest` |
| AC-6 | CID fonts reported as unsupported, not garbled | `cidFontReportedAsUnsupportedNotGarbled` |

## Test plan

- Unit — `ContentStreamParserTest` (9a): `operandsAttachToFollowingOperator`,
  `tjArrayWithKerningNumbers`, `inlineImageContainingEiBytesIsSkipped`,
  `inlineImageLengthIsUsedWhenGiven`, `filteredInlineImageEndsAtTheEiFollowedByContent`,
  `imageParametersGiveTheDataSize`, `malformedInlineImagesFailWithTheirOffset`,
  `malformedContentFailsWithItsOffset`
- Differential — `ContentStreamVsPdfBoxTest` (9a): `generatedStatementsParseAsPdfBoxParsesThem`;
  `inlineImagesPdfBoxWritesAreReadByTheirSize`
- Golden — `DumpContentTest` (9a): `twoStatementsPageOneMatchesGoldenDump`
  (`fixtures/pdf/two-statements.content-1.dump.txt`), `nestingIsIndentedAndResourcesNamed`,
  `unmatchedAndUnknownOperatorsAreNoted`
- Unit — `GraphicsStateTest`: `qAndQRestoreState`, `cmConcatenatesInCorrectOrder`,
  `tdMovesLineMatrix`, `tStarUsesLeading`
- Unit — `TextPositionTest`: `accountNumberFoundAtGeneratorPosition`,
  `tjKerningShiftsFollowingGlyphs`
- Differential — `RegionExtractorVsPdfBoxTest` (100 generated statements)
- Workflow — `BuiltinExtractorWorkflowTest`
- Limitation — `cidFontReportedAsUnsupportedNotGarbled`

## Risks and open questions

- Size **L**: if needed, split into (a) parser + `dump-content` and (b) state tracking + positions
  + extractor.
- PDFBox and the built-in extractor may disagree on how they treat whitespace inside regions.
  Normalize whitespace in the comparison, and document it.

## Definition of done

- Shared DoD, plus: README explains `extractor: builtin | pdfbox` and the CID limitation.
