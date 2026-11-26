# PRD-12 — Barcode step for inserter control

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| Print features | M | 00 | `feature/12-barcode` |

## Summary

An `AddBarcode` step that stamps a Data Matrix barcode on every page of each PDF
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

- ZXing-based `AddBarcode` step for PDF documents, with position, size and a value template.
  Data Matrix only for now; Code 128 is a follow-up.
- Optional human-readable text: a follow-up, not in this PR.

## Non-goals

- Barcodes on AFP documents (BCOCA). A possible follow-up.
- Vendor-specific inserter mark formats, or OMR marks.
- USPS Intelligent Mail barcodes.

## Requirements

**Functional**
- **FR-1** Template placeholders: `${jobId}`, `${sequence}`, `${page}`, `${pageCount}`, and any
  document property (`${accountNumber}`).
- **FR-2** An unknown placeholder fails the job before any output is saved, naming the placeholder
  and the document. Document property names are only known when a job runs, so this can't be
  checked when the workflow loads.
- **FR-3** A value too long for the symbology and size fails the job, naming the document
  sequence.
- **FR-4** Every page of every document gets a barcode; on the last page, page equals pageCount.
- **FR-5** The barcode is drawn as vector shapes: one filled rectangle per module, at the
  configured position and size ([Q-18](../OPEN_QUESTIONS.md#q-18--draw-the-barcode-as-an-image-or-as-vector-shapes)).
- **FR-6** Stamping doesn't overlap the configured `SplitPdf` regions, and doesn't break text
  extraction from them.
- **FR-7** `AddBarcode` runs in the `print` phase, after `presort` and before `write-output`, so
  the page order is final when it stamps ([Q-19](../OPEN_QUESTIONS.md#q-19--where-in-the-workflow-does-addbarcode-run)).
- **FR-8** `${sequence}` is the document's position after presort, numbered 1..N in mailing order.
  Presort renumbers documents, so the `sequence` column in `documents.csv` also changes to mailing
  order ([Q-20](../OPEN_QUESTIONS.md#q-20--what-does-sequence-mean)).
- **FR-9** `position` and `size` are in points, from the top-left of the page, like `SplitPdf`
  regions. `position` is the symbol's top-left corner; `size` is the box it is scaled into, with
  square modules, so the symbol may be smaller than the box in one direction ([Q-21](../OPEN_QUESTIONS.md#q-21--what-units-do-the-barcode-position-and-size-use)).

**Non-functional**
- **NFR-1** Barcodes decode reliably from a 300 dpi render.

## Design notes

- Use ZXing to encode the value into a module matrix, then draw each dark module as a filled
  rectangle in the page content stream. Vector keeps edges sharp at print size, which matters
  for scanner reads ([Q-18](../OPEN_QUESTIONS.md#q-18--draw-the-barcode-as-an-image-or-as-vector-shapes)).
- Each module must print at least 1 pt square. A smaller module fails the job as too long for
  the box.
- The symbol has no quiet zone of its own: the clear space around it is the designer's to leave.
- Presort renumbering (FR-8) changes behavior for every job that presorts, not only jobs with a
  barcode. The input order of a document is no longer recoverable from `sequence`. Check `documents.csv` tests and any golden output that records `sequence`.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Templates substitute values; unknown placeholders and over-long values fail clearly | `BarcodeTemplateTest.*` |
| AC-2 | Rendered pages decode to the expected values, on every page | `AddBarcodeStepTest.firstPageBarcodeDecodesToExpectedValues`, `lastPageBarcodeHasPageEqualsPageCount`, `everyPageOfEveryDocumentHasABarcode` |
| AC-3 | No overlap with split regions; text still extractable | `barcodeDoesNotOverlapConfiguredRegions`, `existingPageTextIsStillExtractable` |
| AC-4 | Content stream draws the modules with `re`/`f` at the configured position and size, in points (PRD-09 is merged) | `barcodeDrawnAsVectorAtConfiguredPosition` |
| AC-6 | `${sequence}` follows mailing order after presort, and `documents.csv` matches | `sequenceIsMailingOrderAfterPresort` |
| AC-5 | Preflight still passes after stamping | `preflightStillPassesAfterStamping` |

## Test plan

- Unit — `BarcodeTemplateTest`: `substitutesJobDocumentAndPageValues`,
  `unknownPlaceholderNamesTheDocumentAndThePlaceholder`
- Integration — `AddBarcodeStepTest` (PDFBox `PDFRenderer` + ZXing decode), listed above
- Integration — `SortDocumentsStepTest`: `sequenceIsMailingOrderAfterPresort` (documents renumbered
  1..N in the order presort leaves them)
- Manual: print or zoom a page and scan the barcode with a phone app.

## Risks and open questions

- All four open questions are decided: [Q-18](../OPEN_QUESTIONS.md#q-18--draw-the-barcode-as-an-image-or-as-vector-shapes)
  to [Q-21](../OPEN_QUESTIONS.md#q-21--what-units-do-the-barcode-position-and-size-use).
- Renumbering (FR-8) changes `documents.csv` for every presorted job. Review it before merging.

## Definition of done

- Shared DoD, plus: the README roadmap's barcode item ticked; the sample workflow includes the
  step.
