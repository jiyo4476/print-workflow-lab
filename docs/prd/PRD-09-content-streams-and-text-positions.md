# PRD-09 — Content streams and text positions

| Phase | Size | Depends on | Branch |
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

- Unit — `ContentStreamParserTest`: `operandsAttachToFollowingOperator`,
  `tjArrayWithKerningNumbers`, `inlineImageContainingEiBytesIsSkipped`
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
