# PRD-04 — AFP test-data writer (page groups + TLEs)

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | M | 03 | `feature/04-afp-writer` |

## Summary

Generate AFP statement print runs with the same content as the existing PDF generator: one named
page group per statement, with TLEs carrying account number, name and zip.

## Why

AFP splitting (PRD-05) and the cross-format parity tests (PRD-05, PRD-10) need AFP input whose
contents are known exactly. Writing AFP is also the best check that the reader in PRD-03 really
understands the format: if the writer's output reads back field for field, the encoding rules
are right.

## Users and scenarios

- **Developer** runs `App generate-afp 200 run.afp` and drops it into the hot folder.
- **Developer** compares `generate 50 run.pdf` and `generate-afp 50 run.afp` with the same seed;
  both contain the same statements.

## Goals

- `AfpWriter` that computes lengths and sequence numbers.
- `StatementAfpGenerator` mirroring `StatementPdfGenerator`'s data for a given seed.
- `generate-afp` CLI.

## Non-goals

- Visual fidelity with the PDF statements; the pages only need minimal PTOCA text.
- Embedding fonts or overlays (reference a standard coded font).
- Conforming to every MO:DCA interchange set rule.

## Requirements

**Functional**
- **FR-1** `AfpWriter` writes a field from (type, flags, data), computing the length and an
  incrementing sequence number.
- **FR-2** Data longer than the maximum field length (32,759 bytes) is rejected.
- **FR-3** Each statement becomes BNG, TLE × 3 (`accountNumber`, `name`, `zip`), its pages
  (BPG … EPG), then ENG, all inside BDT … EDT.
- **FR-4** TLE encoding is the inverse of PRD-03's decoder; characters not in the code page are
  rejected.
- **FR-5** The same seed produces byte-identical files.
- **FR-6** Account numbers, names, zips and page counts match the PDF generator for the same
  seed.

**Non-functional**
- **NFR-1** The generator and the PDF generator share one source of statement data (extract a
  `StatementData` generator) rather than duplicating random logic.

## Design notes

- Extract the random statement data from `StatementPdfGenerator` into `StatementDataGenerator`,
  so the PDF and AFP generators take the same `List<Statement>`.
- PTOCA content can be a single text run per line (`PTX` with `TRN` control sequences).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Written fields read back identically | `AfpWriterTest.writtenFieldIsReadBackIdentically` |
| AC-2 | Lengths are computed; oversize data rejected; sequence numbers increment | `AfpWriterTest.*` |
| AC-3 | TLE encode → decode round-trips; bad characters rejected | `TleEncoderTest.*` |
| AC-4 | Deterministic output; one group per statement with all three TLEs; balanced Begin/End | `StatementAfpGeneratorTest.*` |
| AC-5 | Same statements as the PDF generator for the same seed | `contentMatchesPdfGeneratorForSameSeed` |

## Test plan

- Unit — `AfpWriterTest`: `writtenFieldIsReadBackIdentically`, `lengthIsComputedFromData`,
  `rejectsDataLongerThanMaxFieldLength`, `sequenceNumbersIncrement`
- Unit — `TleEncoderTest`: `encodeThenDecodeRoundTrips`, `rejectsCharactersNotInCodePage`
- Integration — `StatementAfpGeneratorTest`: `sameSeedProducesIdenticalBytes`,
  `groupCountEqualsStatementCount`, `everyGroupHasAccountNumberNameAndZipTles`,
  `beginsAndEndsAreBalanced`, `contentMatchesPdfGeneratorForSameSeed`
- Manual: the generated file opens in a free AFP viewer; screenshot in the PR.

## Risks and open questions

- A viewer may reject minimal pages (no AEG, or missing descriptors). If so, add the minimal
  descriptors the viewer needs and note it.
- **Shortcut:** if time is short, skip this PRD. Check a sample AFP file with page groups and
  TLEs into `fixtures/afp/` and test PRD-05 against it.

## Definition of done

- Shared DoD, plus: README shows `generate-afp`.
