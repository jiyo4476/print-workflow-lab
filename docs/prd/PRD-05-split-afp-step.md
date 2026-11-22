# PRD-05 — `SplitAfp` step: TLEs become document properties, plus property mapping

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| AFP | M | 04 | `feature/05-split-afp` |

## Summary

A workflow step that splits an AFP print file on its named page groups, writes each group as a
standalone AFP document, and turns the group's TLEs into document properties. It's the AFP
counterpart of `SplitPdf`, with no guessing from text positions.

It also adds a reusable `MapProperties` step that renames document properties, so files whose
TLEs use other names (`ACCT_NO` instead of `accountNumber`) work with the same routing, sorting
and output steps ([Q-04](../OPEN_QUESTIONS.md#q-04--tle-name-mapping)).

## Why

This is the core AFP indexing pattern in production print: page groups mark document
boundaries, TLEs carry the index values, and those values drive sorting, routing, inserting and
reprints. Compared with region-based PDF splitting, it shows why AFP's built-in index makes
document identification reliable.

Different applications name the same index value differently. Mapping their names onto one set
of document properties is everyday work in ProcessDirector, and a separate step does it for AFP
and PDF jobs alike.

## Users and scenarios

- **Workflow designer** points `workflows/statements-afp.yaml` at a folder; each statement comes
  out with `accountNumber`, `name`, `zip` and `pageCount` properties.
- **Operator** finds a statement with a missing `zip` TLE in the exceptions queue, while the rest
  of the job completes.
- **Workflow designer** receives AFP from a second application whose TLEs are `ACCT_NO`,
  `CUST_NAME` and `ZIP_CODE`. A `MapProperties` step after the split renames them, and the rest
  of the workflow stays the same.

## Goals

- Split on BNG/ENG; carry BDT/EDT and any inline resource group into every output document.
- TLEs become document properties; add `pageCount`.
- `requiredProperties` marks incomplete groups (`indexError`) instead of failing the job.
- A sample AFP workflow mirroring the PDF one.
- A reusable `MapProperties` step that renames document properties, for any input format.
- The test-data generator can write production-style TLE names, so the mapping runs end to end.

## Non-goals

- Converting AFP to PDF, or rendering pages.
- Resolving external resources (fonts, overlays from a resource library).
- Nested page groups beyond one level.
- Changing property values in `MapProperties` (trimming, reformatting, combining or splitting
  values). It only renames.

## Requirements

**Functional**
- **FR-1** One document per top-level BNG/ENG group, written as a valid AFP file.
- **FR-2** The file's BDT/EDT wrap each output; an inline resource group (BRG/ERG) is copied into
  every output.
- **FR-2a** An Invoke Medium Map (IMM) stays in effect until the next one. A document without its
  own IMM just before it gets a copy of the latest one seen after the preamble (between groups,
  or between an earlier group's pages), so it prints with the right medium map.
- **FR-3** Every TLE in a group becomes a document property; `pageCount` = number of BPGs in the
  group.
- **FR-4** A group missing a `requiredProperties` entry gets `indexError=<missing names>` and
  stays in the job; PRD-02 rules route it.
- **FR-5** A file with no page groups fails the job with a clear message.
- **FR-6** Unbalanced Begin/End fails with the byte offset.
- **FR-7** `workflows/statements-afp.yaml` mirrors `statements.yaml` (split, route, presort,
  write).
- **FR-8** A `MapProperties` step renames document properties with a `rename:` map from the old
  name to the new one:
  ```yaml
  - name: map-properties
    type: MapProperties
    params:
      rename:
        ACCT_NO: accountNumber
        CUST_NAME: name
        ZIP_CODE: zip
      requiredProperties: [accountNumber, zip]
  ```
  - Names match exactly, including case. Properties not listed are kept unchanged.
  - All renames are applied together, from the properties as they were before the step, so the
    order in the map doesn't matter.
  - A name listed in the map but missing from a document is skipped.
  - If a document already has a different value under the new name, the document gets
    `indexError=rename conflict: <old> -> <new>` and keeps both properties as they were. The job
    continues.
  - The optional `requiredProperties` is checked after renaming, and works like FR-4.
  - A map that sends two old names to the same new name fails when the workflow loads, before
    any job runs.
  - Mapping is fully configurable: every old and new name comes from the workflow YAML, the map
    can hold any number of entries, and no TLE or property name is hardcoded in `SplitAfp` or
    `MapProperties`. Each input device's workflow (PRD-06) can carry its own map.
- **FR-9** `App generate-afp` takes `--tle-names legacy`, which writes the TLEs as `ACCT_NO`,
  `CUST_NAME` and `ZIP_CODE` instead of the property names. The default stays as today.
- **FR-10** `workflows/statements-afp-legacy.yaml` is the AFP workflow with a `MapProperties`
  step after the split, for files generated with `--tle-names legacy`.

**Non-functional**
- **NFR-1** Streams through the file once; doesn't hold the whole file in memory.

## Design notes

- Built on PRD-03's reader and PRD-04's writer.
- Documents produced by `SplitAfp` and `SplitPdf` use the same `Document` record, so every later
  step is format-agnostic. Add a `format` property (`afp` / `pdf`).
- **Why a separate step, not a `rename:` option on `SplitAfp`:** one step serves AFP and PDF
  jobs, and keeps the split step about reading the file. This is how ProcessDirector separates
  identifying documents from mapping their values to document properties.
- `MapProperties` changes only document properties, never the document files. The AFP TLEs keep
  their original names inside the file.
- In workflows that rename, put `requiredProperties` on `MapProperties`, so it checks the new
  names. `requiredProperties` on `SplitAfp` checks the TLE names as they are in the file.

**As built (PR 5):**
- Each output is: every field before the first BNG (the BDT, inline resource groups), then any
  fields between the previous ENG and this BNG (an IMM, a NOP), then the group, then an End for
  each Begin still open around it, named like its Begin. Fields after the last group are not
  copied. One document (BDT ... EDT) per file: content after the EDT fails the job.
- Fields are copied through `AfpWriter.write(StructuredField)`: type, flags, extension and data
  are kept, padding is dropped (the reader strips it), reserved bytes are written as `0000`.
- Only TLEs directly inside the top-level group become properties; TLEs inside pages or nested
  groups are ignored. `pageCount` counts every BPG in the group, nested groups included.
- "Missing" means absent or blank, the same test as the `missing` rule operator, so an empty
  `name` TLE is missing. `indexError` values are appended with `; ` (`missing: name, zip`,
  `rename conflict: ACCT_NO -> accountNumber`), so an earlier step's mark is kept.
- A TLE repeated in a group with a different value keeps the first value and marks
  `indexError` (`TLE 'name' repeated with another value`).
- The job fails, with the byte offset, for: bytes that aren't AFP (`AfpFormatException`), an End
  that doesn't close the innermost Begin, a Begin never closed, a BPG outside any group, and a
  TLE that can't be decoded. An optional `codePage` param (a Java charset name, default IBM037)
  decodes TLE text.
- In `MapProperties`, the same value under the old and the new name is not a conflict (the old
  name is dropped). A conflicting rename stays put, and a rename onto its old name is then
  checked against it too.
- Extra tests beyond the plan: `SplitAfpStepTest` (`repeatedTleWithAnotherValueKeepsTheFirstAndIsMarked`,
  `closingEndsAreNamedLikeTheirBegins`, `unclosedGroupAtEndOfFileFailsWithOffset`,
  `endWithNoBeginFails`, `pageOutsideAnyGroupFails`, `contentAfterTheDocumentEndsFails`,
  `undecodableTleFailsWithOffset`, `bytesThatAreNotAfpFailWithOffset`,
  `unknownCodePageFailsAtLoad`), `MapPropertiesStepTest` (`sameValueUnderBothNamesIsNoConflict`,
  `conflictThatBlocksAnotherRenameMarksBoth`, `indexErrorFromAnEarlierStepIsKept`,
  `badRenameParamsFailAtLoad`), and `AfpWriterTest.copiedFieldKeepsItsExtensionAndDropsPadding`.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | One document per page group, with TLE properties and correct `pageCount` | `SplitAfpStepTest.oneDocumentPerPageGroup`, `tlesBecomeDocumentProperties`, `pageCountCountsBpgWithinGroup` |
| AC-2 | A missing required TLE marks the document instead of failing | `groupMissingRequiredTleIsMarkedNotFatal` |
| AC-3 | Inline resources are copied into each document, and each document carries the medium map in effect | `inlineResourceGroupIsCopiedIntoEveryDocument`, `laterImmAppliesToEveryFollowingDocument`, `immBetweenAGroupsPagesCarriesIntoTheNextDocument` |
| AC-4 | Bad files fail clearly | `fileWithNoPageGroupsFailsWithClearMessage`, `unbalancedGroupFailsWithOffset` |
| AC-5 | Every output document reads back cleanly | `SplitAfpOutputTest.everySplitDocumentReadsBackCleanly` |
| AC-6 | AFP and PDF workflows give the same index for the same seed; missing TLE → exceptions | `AfpStatementWorkflowTest.*` |
| AC-7 | Renaming works as FR-8 describes, including conflicts and required properties | `MapPropertiesStepTest.*` |
| AC-8 | A file with legacy TLE names gives the same index as one with property names | `AfpStatementWorkflowTest.legacyTleNamesGiveSameIndexAfterMapping` |

## Test plan

- Unit — `SplitAfpStepTest`: `oneDocumentPerPageGroup`, `tlesBecomeDocumentProperties`,
  `pageCountCountsBpgWithinGroup`, `groupMissingRequiredTleIsMarkedNotFatal`,
  `inlineResourceGroupIsCopiedIntoEveryDocument`, `laterImmAppliesToEveryFollowingDocument`,
  `immBetweenAGroupsPagesCarriesIntoTheNextDocument`, `fileWithNoPageGroupsFailsWithClearMessage`,
  `unbalancedGroupFailsWithOffset`
- Unit — `SplitAfpOutputTest.everySplitDocumentReadsBackCleanly`
- Unit — `MapPropertiesStepTest`: `renamesListedProperties`, `keepsUnlistedProperties`,
  `missingOldNameIsSkipped`, `conflictingValueMarksIndexErrorAndKeepsBoth`,
  `requiredPropertiesAreCheckedAfterRenaming`, `renamesApplyTogetherSoOrderDoesNotMatter`,
  `twoOldNamesToOneNewNameFailsAtLoad`, `mapsArbitraryNamesFromWorkflowConfig`
- Unit — `StatementAfpGeneratorTest.legacyTleNamesOptionWritesProductionStyleNames`
- Workflow — `AfpStatementWorkflowTest`: `afpAndPdfWorkflowsProduceSameIndexForSameSeed`,
  `missingTleRoutesToExceptions`, `legacyTleNamesGiveSameIndexAfterMapping`

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-04). Record the answers there and here.

- **Decided (Q-04):** TLE names in real files vary (`Account Number`, `ACCT_NO`), so this PR
  adds renaming as a separate, reusable `MapProperties` step (FR-8 to FR-10).
- Renaming is optional and limited to names. If value transformations are ever needed, they
  belong in a later step or PR, not here.

## Definition of done

- Shared DoD, plus: README roadmap's AFP item ticked; the demo can run with AFP input, with
  property or legacy TLE names.
