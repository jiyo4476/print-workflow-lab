# PRD-15 — Reprint and retry

| Phase | Size | Depends on | Branch |
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
  runs the `reprint` workflow, and each page carries a "REPRINT" marker.
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
- **FR-4** An empty id list is a 400; a successful reprint is a 201 with the child job id.
- **FR-5** Retry resumes from the failed step, using the state saved after the last successful
  step. Earlier steps don't run again.
- **FR-6** Retrying a completed job is rejected; retrying an unknown job is a 404.
- **FR-7** Two retry requests for the same job never run it twice (idempotent).
- **FR-8** Both actions add entries to `job_steps`.

**Non-functional**
- **NFR-1** Actions are safe to double-submit from the dashboard.

## Design notes

- `ReprintService` and `RetryService` hold the logic; servlets only parse and respond.
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

## Test plan

- Unit — `ReprintServiceTest`: `createsChildJobWithOnlySelectedDocuments`,
  `childJobRecordsParentId`, `originalJobIsUnchanged`,
  `documentsFromDifferentJobsCanBeReprintedTogether` (or rejected, see open question),
  `unknownDocumentIdFailsWholeRequest`
- Unit — `RetryServiceTest`: `retriesFromFailedStepNotFromStart`,
  `retryingCompletedJobIsRejected`, `retryIsRecordedInStepHistory`
- Integration — `ActionsApiTest`: `reprintReturns201WithChildJobId`,
  `reprintWithEmptyListReturns400`, `retryUnknownJobReturns404`,
  `doubleSubmitRetryDoesNotRunTwice`
- Workflow — `ReprintWorkflowTest.reprintedPdfHasReprintMarkerOnEveryPage`

## Risks and open questions

- **Open:** can one reprint combine documents from different jobs? Allowing it fits real use
  (a damaged inserter tray mixes jobs) but makes "parent job" plural. Decide in the PR, and test
  whichever is chosen.
- **Open:** AFP reprints. Is the "REPRINT" marker added as a PTOCA text overlay, or omitted for
  AFP? Decide and document it.

## Definition of done

- Shared DoD, plus: README documents both endpoints.
