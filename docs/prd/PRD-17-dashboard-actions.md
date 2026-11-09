# PRD-17 — Dashboard actions: reprint, retry, exception queue

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Dashboard | M | 15, 16 | `feature/17-dashboard-actions` |

## Summary

Add the operator actions to the Marionette.js v4 dashboard: reprint selected documents, retry a
failed job, and work through an **Exceptions** view listing exception-queue documents across all
jobs.

## Why

With this PRD the demo is complete: a bad statement lands in exceptions, the operator finds it,
reprints a good one by account number, and watches the child job finish, all from the browser.
It also shows careful UI work around side effects: confirmation, no double submits, and correct
behavior if the user leaves mid-request.

## Users and scenarios

- **Operator** opens Exceptions, sees five statements with invalid zips, and checks their
  properties.
- **Operator** searches an account, selects its statement, clicks **Reprint**, confirms, and
  follows the link to the new child job.
- **Operator** opens a failed job and clicks **Retry** once the cause is fixed.

## Goals

- Document selection, **Reprint**, **Retry** and an **Exceptions** view (`#exceptions`).
- An `ActionsController` (`MnObject`) that performs POSTs; Views only emit intent.
- Confirmation, an inline result, disabled buttons while a request is in flight, and a link to
  the child job.

## Non-goals

- Bulk actions across jobs other than reprint, editing document properties, or releasing
  documents from exceptions back into the job.
- Undo.

## Requirements

**Functional**
- **FR-1** Views trigger `reprint:documents` (with the selected ids) and `retry:job`; they never
  call the API directly.
- **FR-2** Reprint sends only the selected document ids. Retry calls the retry endpoint for the
  shown job.
- **FR-3** Both actions ask for confirmation first.
- **FR-4** Buttons are disabled while a request is in flight; a second click sends nothing.
- **FR-5** On success, the affected collection refreshes and the app navigates to the child job
  (reprint) or stays on the job with its new state (retry).
- **FR-6** API errors show a readable message inline; the page stays usable.
- **FR-7** If the user navigates away mid-request, the response doesn't touch destroyed views.
- **FR-8** The Exceptions view lists exception-queue documents across jobs, via an existing
  endpoint or a new one (see the open question).

**Non-functional**
- **NFR-1** Same Marionette 4 rules and escaped templates as PRD-16.

## Design notes

- The `ActionsController` holds the in-flight `jqXHR`; `onDestroy` aborts it, which covers
  FR-7.
- Selection state lives in a Backbone Model owned by the controller, not in the Views.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Reprint sends only the selected ids | `actions.test.mjs: reprintSendsSelectedIdsOnly` |
| AC-2 | No double submit while in flight | `buttonsDisabledWhileRequestInFlight` |
| AC-3 | API failures show a message | `showsErrorMessageWhenApiFails` |
| AC-4 | A successful reprint refreshes jobs and navigates to the child | `successfulReprintRefreshesJobsAndNavigatesToChild` |
| AC-5 | Leaving mid-request doesn't update destroyed views | `leavingThePageMidRequestDoesNotUpdateDestroyedViews` |
| AC-6 | The cross-job exceptions endpoint works (if added) | `ExceptionsApiTest` |

## Test plan

- Mocha + jsdom — `actions.test.mjs`: `reprintSendsSelectedIdsOnly`,
  `buttonsDisabledWhileRequestInFlight`, `showsErrorMessageWhenApiFails`,
  `successfulReprintRefreshesJobsAndNavigatesToChild`,
  `leavingThePageMidRequestDoesNotUpdateDestroyedViews`
- Integration — `ExceptionsApiTest` (if a new endpoint is added)
- Manual demo script in the PR:
  1. Generate a run with bad addresses.
  2. Find one in Exceptions.
  3. Reprint a good statement by account number.
  4. See the child job complete.

## Risks and open questions

- **Open:** the Exceptions view could use `GET /api/documents?property=queue&value=exceptions`
  (no new endpoint), or a dedicated `GET /api/exceptions`. Prefer the existing search endpoint
  unless it can't page or sort well enough.

## Definition of done

- Shared DoD, plus: a short screen recording or GIF of the demo flow attached to the PR.
