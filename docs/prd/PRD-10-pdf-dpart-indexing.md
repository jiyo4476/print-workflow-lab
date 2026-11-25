# PRD-10 — PDF/VT-style document parts (DPart) indexing

| Plan phase | Size | Depends on | Branch |
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
  statement), or at the file's `/RecordLevel` when none is configured; DPM entries become
  document properties (names normalized to the same keys as other splitters, e.g.
  `accountNumber`).
- **FR-4** Overlapping page ranges fail the job with a clear message.
- **FR-5** A missing required DPM key marks `indexError`, which PRD-02 routes to exceptions.
- **FR-6** A file with no DPart tree fails, suggesting the region-based `SplitPdf`.
- **FR-7** A page that no document at the chosen level covers fails the job, so it can't be
  dropped from the output unnoticed. A file with cover or separator pages outside its records sets
  `uncoveredPages: ignore` to leave them out on purpose.
- **FR-8** An encrypted file (empty user password) or one the project's reader can't read is
  opened by PDFBox and read from a clean copy, deleted when the step ends; a file that needs a
  password, or that PDFBox can't open either, fails with a message that says why.
- **FR-9** An output document carries nothing that points back into the source file. A PDF/VT page
  has a `/DPart` entry, which leads to the whole tree and every other document's index values, so
  the copied pages must not keep it (nor `/B`, `/PieceInfo` or link annotations).
- **FR-10** A `/RecordLevel` that isn't a whole number of 1 or more fails the job, naming the
  file as the source; the log says which level a split used and why.

**Non-functional**
- **NFR-1** Reading the tree doesn't parse page content streams.

## Design notes

- The structure is the PDF 2.0 document-part hierarchy (ISO 32000-2, 14.12), which PDF/VT
  (ISO 16612-2) uses: the catalog's `/DPartRoot` has a `/DPartRootNode`, whose `/DParts` is an
  array of arrays of child nodes; nodes have `/Parent`, and a node's `/Start` and `/End` reference
  its first and last page. `/RecordLevel` says which depth holds the print records.
  The details come from vendor documentation and Apache FOP's writer, not the paywalled standard,
  so this reader is lenient about them:
  - `/DParts` may be a flat array of nodes, or the array-of-arrays form;
  - the root dictionary may carry `/DParts` itself with no `/DPartRootNode` (what the first
    version of `generate --dpart` wrote);
  - a branch node may leave out `/Start` and `/End`, and then covers what its children cover; a
    leaf needs `/Start`, and without `/End` it is that one page (vendor documentation: `/End` is
    there "if a DPart node refers to not just one page but to a range of pages").
- `generate --dpart` writes a tree of the PDF 2.0 form (root, root node, `[[...]]`, `/Parent`,
  `/RecordLevel 1`, `/NodeNameList [/root /record]`) and a `/DPart` entry on every page, as PDF/VT
  does. Conformance to PDF/VT (output intents, XMP) is still a non-goal.
- Splitting writes output pages via PDFBox (writing PDFs is out of scope for the reader).
  `importPage` copies a page's dictionary as it is, so the steps remove the entries that point
  back into the source (FR-9) with a shared helper, `PdfPages`; `SplitPdf` uses it too.
- The project's own reader is strict, so a file it can't read, or an encrypted one, is saved
  clean by PDFBox first and read from that copy (FR-8). Memory is about 10 to 16 times the file
  size (a 5.8 MB file split in 72 MB of heap but not 56 MB; a 23 MB file in 384 MB but not
  256 MB), because both readers hold the file; each output document also carries its own copy of
  the fonts and images its pages use.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Generated DPart trees cover every page once, in order | `DPartWriterTest.*` |
| AC-2 | Flat and two-level trees read at the configured level; overlaps fail | `DPartReaderTest.readsFlatTree`, `readsTwoLevelTreeAtConfiguredRecordLevel`, `overlappingRangesFail` |
| AC-3 | Missing DPM key → `indexError` → exceptions | `SplitPdfByDPartStepTest.missingRequiredDpmKeyMarksIndexError`, `DPartWorkflowTest.missingRequiredDpmKeyMarksIndexErrorAndRoutesToExceptions` |
| AC-4 | DPart, region and AFP splits produce identical documents, properties and zip order | `IndexParityTest` |
| AC-5 | The PDF 2.0 shape reads; branch ranges derive from children; hostile trees fail cleanly | `DPartReaderTest.*`, `DPartWriterTest.writesThePdf20Shape` |
| AC-6 | Encrypted and damaged files split; a password-protected or unreadable one fails clearly; no decrypted copy is left | `SplitPdfByDPartStepTest.fileEncryptedWithAnEmptyUserPasswordIsDecryptedByPdfBox`, `fileWithItsCrossReferenceDataCutOffIsRepairedByPdfBox`, `fileThatNeedsAPasswordFailsWithAClearMessage`, `aFileNeitherReaderCanOpenFailsNamingBothProblems`, `theDecryptedCopyIsDeletedWhenTheSplitWorks` and `...WhenTheSplitFails` |
| AC-7 | Output documents hold no `/DPart`, tree or other document's pages | `SplitPdfByDPartStepTest.outputDocumentsDoNotCarryTheDocumentPartTree`, `SplitPdfStepTest.outputDocumentsDoNotCarryTheDocumentPartTree`, `linkAnnotationsDoNotDragOtherPagesIntoTheOutput` |
| AC-8 | `/RecordLevel` and `level` choose the documents; bad values fail clearly; uncovered pages fail unless ignored | `SplitPdfByDPartStepTest.recordLevelPicksTheRecordsOfAThreeLevelTree`, `aBadRecordLevelFailsAndNamesTheFile`, `aCoverPageOutsideEveryDocumentFailsUnlessLeftOutOnPurpose`, `DPartReaderTest.aBadRecordLevelFails` |

## Test plan

- Unit — `DPartWriterTest`: `everyPageCoveredExactlyOnce`, `rangesAreOrderedAndStartBeforeEnd`,
  `dpmEntriesMatchTheStatements`, `writesThePdf20Shape`
- Unit — `DPartReaderTest`: `readsFlatTree`, `readsTwoLevelTreeAtConfiguredRecordLevel`,
  `overlappingRangesFail`, the PDF 2.0 shape and derived ranges, hostile and malformed trees, and
  `readsTheTreeWithoutDecodingAnyPageContent` (NFR-1)
- Unit — `SplitPdfByDPartStepTest`: config checks, levels and `/RecordLevel`, multi-page ranges,
  page order, `missingRequiredDpmKeyMarksIndexError`, encrypted and damaged files
- Workflow — `DPartWorkflowTest` (index errors reach `exceptions`) and `IndexParityTest`
  (three-way parity for one seed, including each output document's pages and text)

## Risks and open questions

This PR depended on PRD-04's decision, tracked as Q-03 in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md).
Q-03 is decided (the AFP writer was built), so `IndexParityTest` compares all three splits.

- The DPart structure follows secondary sources (see Design notes). A file from a real PDF/VT
  tool may differ in ways this reader doesn't expect; the first one to try is the test.
- **`/RecordLevel` counts the root node as level 0** (so 1 is its children) in this reader and the
  generator. Vendor sources agree: Callas pdfToolbox says "since the root is zero ... the first
  level of the DPart hierarchy refers to the records", its sample has `/NodeNameList [/Root
  /Record /Page]` with `/RecordLevel 1`, and PDFlib's examples use `nodenamelist={root recipient}`
  with `recordlevel=1`. The ISO text itself (Table 408) could not be read, so if a real file
  counts from 1, a wrong default silently picks the level above the records whenever every level
  covers the pages. The log says which level was used; for a file from another tool, set
  `level` explicitly and check the documents it makes.
- **Page-count damage.** After a repair, the page tree's own `/Count` must match the pages that
  can be read, and this project's reader and PDFBox must agree on the number; either mismatch
  fails the job. A node pointing at a page that no longer exists fails as "not a page". A file
  that lost pages no node refers to is not noticed, because nothing says they were there.
- Only the page entries listed in FR-9 are removed from copied pages. Other entries that point
  back into the source file would be copied with the page; the list should grow if a real file
  shows one.

## Definition of done

- Shared DoD, plus: the README concept map adds "PDF/VT document parts ↔ AFP page groups + TLEs".
