# PRD-08 — Modern PDF structure: xref streams, object streams, incremental updates

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| PDF | M | 07 | `feature/08-modern-pdf-structure` |

## Summary

Extend the PRD-07 reader to handle PDFs the way most current tools write them (PDF 1.5+):
compressed cross-reference streams with PNG predictors, objects packed inside object streams, and
incremental updates chained through `/Prev`. Each revision of an edited file stays readable.

## Why

The classic xref table from PRD-07 is rare in files from real systems. Without this PRD the
reader fails on most PDFs a print shop actually receives. Incremental updates also explain a
common production surprise: a "small edit" that leaves the old content still inside the file.

## Users and scenarios

- **Developer** runs `dump-pdf --xref edited.pdf` and sees two xref sections, and which objects
  the second one changed.
- **Developer** asks `reader.revision(1)` for the file as it was before the edit.

## Goals

- Cross-reference streams (`/Type /XRef`, `/W`, `/Index`) with PNG predictors 10–15.
- Object streams (`/Type /ObjStm`, `/N`, `/First`).
- The `/Prev` chain: the newest entry wins, and every revision stays accessible.
- `dump-pdf --xref`.

## Non-goals

- Hybrid-reference files beyond what PDFBox produces in tests.
- Writing or saving PDFs.
- Linearization (read the file normally and ignore the hint tables).

## Requirements

**Functional**
- **FR-1** Xref streams decode with `/W` field widths and `/Index` ranges; entry type 2 means
  "inside object stream N at index I".
- **FR-2** PNG predictors None, Sub, Up, Average and Paeth decode correctly.
- **FR-3** An object inside an object stream resolves transparently through the same API as any
  other object.
- **FR-4** An object stream with a bad `/First` offset fails with a message naming the stream.
- **FR-5** The newest revision of an object wins; `revision(n)` returns the document as of xref
  section n.
- **FR-6** `dump-pdf --xref` lists each section (offset, type, object count) and the objects each
  update changed.

**Non-functional**
- **NFR-1** Decompressed object streams are cached per reader, so each is inflated once.

## Design notes

- `XrefSection` (classic or stream), chained into a `Revisions` list; the resolver looks up from
  newest to oldest.
- Test files: PDFBox default save (compressed) vs `NO_COMPRESSION` save of the same document; and
  `saveIncremental` after changing `/Info /Title`.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | All five PNG predictors match known vectors | `PngPredictorTest` |
| AC-2 | Compressed and uncompressed saves resolve to the same objects | `XrefStreamTest.compressedAndUncompressedSavesResolveTheSameObjects` |
| AC-3 | Objects in object streams resolve; a bad `/First` fails clearly | `ObjectStreamTest.*` |
| AC-4 | The newest revision wins, the previous one is still readable, and the xref dump shows both | `IncrementalUpdateTest.*` |
| AC-5 | The reader agrees with PDFBox on compressed files | `PdfReaderVsPdfBoxTest` (extended) |

## Test plan

- Unit — `PngPredictorTest` (parameterized: None, Sub, Up, Average, Paeth)
- Integration — `XrefStreamTest.compressedAndUncompressedSavesResolveTheSameObjects`
- Integration — `ObjectStreamTest`: `objectInsideObjectStreamResolves`,
  `objectStreamWithBadFirstOffsetFailsWithMessage`
- Integration — `IncrementalUpdateTest`: `newestRevisionWins`, `previousRevisionStillReadable`,
  `xrefDumpShowsTwoSectionsAndChangedObjects` (golden `fixtures/pdf/two-revisions.xref.dump.txt`),
  `objectFreedInAnUpdateResolvesToNullButIsInTheEarlierRevision`, `prevLoopFails`
- Integration — `XrefStreamTest`: also `indexRangesAndAMissingTypeField`,
  `flateStreamWithAPngPredictorReads`, `hybridFileReadsObjectsFromBothTheTableAndTheStream`
- Integration — `ObjectStreamTest`: also `objectStreamInsideAnObjectStreamFails`,
  `entryNamingTheWrongObjectOrIndexFails`
- Differential — `PdfReaderVsPdfBoxTest` extended to compressed files.

## Risks and open questions

- Predictor bugs only show up on certain byte patterns. Use the PNG spec's worked examples plus
  random round-trips.

## Decided in PR 8

- **Revisions are numbered from the oldest:** `revision(1)` is the file as first written and
  `revisions()` is how it is now, the order edits happen in. The reader opens the newest.
- **Hybrid files are read,** not refused: a table's `/XRefStm` stream fills in the entries the
  table doesn't have in use (7.5.8.4). It's a few lines, and some real files use it
  (Word's PDF export, for one).
- **`generate` saves PDFBox's default compressed form again** (PRD-07 had switched it to a
  classic table so `dump-pdf` could read its runs). `ClassicPdf` builds the same document and
  saves it with a classic table directly, so the fixture no longer has 28 free entries from a
  re-save; its object numbers changed (page 1's content stream is now object 10).
- An `Entry` is a sealed type: `Free`, `InFile` or `Compressed`. An xref stream row of an
  unknown type is free (Table 18); a missing type field (`/W [0 ...]`) means type 1.
- A cross-reference stream's own dictionary must be direct (7.5.8.2), so it's parsed without a
  resolver; its dictionary is also that section's trailer, so `dump-pdf` shows `/W` and `/Index`.
- Only the PNG predictors (10-15) are decoded; the TIFF predictor (2) is reported as
  unsupported, since cross-reference streams don't use it.
- An object stream inside an object stream, an entry pointing at something that isn't
  `/Type /ObjStm`, an index past `/N`, a header naming a different object, and a header that
  runs past `/First` all fail naming the stream. The offset in the exception is the object
  stream's in the file; the message gives the offset in its decoded data.
- A `/Prev` loop fails, and so do more than 10,000 sections.
- `xrefDumpShowsTwoSectionsAndChangedObjects` uses a file built with `PdfBytes`, not PDFBox's
  `saveIncremental`, because PDFBox writes a new random `/ID` on every incremental save, so its
  output can't be a golden file. A second test checks the dump of a PDFBox update names the
  changed `/Info` object.
- The PDFBox comparison (`PdfReaderVsPdfBoxTest`) runs every check on a classic and a
  compressed save of each seed, plus the AFPWorld sample PDF when it has been downloaded.

## Definition of done

- Shared DoD, plus: the README concept map mentions incremental updates.
