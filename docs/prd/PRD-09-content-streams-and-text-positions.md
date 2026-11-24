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
- Rendering, clipping paths and annotations. (Form XObjects turned out to be needed: see
  "Decided in 9b".)
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
- **Follow-up** (`feature/09c-dump-text`): `App dump-text <file> <page>`, each glyph's text,
  position, width and font size, one per line: the 9b counterpart of `dump-content`. Kept out
  of 9b to keep that PR to one concern.

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
  (`G`, `RGB`, `CMYK`, an `I` indexed array) or 1 for `/IM true`; otherwise, or when no `EI`
  follows the data at that size, the first `EI` with white space before it, white space, a
  delimiter or the end after it, then no control bytes but white space in the next 32 bytes
  (bytes past 0x7F are allowed: WinAnsi strings are written raw) and a token that lexes. For a
  known size, CR LF after `ID` is accepted as the separator. A named color space (from the page
  resources) falls back to the scan; 9b can look it up.
- **Limits.** At most 1,000 operands per operator and 100,000 items in an array or dictionary
  operand, so content of nothing but numbers fails with the offset instead of filling the heap.
- **Operators are shown escaped.** A keyword is any run of regular bytes, control bytes
  included, so `Operation.toString()` and error messages write bytes outside `21-7E` (and `#`)
  as `#xx` (`PdfLexer.printable`), as names are.
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

## Decided in 9b

- **State.** `GraphicsState` tracks the CTM with a `q`/`Q` stack, the text state parameters
  (`Tc`, `Tw`, `Tz`, `TL`, `Tf`, and `Ts`, which costs nothing), and the text and text line
  matrices. `q`/`Q` saves the text state parameters (they're part of the graphics state, 8.4)
  but not the text matrices; `BT` resets those. A `Q` with nothing saved is ignored, as viewers
  do. A state operator with the wrong operands fails with its offset.
- **`Matrix` follows the spec's row-vector convention**: `a.multiply(b)` applies `a` first, so
  `cm` is `new × CTM` and a glyph's text rendering matrix is `[Tfs×Th 0 0 Tfs 0 Trise] × Tm × CTM`.
- **Widths**: a font's `/FirstChar` and `/Widths` (then `/MissingWidth`, else 0); a Standard 14
  font without them uses Adobe's AFM metrics, parsed (C/WX/N lines) from the copies in the
  PDFBox jar, with WinAnsi's `nbspace` and `sfthyphen` measured as `space` and `hyphen`, as
  PDFBox does. `SimpleFontTest` checks every code of 12 fonts against PDFBox.
- **Encodings**: `WinAnsiEncoding` and `StandardEncoding` from `encodings.txt` (Annex D, plus
  the Adobe Glyph List's Unicode for each name; checked against PDFBox), a dictionary's
  `/BaseEncoding` and `/Differences`, and glyph names in the `uniXXXX` and `uXXXX` forms, with
  variant suffixes (`a.sc`) dropped and ligature parts (`f_i`) joined. An unknown name is
  U+FFFD, never a guess. A Standard 14 font's codes outside its `/Widths` (with no
  `/MissingWidth`) take the AFM width, as in PDFBox.
- **Unsupported, reported, never guessed** (`UnsupportedContentException`, message starting
  "unsupported font"): Type 0 (CID) and Type 3 fonts, `MacRomanEncoding` and other encodings,
  a built-in encoding (no `/Encoding`, or no `/BaseEncoding`, on Symbol, ZapfDingbats, a font
  with the symbolic flag or an embedded font: 9.6.6.1 gives those the font program's own
  encoding), a non-standard font without `/Widths`. Pages with `/Rotate` other than 0, and text
  that `cm`, `Tm` or a form's `/Matrix` turns off a horizontal left-to-right baseline, are
  reported too (skewed and upside-down text is read). `ToUnicode` maps aren't read.
- **Form XObjects are followed** (with their `/Matrix` and `/Resources`, 20 levels deep at most,
  so a form that draws itself fails): text inside a form is region text like any other, and
  PDFBox reads it, so skipping it would silently lose index values. The page and every form
  draw together may come to 256 MiB of content and a page to 1,000,000 glyphs, so forms that
  draw each other many times over fail instead of exhausting memory.
- **Page coordinates**: x from the left of the crop box (else the media box), y down from its
  top, the same coordinates PDFBox's `TextPosition.getX()`/`getY()` use.
- **Which glyphs a region holds**: those whose baseline start point is inside the rectangle
  (`Rectangle2D.contains`: left and top edges in, right and bottom out), PDFBox's rule, so the
  two agree. Fake bold (the same text again within a third of its width) is read once, as
  PDFBox reads it. Two deliberate differences: overlapping regions each get every glyph (PDFBox
  gives a shared glyph to only one of them, because its suppression of duplicated text is shared
  by all regions, in hash order; here each region drops its own duplicates), and lines are in reading order, top to bottom (PDFBox keeps the
  drawing order). Glyphs within half a font size of a baseline are one line; a gap of more than
  half a space is a word break.
- **`SplitPdf` `extractor`**: `pdfbox` (default) or `builtin`, checked when the workflow loads.
  `builtin` reads the region text with `RegionTextExtractor`; the documents are still written
  with PDFBox, so `builtin` holds the file in memory twice. A page it can't read fails the job
  with the page number. Encrypted PDFs need `pdfbox`.
- **Parity**: identical region text (white space normalized) for the statement regions on 100
  statements, and an identical `documents.csv` for a 40-statement run with bad addresses.
  `pdfbox` stays the default, as planned, until parity has held on more than generated input.

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
  `tdMovesLineMatrix`, `tStarUsesLeading`, `textRenderingMatrixScalesAndRises`,
  `wrongOperandsFailWithTheirOffset`
- Unit — `TextPositionTest`: `accountNumberFoundAtGeneratorPosition`,
  `tjKerningShiftsFollowingGlyphs`, `glyphsAdvanceByTheirAfmWidths`,
  `charAndWordSpacingAndHorizontalScaling`, `ctmAndCropBoxMoveTheOrigin`,
  `textInAFormXObjectIsFound`, `widthsArrayAndDifferencesAreRead`,
  `standard14CodesOutsideWidthsUseTheAfm`, `formsDrawnOverAndOverFailPastTheContentLimit`
- Unit — `RegionTextExtractorTest`: `aGlyphBelongsWhereItsBaselineStarts`,
  `overlappingRegionsEachGetTheirGlyphs`, `linesAreInReadingOrder`, `wordGapsBecomeSpaces`,
  `fakeBoldTextIsReadOnce`
- Differential — `SimpleFontTest`: `encodingTablesMatchPdfBox`, `standard14WidthsMatchPdfBox`
- Differential — `RegionExtractorVsPdfBoxTest`: `builtinAndPdfBoxRegionTextIdenticalFor100Statements`,
  `fakeBoldReadOnceByBoth`, `overlappingRegionsAreWhereTheExtractorsDifferByDesign`
- Workflow — `BuiltinExtractorWorkflowTest.documentsCsvIsIdenticalWithEitherExtractor`
- Limitation — `TextPositionTest.cidFontReportedAsUnsupportedNotGarbled`,
  `otherFontsTheExtractorCantReadAreReported`, `builtInEncodingsAreReportedNotGuessed`,
  `rotatedPagesAreReported`, `textThatIsNotHorizontalIsReported`;
  `SplitPdfStepTest.builtinExtractorFailsOnAPageItCantRead`

## Risks and open questions

- Size **L**: if needed, split into (a) parser + `dump-content` and (b) state tracking + positions
  + extractor.
- PDFBox and the built-in extractor may disagree on how they treat whitespace inside regions.
  Normalize whitespace in the comparison, and document it.

## Definition of done

- Shared DoD, plus: README explains `extractor: builtin | pdfbox` and the CID limitation.
