# PRD-15 — Reprint and retry

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Platform | L | 14 | `feature/15-reprint-retry` |

## Summary

Two operator actions every production print system needs: **reprint** selected documents as a
new child job, and **retry** a failed job from the step that failed. Both go through the API and
are recorded in the job history.

## Why

Paper jams, damaged envelopes and customer requests mean individual statements must be reprinted
without rerunning the whole print file. Failed jobs need restarting after the cause is fixed,
without repeating work that already succeeded. Being able to reprint one document from a large
run is the headline of the demo.

## Users and scenarios

- **Operator** selects three statements damaged in the inserter and reprints them. A child job
  runs the `reprint` workflow, and each page carries a "REPRINT" marker: drawn on the page for
  PDF, and an overlay for AFP.
- **Operator** fixes a full disk, then retries a job that failed in `write-output`. Split and sort
  are not rerun.

## Goals

- `POST /api/documents/reprint` creates a child job from selected documents.
- `POST /api/jobs/{id}/retry` resumes from the failed step.
- A `reprint` workflow, and an audit trail in `job_steps`.

## Non-goals

- Partial-page reprints, reprint quantities, or sending to a specific printer.
- Automatic retries or scheduling.
- Undoing a reprint.

## Requirements

**Functional**
- **FR-1** Reprint takes document ids and creates a new job with exactly those documents, run
  through `workflows/reprint.yaml`, which adds a "REPRINT" marker and skips presort.
- **FR-2** The child job records its parent job id; the original job is unchanged.
- **FR-3** If any id is unknown, the whole request fails; nothing is partially reprinted.
- **FR-3a** All documents in a request must come from one job. A request mixing jobs is a 400
  whose message names the jobs involved, and nothing is reprinted
  ([Q-06](../OPEN_QUESTIONS.md#q-06--reprint-across-jobs)).
- **FR-4** An empty id list is a 400; a successful reprint is a 201 with the child job id.
- **FR-5** Retry resumes from the failed step, using the state saved after the last successful
  step. Earlier steps don't run again.
- **FR-6** Retrying a completed job is rejected; retrying an unknown job is a 404.
- **FR-7** Two retry requests for the same job never run it twice (idempotent).
- **FR-8** Both actions add entries to `job_steps`.

**AFP reprint marking** ([Q-07](../OPEN_QUESTIONS.md#q-07--marking-afp-reprints))
- **FR-9** Every reprinted AFP document gets a `Reprint` TLE in its page group (value: the parent
  job id), so inserters and archives can tell it's a reprint.
- **FR-10** The document's inline resource group holds one `REPRINT` page overlay:
  ```
  BRG                      created at the front of the file if the document has none
    BRS REPRINT            Resource Object Type triplet (X'21'): overlay
      BMO REPRINT
        BAG
          MCF              same font as the pages
          PGD              the overlay's own size, e.g. 2 x 0.5 inches
          PTD
        EAG
        BPT
          PTX              "REPRINT"
        EPT
      EMO
    ERS
  ERG
  ```
  If the document already has a resource group, the overlay is added to it rather than in a
  second group. Reprinting a document that already has the overlay doesn't add another.
- **FR-11** Every page maps the overlay with an `MPO` (Map Page Overlay) inside its `BAG … EAG`,
  and places it with an `IPO` (Include Page Overlay) after `EAG`, at a configurable position
  (default: top margin, right).
- **FR-12** The rewrite streams through the document once, field by field. Fields other than the
  inserted ones are copied byte for byte, so the original page content is untouched.

**Non-functional**
- **NFR-1** Actions are safe to double-submit from the dashboard.

## Design notes

- `ReprintService` and `RetryService` hold the logic; servlets only parse and respond.
- **AFP building blocks this PR adds:**
  - `afp/PageBuilder`: the `MCF`, `PGD`, `PTD` and PTOCA text encoders, moved out of
    `StatementAfpGenerator` (where they're private today) so the generator and the overlay
    share them.
  - `afp/OverlayWriter`: writes a named overlay resource (FR-10).
  - `MPO` and `IPO` encoders, and decoding of both in `dump-afp`, so the dump shows the
    overlay name and position.
  - `afp/AfpRewriter`: reads structured fields with `AfpReader`, lets a transformer insert
    fields before or after any field, and writes with `AfpWriter`. Option C (a text object per
    page) would have needed this too.
- **Why an overlay, not text on each page:** the mark is defined once in the resource group and
  referenced by every page, which is how production systems add marks. A **medium overlay**,
  applied to every sheet by the form definition (`BFM`/`BMM`) with no change to the page data, is
  the other common approach. It's out of scope here, but worth explaining in the interview.
- Retry needs each step's output to be reusable from the work dir. Document which steps support
  this, and record it per step.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | The child job has only the selected documents and records its parent; the original is untouched | `ReprintServiceTest.*` |
| AC-2 | Unknown ids reject the whole request | `unknownDocumentIdFailsWholeRequest` |
| AC-3 | Retry resumes at the failed step and is recorded; completed jobs are rejected | `RetryServiceTest.*` |
| AC-4 | HTTP status codes and idempotency | `ActionsApiTest.*` |
| AC-5 | Reprinted PDFs show the marker on every page | `ReprintWorkflowTest.reprintedPdfHasReprintMarkerOnEveryPage` |
| AC-6 | Reprinted AFP has the `Reprint` TLE, one inline `REPRINT` overlay, and `MPO` + `IPO` on every page | `ReprintAfpTest.*` |
| AC-7 | Reprinted AFP reads back cleanly and its original page content is unchanged | `ReprintAfpTest.reprintedAfpReadsBackCleanly`, `originalPageFieldsAreUnchanged` |
| AC-8 | The overlay shows on the page in a real viewer | Manual: Papyrus AFP Viewer screenshot in the PR |

## Test plan

- Unit — `ReprintServiceTest`: `createsChildJobWithOnlySelectedDocuments`,
  `childJobRecordsParentId`, `originalJobIsUnchanged`,
  `documentsFromDifferentJobsAreRejected`,
  `unknownDocumentIdFailsWholeRequest`
- Unit — `RetryServiceTest`: `retriesFromFailedStepNotFromStart`,
  `retryingCompletedJobIsRejected`, `retryIsRecordedInStepHistory`
- Integration — `ActionsApiTest`: `reprintReturns201WithChildJobId`,
  `reprintWithEmptyListReturns400`, `retryUnknownJobReturns404`,
  `doubleSubmitRetryDoesNotRunTwice`
- Workflow — `ReprintWorkflowTest.reprintedPdfHasReprintMarkerOnEveryPage`
- Unit — `ReprintAfpTest`: `reprintedAfpHasReprintTleOnEveryDocument`,
  `overlayResourceIsInlineOnce`, `existingResourceGroupGetsOverlayAdded`,
  `everyPageMapsAndIncludesTheOverlay`, `reprintingTwiceDoesNotDuplicateTheOverlay`,
  `reprintedAfpReadsBackCleanly`, `originalPageFieldsAreUnchanged`
- Unit — `AfpRewriterTest`: `copiesUnchangedFieldsByteForByte`, `insertsBeforeAndAfterAField`
- Unit — `PageBuilderTest`: `generatorOutputIsUnchangedAfterExtraction` (same seed, same bytes as
  before the refactor)
- Unit — `DumpAfpTest.dumpShowsOverlayNameAndPositionForMpoAndIpo`
- Manual: open a reprinted AFP document in Papyrus AFP Viewer and confirm the overlay shows.

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-06, Q-07). Record the answers there and here.

- **Decided (Q-06):** one source job per reprint (FR-3a), so each child job has one parent.
- **Follow-up to review:** extending reprint across jobs, since one inserter jam can damage mail
  from several jobs. The first option to evaluate is splitting a mixed request automatically into
  one child job per source job (Q-06 option C): it keeps one parent per job and needs no schema
  change, but the API returns several child ids. Record the review in `OPEN_QUESTIONS.md`.
- **Decided (Q-07):** AFP reprints get an inline page overlay plus a `Reprint` TLE (FR-9 to
  FR-12). This makes the PR larger (L).
- Check the Resource Object Type triplet's code for an overlay (expected X'FC') and the `MPO` and
  `IPO` layouts against the MO:DCA reference before relying on them.
- Viewers handle inline overlays differently; the manual viewer check (AC-8) is the real test.
  If Papyrus doesn't render it, record what it shows and try a second viewer.

## Definition of done

- Shared DoD, plus: README documents both endpoints.
