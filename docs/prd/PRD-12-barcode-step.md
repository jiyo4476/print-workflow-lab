# PRD-12 — Barcode step for inserter control

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| Print features | M | 00 | `feature/12-barcode` |

## Summary

An `AddBarcode` step that stamps a Data Matrix (or Code 128) barcode on every page of each PDF
document, encoding job, document and page information for mail-inserting equipment. Optional
human-readable text goes under it.

## Why

Inserters read a barcode on each page to know which sheets belong in which envelope and when
an envelope is complete. Adding those marks is a standard job for a print workflow system, and
a feature you can show working end to end: generate, stamp, then scan with a phone.

## Users and scenarios

- **Workflow designer** adds `AddBarcode` with template
  `${jobId}|${sequence}|${page}|${pageCount}|${accountNumber}` at a fixed position in the
  margin.
- **Operator** scans a page with a phone app and reads back "page 2 of 3 for account
  1234567890".

## Goals

- ZXing-based `AddBarcode` step for PDF documents, with symbology, position, size and a value
  template.
- Optional human-readable text.

## Non-goals

- Barcodes on AFP documents (BCOCA). A possible follow-up.
- Vendor-specific inserter mark formats, or OMR marks.
- USPS Intelligent Mail barcodes.

## Requirements

**Functional**
- **FR-1** Template placeholders: `${jobId}`, `${sequence}`, `${page}`, `${pageCount}`, and any
  document property (`${accountNumber}`).
- **FR-1a** `${sequence}` is the document's position in the job's final mailing order, numbered
  from 1 with no gaps. It is set after presort, not at split time, so it matches the order the
  inserter receives the documents
  ([Q-19](../OPEN_QUESTIONS.md#q-19--what-sequence-means)).
- **FR-2** An unknown placeholder fails when the workflow loads.
- **FR-3** A value too long for the symbology and size fails the job, naming the document
  sequence.
- **FR-4** Every page of every document gets a barcode; on the last page, page equals pageCount.
- **FR-5** The barcode is drawn as filled rectangles, one per module, at the configured position
  and size. Each module is a `re` path painted with `f`, so the barcode stays sharp at any print
  size ([Q-18](../OPEN_QUESTIONS.md#q-18--how-the-barcode-is-drawn)).
- **FR-5a** Position and size are in points, measured from the top-left of the page, the same
  units as the `SplitPdf` regions ([Q-21](../OPEN_QUESTIONS.md#q-21--units-for-position-and-size)).
- **FR-6** Stamping doesn't overlap the configured `SplitPdf` regions, and doesn't break text
  extraction from them.
- **FR-7** `AddBarcode` runs in the `print` phase, after `presort`, and before `write-output`. The
  sample workflow sets this order
  ([Q-20](../OPEN_QUESTIONS.md#q-20--where-the-step-sits-in-the-workflow)).

**Non-functional**
- **NFR-1** Barcodes decode reliably from a 300 dpi render.

## Design notes

- Encode the symbology with ZXing (`Data Matrix` by default). Read its module matrix, then write
  each dark module as a filled rectangle in the page's content stream. Nothing is rasterised, so
  no bitmap is involved at any size.
- Renumbering for FR-1a: after `presort` reorders the documents, a step sets each document's
  `sequence` to its index plus one. The change touches anything that reads `sequence` after
  presort, including file names and output ordering. The PR checks each of them.
- Because the barcode is stamped after presort, the order the step sees is already final.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Templates substitute values; unknown placeholders and over-long values fail clearly | `BarcodeTemplateTest.*` |
| AC-2 | Rendered pages decode to the expected values, on every page | `AddBarcodeStepTest.firstPageBarcodeDecodesToExpectedValues`, `lastPageBarcodeHasPageEqualsPageCount`, `everyPageOfEveryDocumentHasABarcode` |
| AC-3 | No overlap with split regions; text still extractable | `barcodeDoesNotOverlapConfiguredRegions`, `existingPageTextIsStillExtractable` |
| AC-4 | Content stream draws the modules with `re` + `f` inside the configured rectangle, in points (PRD-09 is merged) | `barcodeDrawnAsVectorAtConfiguredPosition` |
| AC-5 | Preflight still passes after stamping (PRD-11 is merged) | `preflightStillPassesAfterStamping` |
| AC-6 | `${sequence}` is 1..N in mailing order after presort, and the step runs after presort | `sequenceIsMailingOrderAfterPresort`, `barcodeRunsAfterPresort` |

## Test plan

- Unit — `BarcodeTemplateTest`: `substitutesJobDocumentAndPageValues`,
  `unknownPlaceholderFailsAtLoad`, `valueTooLongForSymbologyFailsWithDocumentSequence`
- Unit — `RenumberDocumentsTest` (or the presort test, whichever the PR uses):
  `sequenceIsMailingOrderAfterPresort`
- Integration — `AddBarcodeStepTest` (PDFBox `PDFRenderer` + ZXing decode), listed above, plus
  `barcodeRunsAfterPresort` in the statements workflow test
- Manual: print or zoom a page and scan the barcode with a phone app.

## Risks and open questions

- Renumbering after presort changes the `sequence` values that later steps see. Check file names,
  output ordering and any existing test that expects split-order numbers before merging.
- Vector modules make a large content stream for big symbols. Check the size for a full-page
  Data Matrix at 300 dpi.

## Definition of done

- Shared DoD, plus: the README roadmap's barcode item ticked; the sample workflow includes the
  step.
