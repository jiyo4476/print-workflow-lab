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
  `xrefDumpShowsTwoSectionsAndChangedObjects`
- Differential — `PdfReaderVsPdfBoxTest` extended to compressed files.

## Risks and open questions

- Predictor bugs only show up on certain byte patterns. Use the PNG spec's worked examples plus
  random round-trips.

## Definition of done

- Shared DoD, plus: the README concept map mentions incremental updates.
