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
- **FR-2** An unknown placeholder fails when the workflow loads.
- **FR-3** A value too long for the symbology and size fails the job, naming the document
  sequence.
- **FR-4** Every page of every document gets a barcode; on the last page, page equals pageCount.
- **FR-5** The barcode is drawn as an image XObject at the configured position and size.
- **FR-6** Stamping doesn't overlap the configured `SplitPdf` regions, and doesn't break text
  extraction from them.

**Non-functional**
- **NFR-1** Barcodes decode reliably from a 300 dpi render.

## Design notes

- Render the barcode with ZXing to a bitmap and draw it with PDFBox `drawImage`, or draw the
  modules as filled rectangles (vector). Pick one and justify it in the PR; vector scales better.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Templates substitute values; unknown placeholders and over-long values fail clearly | `BarcodeTemplateTest.*` |
| AC-2 | Rendered pages decode to the expected values, on every page | `AddBarcodeStepTest.firstPageBarcodeDecodesToExpectedValues`, `lastPageBarcodeHasPageEqualsPageCount`, `everyPageOfEveryDocumentHasABarcode` |
| AC-3 | No overlap with split regions; text still extractable | `barcodeDoesNotOverlapConfiguredRegions`, `existingPageTextIsStillExtractable` |
| AC-4 | Content stream shows `cm` + `Do` at the configured position (when PRD-09 is merged) | `barcodeDrawnAsImageXObjectAtConfiguredPosition` |
| AC-5 | Preflight still passes after stamping (when PRD-11 is merged) | `preflightStillPassesAfterStamping` |

## Test plan

- Unit — `BarcodeTemplateTest`: `substitutesJobDocumentAndPageValues`,
  `unknownPlaceholderFailsAtLoad`, `valueTooLongForSymbologyFailsWithDocumentSequence`
- Integration — `AddBarcodeStepTest` (PDFBox `PDFRenderer` + ZXing decode), listed above
- Manual: print or zoom a page and scan the barcode with a phone app.

## Risks and open questions

- If drawing as vector rectangles, AC-4 checks `re`/`f` operators instead of `Do`. Adjust that
  test to the chosen approach.

## Definition of done

- Shared DoD, plus: the README roadmap's barcode item ticked; the sample workflow includes the
  step.
