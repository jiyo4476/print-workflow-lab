# PRD-14 — REST API on Tomcat (read-only servlets)

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Platform | M | 13 | `feature/14-rest-api` |

## Summary

Expose jobs and documents over HTTP from the Tomcat web app using plain Jakarta servlets, and run
the workflow engine and hot folders inside Tomcat, started and stopped with the web application.

## Why

The dashboard (PRD-16), reprint (PRD-15) and the Docker smoke test (PRD-18) all need an API.
Running the engine inside the servlet container is how production workflow servers are usually
deployed, so the WAR becomes the whole application. Writing servlets directly, without a
framework, is practice in request handling, status codes, streaming and lifecycle.

## Users and scenarios

- **Dashboard** calls `GET /api/jobs?state=ERROR&limit=20`.
- **Operator / support** runs `curl /api/documents?property=accountNumber&value=1234567890`,
  then downloads the file.
- **Developer** opens `/api/documents/{id}/structure` to see an AFP file's fields or a PDF's
  object tree as JSON.

## Goals

- `JsonServlet` base, plus `JobsServlet` (`/api/jobs/*`) and `DocumentsServlet`
  (`/api/documents/*`).
- The six read-only endpoints below, with a consistent JSON error body.
- `WorkflowContextListener` owning the engine, repositories and watchers inside Tomcat.
- Configuration from environment variables, with context-param fallbacks.

## Non-goals

- Write endpoints (PRD-15), authentication, a JAX-RS framework, API versioning, pagination
  cursors.

## Requirements

**Functional**
- **FR-1** `GET /api/jobs?state=&limit=` returns jobs newest first. An invalid `state` is a 400;
  `limit` is capped.
- **FR-2** `GET /api/jobs/{id}` returns the job, its properties and step history; an unknown id
  is a 404.
- **FR-3** `GET /api/jobs/{id}/documents?queue=` returns the job's documents, filtered by queue.
- **FR-4** `GET /api/documents` searches across jobs with these filters
  ([Q-08](../OPEN_QUESTIONS.md#q-08--exceptions-endpoint)):
  - `property` and `value`, which go together, match a document property.
  - `queue` matches the document's queue (for example `queue=exceptions`, used by PRD-17's
    Exceptions view).
  - `limit` (default 50, capped at 500) and `offset` (default 0) page the results, ordered by job
    (newest first), then document sequence. The `X-Total-Count` header gives the total number of
    matches.
  - A request with neither `property` nor `queue` is a 400, as is `property` without `value`, or
    a negative or non-numeric `limit` or `offset`.
- **FR-5** `GET /api/documents/{id}/file` streams the file with the correct content type
  (`application/pdf`, or `application/afp` / `application/octet-stream` for AFP) and refuses
  any path outside the output directory.
- **FR-6** `GET /api/documents/{id}/structure` returns structured fields (AFP) or the object tree
  plus page operators (PDF). A corrupt file is a 422 with the byte offset.
- **FR-7** Every error is JSON `{ "error": "...", "status": N }`; an uncaught exception becomes a
  500 without a stack trace in the body.
- **FR-8** `WorkflowContextListener` starts the input devices (PRD-06) and engine on
  `contextInitialized`. On `contextDestroyed` it stops them, leaving no live watcher threads.
- **FR-9** Configuration: `PRINTLAB_HOME` (for `hotfolder/`, `work/`, `output/`, `error/`) and
  the JDBC URL, user and password, read from environment variables first, then context params.
- **FR-10** The CLI keeps working.

**Non-functional**
- **NFR-1** `Cache-Control: no-store` on API responses.
- **NFR-2** One job's JSON matches `fixtures/api/job.json`, which is the contract the dashboard
  tests load.

## Design notes

- Integration tests reuse `TomcatTestServer` (from the scaffold) with seeded H2 and
  `java.net.http.HttpClient`.
- Servlets get repositories from a `ServletContext` attribute set by the listener.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Job listing, filtering, capping, detail and 404/400 behave as specified | `JobsApiTest.*` |
| AC-2 | Document listing, search, file streaming, path-traversal guard and structure endpoint behave as specified | `DocumentsApiTest.*` |
| AC-3 | Errors are JSON with no stack traces | `JsonServletTest.*` |
| AC-4 | The engine starts and stops with the web app; a dropped file becomes a job visible in the API | `WorkflowContextListenerTest.*` |
| AC-5 | The job JSON shape is pinned | `ApiJsonShapeTest` |

## Test plan

- Integration — `JobsApiTest`: `listsJobsNewestFirst`, `filtersByState`, `limitIsCapped`,
  `getJobIncludesStepHistory`, `unknownJobReturns404Json`, `invalidStateParamReturns400`
- Integration — `DocumentsApiTest`: `listsDocumentsForJobFilteredByQueue`,
  `searchByAccountNumberReturnsMatch`, `searchWithoutAnyFilterReturns400`,
  `filtersByQueueAcrossJobs`, `limitAndOffsetPageResultsWithTotalCount`,
  `invalidLimitOrOffsetReturns400`,
  `fileEndpointStreamsPdfWithCorrectContentType`,
  `fileEndpointDoesNotServePathsOutsideOutputDir`,
  `structureEndpointReturnsFieldsForAfpAndObjectsForPdf`,
  `structureOfCorruptFileReturns422WithOffset`
- Unit — `JsonServletTest`: `uncaughtExceptionBecomes500JsonWithoutStackTrace`,
  `unknownSubPathReturns404Json`
- Integration — `WorkflowContextListenerTest`: `startsWatcherOnStartupAndStopsItOnShutdown`,
  `fileDroppedInHotFolderBecomesAJobVisibleInApi`
- Contract — `ApiJsonShapeTest`
- Manual: `./mvnw package cargo:run`, drop a generated file, `curl /api/jobs`.

## Risks and open questions

- Tomcat logs warnings about threads left running after undeploy. Treat any such warning in the
  manual check as a bug.

## Definition of done

- Shared DoD, plus: README documents the endpoints and configuration variables.
