# PRD-10 — PDF/VT-style document parts (DPart) indexing

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| PDF | M | 08, 02 | `feature/10-dpart-indexing` |

## Summary

Put the document index **inside the PDF**: a `/DPartRoot` tree whose nodes give each statement's
page range and metadata (`AccountNumber`, `Name`, `Zip`). A `SplitPdfByDPart` step splits and
indexes from that tree, with no dependence on where text sits on the page.

## Why

PDF/VT document parts are PDF's answer to AFP page groups and TLEs, and they're how modern
variable-data print jobs carry their index. With this PRD, the project can split the same
statement run three ways (AFP TLEs, PDF DPart, PDF page regions) and show that all three agree.
That's a compact, convincing demo of how the formats compare.

## Users and scenarios

- **Developer** runs `generate --dpart 200 run.pdf`, and `dump-pdf` shows the DPart tree.
- **Workflow designer** uses `workflows/statements-dpart.yaml`; documents come out indexed even if
  the page layout changes.

## Goals

- Generator option `--dpart`: a `/DPartRoot` with one DPart per statement (`/Start`, `/End`,
  `/DPM`).
- `SplitPdfByDPart` step reading the tree through the PRD-07/08 reader.
- A sample workflow, and a three-way parity test.

## Non-goals

- Full PDF/VT conformance (output intents, XMP identification, etc.).
- Editing the DPart tree of existing files.

## Requirements

**Functional**
- **FR-1** The generator writes a DPart tree that covers every page exactly once, with ordered,
  non-overlapping ranges.
- **FR-2** Each statement's DPart has `/DPM` entries `AccountNumber`, `Name`, `Zip`.
- **FR-3** `SplitPdfByDPart` splits at a configured record level (e.g. level 2 for batch →
  statement); DPM entries become document properties (names normalized to the same keys as
  other splitters, e.g. `accountNumber`).
- **FR-4** Overlapping page ranges fail the job with a clear message.
- **FR-5** A missing required DPM key marks `indexError`, which PRD-02 routes to exceptions.
- **FR-6** A file with no DPart tree fails, suggesting the region-based `SplitPdf`.

**Non-functional**
- **NFR-1** Reading the tree doesn't parse page content streams.

## Design notes

- Follows the PDF/VT (ISO 16612-2) document-part structure: `/DPartRoot` → `/DParts` arrays of
  nodes, with `/Start` / `/End` page references on leaf nodes.
- Splitting writes output pages via PDFBox (writing PDFs is out of scope for the reader).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Generated DPart trees cover every page once, in order | `DPartWriterTest.*` |
| AC-2 | Flat and two-level trees read at the configured level; overlaps fail | `DPartReaderTest.readsFlatTree`, `readsTwoLevelTreeAtConfiguredRecordLevel`, `overlappingRangesFail` |
| AC-3 | Missing DPM key → `indexError` → exceptions | `missingRequiredDpmKeyMarksIndexError` |
| AC-4 | DPart, region and AFP splits produce identical documents, properties and zip order | `IndexParityTest` |

## Test plan

- Unit — `DPartWriterTest`: `everyPageCoveredExactlyOnce`, `rangesAreOrderedAndStartBeforeEnd`
- Unit — `DPartReaderTest`: `readsFlatTree`, `readsTwoLevelTreeAtConfiguredRecordLevel`,
  `overlappingRangesFail`, `missingRequiredDpmKeyMarksIndexError`
- Workflow — `IndexParityTest` (three-way parity for one seed)

## Risks and open questions

This PR depends on PRD-04's decision, tracked as Q-03 in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md).

- If PRD-04 (AFP writer) was skipped, `IndexParityTest` compares DPart with region only, and the
  PR says so.

## Definition of done

- Shared DoD, plus: the README concept map adds "PDF/VT document parts ↔ AFP page groups + TLEs".
