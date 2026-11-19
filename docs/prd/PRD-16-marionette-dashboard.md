# PRD-16 — Marionette.js v4 dashboard: jobs, documents, search

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Dashboard | L | 14 | `feature/16-dashboard` |

## Summary

The operator's web view, grown from the scaffold's shell. It has a live jobs list, job detail
with step timeline and documents by queue, document search, and a structure viewer for AFP and
PDF files. Built with **Marionette.js v4 (`backbone.marionette` 4.x) with Backbone and jQuery,
served by Tomcat as a WAR**, with no JavaScript build step.

## Why

Production print operators work from a web console: watch jobs move through steps, find a
document, and see what went wrong. The dashboard is also the most visible part of the demo, and
the place the Java and JavaScript skills show up together.

## Users and scenarios

- **Operator** keeps the jobs list open; it refreshes every 5 seconds, and a job turns red when
  it fails.
- **Operator** opens a job, switches between `standard`, `large-envelope` and `exceptions`, and
  opens a document's file.
- **Operator** searches by account number, name or zip, and finds a statement across all jobs.
- **Developer / interviewer** opens a document's structure view and walks through AFP page
  groups and TLEs, or a PDF's object tree.

## Goals

- `DashboardApp` + `ShellView` (header, nav, main) + `DashboardRouter` (hash routes).
- Entities, controllers and views listed in the design notes.
- Auto-refresh with request aborting, plus timer teardown.
- Escaped templates everywhere.
- Mocha + jsdom tests for every view and controller.

## Non-goals

- Write actions (PRD-17), authentication, live push (WebSockets), mobile-first design (it must
  still work on narrow screens).
- Upgrading Marionette, or adding a bundler or transpiler.

## Requirements

**Functional**
- **FR-1** Routes: `#jobs` (default and unknown routes), `#jobs/:id`,
  `#search?property=…&value=…&offset=…`, `#documents/:id/structure`. Back and forward buttons work.
- **FR-2** The jobs list shows a state badge and the current step for each job, and an empty
  state when there are none. Selecting a row navigates to its detail.
- **FR-3** The jobs list refreshes every 5 s. Starting a refresh aborts the previous in-flight
  request, and the selected job stays selected.
- **FR-4** Leaving the jobs feature stops polling, with no requests after teardown.
- **FR-5** Job detail shows properties, a step timeline, and documents grouped by queue.
  Switching queue filters the existing views without refetching.
- **FR-6** A failed load shows an error with **Retry**.
- **FR-7** Search encodes query values. A new search aborts the previous request. An empty result
  shows a message.
- **FR-7a** Search results come a page at a time (`limit` 50, PRD-14 FR-4). The view shows
  "Showing 1–50 of N" from the `X-Total-Count` header, with **Previous** and **Next** buttons,
  disabled on the first and last page. The page's `offset` is in the URL, so back and forward
  buttons and a bookmarked link return to the same page.
- **FR-8** The structure view renders nested AFP groups and PDF objects from the API, and nodes
  collapse and expand.
- **FR-9** Every data value renders as text; no `<%=` anywhere in `src/main/webapp/js/`.

**Non-functional**
- **NFR-1** Uses only Marionette 4's public API, as documented in the v4.1.3 docs.
- **NFR-2** Library versions stay pinned in `pom.xml`, `index.html` and `package.json`, enforced
  by `versions.test.mjs`.
- **NFR-3** Usable with the keyboard; focus is sensible after navigation and refresh.

## Design notes

- **Application:** `DashboardApp` (`region: '#app'`, `channelName: 'dashboard'`) starts
  `Backbone.history`; route handlers call app methods, and the app shows the right View in
  `main`.
- **Entities** (`js/entities/`): `Job` / `JobCollection` (`url: 'api/jobs'`), `Document` /
  `DocumentCollection`, `StructureNode`.
- **Controllers** (`Marionette.MnObject`): `JobsController` (polling, abort, `onDestroy` clears
  timer), `SearchController`, `StructureController`.
- **Views** (`js/views/`):
  - `JobsListView`: a `CollectionView` of `JobRowView` with an `emptyView`, using
    `childViewTriggers`.
  - `JobDetailView`: regions for properties, the step timeline and documents; `DocumentsView`
    switches queues with `setFilter`.
  - `SearchView`: `ui` hash plus `triggers: { 'submit form': 'search:submit' }`.
  - `StructureView`: a recursive `CollectionView`.
- Views only render and emit intent; no View references another View or a controller.
- Tests use `Backbone.ajax` stubs (no network) and Sinon fake timers for polling. Add `sinon` as
  a dev dependency in this PR.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Entities build URLs correctly and parse the API contract fixture | `entities.test.mjs` |
| AC-2 | Jobs list renders, shows the empty state, navigates, refreshes safely, and stops polling on teardown | `jobs.test.mjs` |
| AC-3 | Queue switching filters without refetch; failed load offers Retry | `job-detail.test.mjs` |
| AC-4 | Search aborts stale requests, encodes values, handles empty results, and pages with Previous / Next | `search.test.mjs` |
| AC-5 | Structure view nests and collapses | `structure.test.mjs` |
| AC-6 | Each route shows its view; unknown routes show jobs | `router.test.mjs` |
| AC-7 | Values render as text in every view; no `<%=` in source | `security.test.mjs` |

## Test plan

Mocha specs in jsdom (`npm test`), `Backbone.ajax` stubbed, no network and no sleeps:

- `entities.test.mjs`: `jobCollectionUrlIncludesStateAndLimit`,
  `parsesFixtureJobJsonIntoModels`
- `jobs.test.mjs`: `listRendersOneRowPerJobWithStateBadge`, `emptyListShowsEmptyView`,
  `selectingARowNavigatesToJobDetail`, `refreshAbortsPreviousRequest`,
  `refreshKeepsSelectedJob`, `pollingStopsWhenControllerIsDestroyed`
- `job-detail.test.mjs`: `switchingQueueFiltersDocumentsWithoutRefetch`,
  `failedLoadShowsErrorWithRetry`
- `search.test.mjs`: `newSearchAbortsPreviousRequest`, `encodesQueryValues`,
  `emptyResultShowsMessage`, `showsRangeAndTotalFromHeader`, `nextAndPreviousChangeOffset`,
  `pagingButtonsDisabledAtEnds`, `offsetInUrlRestoresPage`
- `structure.test.mjs`: `rendersNestedAfpGroupsAndPdfObjects`, `collapsingANodeHidesItsChildren`
- `router.test.mjs`: `eachRouteShowsItsView`, `unknownRouteShowsJobs`
- `security.test.mjs`: `rendersValuesAsTextNotHtml` (every view),
  `noUnescapedTemplateTagsInSource`
- Manual, against `./mvnw package cargo:run`: list refreshes, job detail, queue filter, search,
  open file, structure view, back/forward, narrow screen.

## Risks and open questions

- Size **L**: split into (a) router + jobs list + detail and (b) search + structure view if
  needed.
- Five-second polling is fine for a demo; note in the README that production would use push or
  longer intervals.

## Definition of done

- Shared DoD, plus: screenshots of the jobs list, a job detail and the structure view in the PR,
  and the README roadmap's dashboard item ticked.
