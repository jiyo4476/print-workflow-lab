# PRD-05 — `SplitAfp` step: TLEs become document properties

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | M | 04 | `feature/05-split-afp` |

## Summary

A workflow step that splits an AFP print file on its named page groups, writes each group as a
standalone AFP document, and turns the group's TLEs into document properties. It's the AFP
counterpart of `SplitPdf`, with no guessing from text positions.

## Why

This is the core AFP indexing pattern in production print: page groups mark document
boundaries, TLEs carry the index values, and those values drive sorting, routing, inserting and
reprints. Compared with region-based PDF splitting, it shows why AFP's built-in index makes
document identification reliable.

## Users and scenarios

- **Workflow designer** points `workflows/statements-afp.yaml` at a folder; each statement comes
  out with `accountNumber`, `name`, `zip` and `pageCount` properties.
- **Operator** finds a statement with a missing `zip` TLE in the exceptions queue, while the rest
  of the job completes.

## Goals

- Split on BNG/ENG; carry BDT/EDT and any inline resource group into every output document.
- TLEs become document properties; add `pageCount`.
- `requiredProperties` marks incomplete groups (`indexError`) instead of failing the job.
- A sample AFP workflow mirroring the PDF one.

## Non-goals

- Converting AFP to PDF, or rendering pages.
- Resolving external resources (fonts, overlays from a resource library).
- Nested page groups beyond one level.

## Requirements

**Functional**
- **FR-1** One document per top-level BNG/ENG group, written as a valid AFP file.
- **FR-2** The file's BDT/EDT wrap each output; an inline resource group (BRG/ERG) is copied into
  every output.
- **FR-3** Every TLE in a group becomes a document property; `pageCount` = number of BPGs in the
  group.
- **FR-4** A group missing a `requiredProperties` entry gets `indexError=<missing names>` and
  stays in the job; PRD-02 rules route it.
- **FR-5** A file with no page groups fails the job with a clear message.
- **FR-6** Unbalanced Begin/End fails with the byte offset.
- **FR-7** `workflows/statements-afp.yaml` mirrors `statements.yaml` (split, route, presort,
  write).

**Non-functional**
- **NFR-1** Streams through the file once; doesn't hold the whole file in memory.

## Design notes

- Built on PRD-03's reader and PRD-04's writer.
- Documents produced by `SplitAfp` and `SplitPdf` use the same `Document` record, so every later
  step is format-agnostic. Add a `format` property (`afp` / `pdf`).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | One document per page group, with TLE properties and correct `pageCount` | `SplitAfpStepTest.oneDocumentPerPageGroup`, `tlesBecomeDocumentProperties`, `pageCountCountsBpgWithinGroup` |
| AC-2 | A missing required TLE marks the document instead of failing | `groupMissingRequiredTleIsMarkedNotFatal` |
| AC-3 | Inline resources are copied into each document | `inlineResourceGroupIsCopiedIntoEveryDocument` |
| AC-4 | Bad files fail clearly | `fileWithNoPageGroupsFailsWithClearMessage`, `unbalancedGroupFailsWithOffset` |
| AC-5 | Every output document reads back cleanly | `SplitAfpOutputTest.everySplitDocumentReadsBackCleanly` |
| AC-6 | AFP and PDF workflows give the same index for the same seed; missing TLE → exceptions | `AfpStatementWorkflowTest.*` |

## Test plan

- Unit — `SplitAfpStepTest`: `oneDocumentPerPageGroup`, `tlesBecomeDocumentProperties`,
  `pageCountCountsBpgWithinGroup`, `groupMissingRequiredTleIsMarkedNotFatal`,
  `inlineResourceGroupIsCopiedIntoEveryDocument`, `fileWithNoPageGroupsFailsWithClearMessage`,
  `unbalancedGroupFailsWithOffset`
- Unit — `SplitAfpOutputTest.everySplitDocumentReadsBackCleanly`
- Workflow — `AfpStatementWorkflowTest`: `afpAndPdfWorkflowsProduceSameIndexForSameSeed`,
  `missingTleRoutesToExceptions`

## Risks and open questions

- TLE names in real files vary (`Account Number`, `ACCT_NO`). Consider an optional `rename:` map
  in the step params; leave it out unless a sample file needs it.

## Definition of done

- Shared DoD, plus: README roadmap's AFP item ticked; the demo can run with AFP input.
