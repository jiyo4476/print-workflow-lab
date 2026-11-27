# PRD-15 — Reprint and retry

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| Platform | M | 14 | `feature/15-reprint-retry` |

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
  PDF, and as a text object added to each page for AFP (an overlay comes later, in PRD-21).
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
- **FR-10** Every page gets one new presentation-text object holding "REPRINT", added after the
  page's existing objects and just before `EPG`:
  ```
  BPG
    BAG … EAG            unchanged (an MCF is added only if the page maps no font, FR-11)
    …                    the page's existing objects, unchanged
    BPT                  new
      PTX                AMI/AMB to the mark's position, SCFL to the font, TRN "REPRINT"
    EPT                  new
  EPG
  ```
  The existing text objects are never edited; the mark is a separate object.
- **FR-11** The mark uses a font the page already maps in its `MCF`. If the page maps no font,
  the step adds an `MCF` entry for the generator's font (`T1V10037` / `C0D0GT10`) under an unused
  local ID.
- **FR-12** The mark's position is a step param in inches (default: top margin, right),
  converted to the page's own units from its `PGD`, so files at 240 or 1440 units per inch both
  place it correctly.
- **FR-13** The rewrite streams through the document once, field by field. Fields other than the
  inserted ones are copied byte for byte. Reprinting a document that already has the mark doesn't
  add a second one.

**Non-functional**
- **NFR-1** Actions are safe to double-submit from the dashboard. Retry is guarded (FR-7). Reprint
  is not yet: a repeat request creates a second child job. Fixed in plan PR 17 by an idempotency
  key on `POST /api/documents/reprint` ([PR_PLAN](../PR_PLAN.md), PR 17).

## Design notes

- `ReprintService` and `RetryService` hold the logic; servlets only parse and respond.
- **AFP building blocks this PR adds:**
  - `afp/PageBuilder`: the `MCF`, `PGD`, `PTD` and PTOCA text encoders, moved out of
    `StatementAfpGenerator` (where they're private today) so the generator and the reprint mark
    share them. PRD-21's overlay reuses them too.
  - `afp/AfpRewriter`: reads structured fields with `AfpReader`, lets a transformer insert
    fields before or after any field, and writes with `AfpWriter`. PRD-21 reuses it.
  - A `PGD` decoder, to read each page's units and size.
- **Why a text object per page now, and an overlay later:** a separate text object is the
  smallest change that gives a visible mark, and it builds the rewriter and PTOCA helpers that
  an overlay needs anyway. Production systems usually mark pages with an **overlay** instead: the
  mark is defined once as a resource and referenced from every page. That is planned as
  [PRD-21](PRD-21-afp-reprint-overlay.md).
- Retry needs each step's output to be reusable from the work dir. Document which steps support
  this, and record it per step.
- **The input file is in the error folder.** When a job fails, `ErrorFolder` moves its input
  from `work/<jobId>/` to `error/<jobId>/`. Retry moves it back to `job.inputFile()` before
  resuming, and refuses with a clear message if it's no longer in either place (an operator
  removed it). Once the job is retried, `error/<jobId>/` is removed, so the folder lists only
  jobs still in error.
- `WriteOutput` replaces the job's own `<outputDir>/<jobId>/` when it runs again, so retrying a
  job that failed part way through writing output doesn't trip over its earlier files.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | The child job has only the selected documents and records its parent; the original is untouched | `ActionsApiTest.reprintMakesAChildJobWithOnlyTheSelectedDocuments`, `reprintLeavesTheOriginalJobUnchanged` |
| AC-2 | Unknown ids reject the whole request | `ActionsApiTest.reprintWithAnUnknownIdFailsTheWholeRequest` |
| AC-3 | Retry resumes at the failed step and is recorded; completed jobs are rejected | `WorkflowEngineTest.resume*`, `ActionsApiTest.retry*`, `WorkflowContextListenerTest.failedJobIsRetriedThroughTheApiOnTheDeviceThread` |
| AC-4 | HTTP status codes and idempotency | `ActionsApiTest.*` (retry double submit: `doubleSubmitRetryDoesNotRunTwice`; reprint double submit: plan PR 17) |
| AC-5 | Reprinted PDFs show the marker on every page | `ReprintWorkflowTest.reprintedPdfHasReprintMarkerOnEveryPage`, `MarkReprintStepTest` |
| AC-6 | Reprinted AFP has the `Reprint` TLE and one "REPRINT" text object on every page, positioned in the page's units | `AfpReprintMarkTest.reprintedAfpHasReprintTleOnEveryDocument`, `everyPageGetsOneReprintTextObject`, `markPositionUsesPageUnitsFromPgd` |
| AC-7 | Reprinted AFP reads back cleanly and its original page content is unchanged | `AfpReprintMarkTest.reprintedAfpReadsBackCleanly`, `originalPageFieldsAreUnchanged` |
| AC-8 | The mark shows on the page in a real viewer | Manual: Papyrus AFP Viewer screenshot in the PR. **Open:** the viewer lacked the fonts the files name, so the text was not legible |

## Test plan

As built. Names that differ from the first draft of this plan are noted.

- Unit: `WorkflowEngineTest` (in the workflow package): `resumeRunsFromTheNamedStepOnly`,
  `resumeKeepsDocumentsTheCompletedStepsMade`, `failedStepLeavesDocumentsAndPropertiesAsTheLastCompletedStepDid`,
  `resumeRefusesAStepThatIsNotResumable`, `resumeRefusesAStepTheWorkflowDoesNotHave`
- HTTP: `ActionsApiTest` covers both services with a fake queue, in place of `ReprintServiceTest`
  and `RetryServiceTest` (no separate service classes are unit-tested):
  - reprint: `reprintMakesAChildJobWithOnlyTheSelectedDocuments`, `reprintLeavesTheOriginalJobUnchanged`,
    `reprintWithAnUnknownIdFailsTheWholeRequest`, `reprintAcrossJobsIsRefusedAndNamesTheJobs`,
    `reprintWithNoDocumentsOrBadBodyIs400`, `reprintOfADocumentWithoutAFileIs409`,
    `reprintThatCannotBeQueuedLeavesNoChildJob`
  - retry: `retryOfUnknownJobReturns404`, `retryOfCompletedJobIsRejected`,
    `retryRunsFromTheFailedStepAndRecordsIt`, `retryPassesTheDeviceToTheRunner`,
    `doubleSubmitRetryDoesNotRunTwice`, `retryMovesTheInputBackFromTheErrorFolder`,
    `retryWithTheInputGoneIsRefused`, `retryFromAStepThatCannotRunAgainIsRefusedAndMovesNothing`
  - not yet: a reprint double-submit test (plan PR 17)
- Device thread: `WorkflowContextListenerTest.failedJobIsRetriedThroughTheApiOnTheDeviceThread`,
  `reprintRunsThroughTheReprintWorkflowOnTheDeviceThread`, `reprintIsUnavailableWithoutAReprintWorkflow`
- `InputDevicesTest.retryOnAnUnknownDeviceIsRefusedBeforeAnythingRuns`
- `WriteOutputStepTest.rerunReplacesAnEarlierRunsPartialOutput`
- Workflow: `ReprintWorkflowTest.reprintedPdfHasReprintMarkerOnEveryPage`,
  `ReprintWorkflowTest.reprintedAfpHasReprintTleAndMarkOnEveryPage`
- Unit: `MarkReprintStepTest` (PDF and AFP marks, rerun, original not written over, bad input refused)
- Unit: `AfpReprintMarkTest` (in place of `ReprintAfpTest`): `reprintedAfpHasReprintTleOnEveryDocument`,
  `everyPageGetsOneReprintTextObject`, `theMarkSitsInItsOwnObjectBeforeEachPagesEpg`,
  `markPositionUsesPageUnitsFromPgd`, `pageWithoutFontMappingGetsMcfEntry`,
  `reprintedAfpReadsBackCleanly`, `originalPageFieldsAreUnchanged`, `documentWithoutAPageGroupIsRefused`.
  Reprinting twice is covered at the step level (`MarkReprintStepTest.markingAgainDoesNotStampTheDocumentTwice`,
  `markingAfpAgainDoesNotMarkItTwice`); not in `AfpReprintMarkTest`.
- Unit: `PageBuilderTest` (descriptors, unit bases, font map, PTOCA). The generator's output is
  unchanged after the move, checked by the existing `StatementAfpGeneratorTest` and the AFP workflow
  parity tests, in place of a new `generatorOutputIsUnchangedAfterExtraction`. There is no separate
  `AfpRewriter`: `AfpReprintMark` streams the document itself.
- Manual: open a reprinted AFP document in Papyrus AFP Viewer and confirm "REPRINT" shows, with the
  viewer's fonts loaded (AC-8).

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-06, Q-07). Record the answers there and here.

- **Decided (Q-06):** one source job per reprint (FR-3a), so each child job has one parent.
- **Follow-up to review:** extending reprint across jobs, since one inserter jam can damage mail
  from several jobs. The first option to evaluate is splitting a mixed request automatically into
  one child job per source job (Q-06 option C): it keeps one parent per job and needs no schema
  change, but the API returns several child ids. Record the review in `OPEN_QUESTIONS.md`.
- **Decided (Q-07):** AFP reprints get a `Reprint` TLE plus a separate "REPRINT" text object on
  each page (FR-9 to FR-13). Marking with a page overlay is the planned follow-up,
  [PRD-21](PRD-21-afp-reprint-overlay.md).
- Viewers substitute fonts they don't have; if the mark renders in an unexpected font, record
  what the viewer shows. The position and content are what matter.

## Definition of done

- Shared DoD, plus: README documents both endpoints.
