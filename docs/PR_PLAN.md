# Pull request plan

How to take `print-workflow-lab` from the initial skeleton to the full demo:
**drop a print file in a hot folder → split and index it → route, sort and barcode it → watch it in
a dashboard → reprint a single statement.**

Each PR below is small enough to review in one sitting, leaves `main` working, and ships with the
tests listed under it. Sizes: **S** ≈ an evening, **M** ≈ 2–3 evenings, **L** ≈ a week of
evenings.

## Overview

| # | PR | Phase | Size | Depends on |
|---|---|---|---|---|
| 0 | Test infrastructure: coverage, fixtures, helpers | Foundation | S | — |
| 1 | Error folder and job summary file | Core | S | 0 |
| 2 | Routing rules and exception queue | Core | M | 1 |
| 3 | AFP structured-field reader + dump tool | AFP | M | 0 |
| 4 | AFP test-data writer (page groups + TLEs) | AFP | M | 3 |
| 5 | `SplitAfp` step: TLEs become document properties | AFP | M | 4 |
| 6 | Multiple input devices (folder → workflow mapping) | AFP | S | 5 |
| 7 | Barcode step for inserter control | Print features | M | 0 |
| 8 | Persistence: jobs and documents in PostgreSQL | Platform | L | 1 |
| 9 | REST API (read-only) | Platform | M | 8 |
| 10 | Reprint and retry | Platform | M | 9 |
| 11 | Dashboard: jobs, documents, search | Dashboard | M | 9 |
| 12 | Dashboard actions: reprint, retry, exception queue | Dashboard | S | 10, 11 |
| 13 | Docker Compose, demo script, README polish | Presentation | S | 12 |

```
0 ──► 1 ──► 2
│     └──► 8 ──► 9 ──► 10 ──► 12 ──► 13
│                 └──► 11 ──┘
├──► 3 ──► 4 ──► 5 ──► 6
└──► 7
```

**Fast track if the interview is soon:** 0 → 3 → 5 (use a sample AFP file instead of PR 4) → 2 → 7.
That gets you AFP parsing, AFP indexing, rules-based routing and barcodes — the most
interview-relevant topics — without the platform work. Then do 8 → 9 → 11 for a visible dashboard.

Branch naming: `feature/NN-short-name` (e.g. `feature/03-afp-reader`).

---

## Testing strategy

Every PR adds tests at three levels, in proportion to what it changes:

| Level | What it covers | Tools | Runs in CI |
|---|---|---|---|
| **Unit** | One class in isolation: parsers, rules, steps with fake jobs | JUnit 5, `@TempDir` | Yes |
| **Workflow / integration** | A real workflow YAML end to end on generated input; repositories against a database; HTTP endpoints | JUnit 5, H2, Javalin test tools | Yes |
| **Manual** | Things that need eyes: opening AFP in a viewer, the dashboard, the demo | Checklist in the PR description | No |

Rules for every PR:

- **Tests ship in the same PR as the code.** A PR with no new tests needs a sentence explaining why.
- **Test the failure paths**, not just the happy path — bad input, missing config, truncated
  files. Production print is mostly about what happens when things go wrong.
- **Deterministic data.** Generators take a seed; tests never depend on the current time or on
  random order.
- **No test touches the real `hotfolder/`, `work/` or `output/`.** Use `@TempDir`.
- Test names describe behavior: `failedJobMovesInputToErrorFolder`, not `testError1`.
- CI (`./mvnw verify`) must be green before merging; coverage must not drop (from PR 0 on).

---

## Phase 0 — Foundation

### PR 0 — Test infrastructure `S`

**Goal:** make the following PRs cheap to test.

- Add **JaCoCo**: HTML report on `./mvnw verify`, and a minimum line-coverage check (start at the
  current number, ratchet up later).
- Add **AssertJ** for readable assertions on collections and files.
- `src/test/resources/fixtures/` for small checked-in test files (AFP samples, workflow YAMLs).
- Test helpers in `src/test/java/.../testsupport/`:
  - `TestJobs.newJob(Path tempDir, Path input)` — a `Job` with a work dir under `@TempDir`.
  - `TestWorkflows.of(StepDefinition...)` and `TestWorkflows.load(name, overrides)` — build or
    load a workflow and redirect output to a temp folder (replaces the ad-hoc
    `withOutputDir` in `StatementWorkflowTest`).
  - `RecordingStep` — a `Step` that records calls, can be told to throw.
- Upload the JaCoCo report as a CI artifact.

**Tests**
- Existing tests refactored onto the helpers and still green.
- `TestWorkflowsTest.outputOverrideOnlyChangesWriteOutputStep` — guards the helper itself.

---

## Phase 1 — Core workflow

### PR 1 — Error folder and job summary file `S`

**Goal:** a failed job is easy to find and diagnose, and every job leaves a record of what
happened.

- When a job fails, move its input file to `error/<jobId>/` alongside an `error.txt` (step name,
  message, timestamp).
- Write `work/<jobId>/job.json` after every step: job id, state, current step, job properties,
  document count, and per-step start/end times.
- Add a `StepRecord` list (`name`, `startedAt`, `finishedAt`, `outcome`) to `Job`. Inject a
  `Clock` into the engine so times are testable.

**Tests**
- Unit — `WorkflowEngineTest`
  - `recordsStartAndFinishForEveryStep` (fixed `Clock`)
  - `failedStepIsRecordedWithOutcomeFailed`
  - `stepsAfterFailureAreNotRecorded`
- Unit — `JobSummaryWriterTest`
  - `writesJobJsonThatRoundTrips` (write → read with Jackson → equal)
  - `overwritesSummaryAfterEachStep`
- Integration — `ErrorHandlingTest`
  - `failedJobMovesInputToErrorFolder`
  - `errorTxtContainsStepNameAndMessage`
  - `successfulJobLeavesNothingInErrorFolder`
  - `twoFailedJobsWithSameFileNameDoNotCollide`

### PR 2 — Routing rules and exception queue `M`

**Goal:** decide where documents go based on their properties — the equivalent of workflow
connectors with rules.

- A small rule model: `property`, `operator` (`equals`, `notEquals`, `matches`, `notMatches`,
  `greaterThan`, `lessThan`, `missing`), `value`. Rules combine with `all:` / `any:`.
- A `RouteDocuments` step that assigns each document to a named queue (document property
  `queue`), e.g.:
  ```yaml
  - name: route
    type: RouteDocuments
    params:
      routes:
        - queue: exceptions
          when: { any: [ { property: zip, operator: notMatches, value: "\\d{5}" },
                         { property: name, operator: missing } ] }
        - queue: large-envelope
          when: { property: pageCount, operator: greaterThan, value: 6 }
      default: standard
  ```
  Routes are checked in order; the first match wins.
- An optional `when:` on any step so it only runs if a **job** property matches (e.g. skip
  presort when `documentCount` < 50).
- `WriteOutput` writes each queue to its own subfolder.
- Generator option to emit a known number of bad addresses (blank name, 4-digit zip).

**Tests**
- Unit — `RuleTest` (parameterized, one row per case)
  - every operator, true and false case
  - `greaterThan`/`lessThan` compare numerically (`"10" > "9"`), not as strings
  - `missing` is true for absent **and** blank values
  - `all`/`any` nesting two levels deep; empty `all` is true, empty `any` is false
- Unit — `RuleParserTest`
  - parses the YAML example above
  - `unknownOperatorFailsWithStepName`
  - `invalidRegexFailsAtLoadNotAtRuntime`
  - `missingValueForComparisonOperatorFails`
- Unit — `RouteDocumentsStepTest`
  - `firstMatchingRouteWins`
  - `unmatchedDocumentsGoToDefaultQueue`
  - `noDefaultAndNoMatchFailsTheJob`
- Unit — `WorkflowEngineTest.stepWithFalseWhenIsSkippedAndRecordedAsSkipped`
- Workflow — `RoutingWorkflowTest` (generator seeded with 5 bad addresses out of 50)
  - `badAddressesLandInExceptions` (exactly 5)
  - `everyDocumentIsInExactlyOneQueue` (sum of queues == input documents)
  - `outputHasOneFolderPerQueue`

---

## Phase 2 — AFP

### PR 3 — AFP structured-field reader + dump tool `M`

**Goal:** read any AFP file as a sequence of structured fields.

- `StructuredField` record: offset, length, type ID (3 bytes), flags, sequence number, data.
- `AfpReader` implementing `Iterator<StructuredField>` over an `InputStream`: expects `0x5A`,
  reads the 2-byte big-endian length (counts itself but not the `0x5A`), the 3-byte ID, flag
  byte, 2-byte sequence, then `length - 8` data bytes. Errors include the byte offset.
- `FieldType` enum mapping IDs to names (BDT/EDT, BNG/ENG, BPG/EPG, BAG/EAG, BRG/ERG, TLE, PTX,
  NOP, IMM, IPO, MCF, PGD, …). Unknown IDs print as hex.
- `Ebcdic` helper (`Charset.forName("IBM037")`) and a TLE decoder (triplet `0x02` for the
  attribute name, `0x36` for the attribute value).
- CLI: `App dump-afp <file>` prints one line per field, indented by Begin/End nesting, with TLE
  name/value pairs inline.

**Tests**
- Unit — `AfpReaderTest` (inputs built as byte arrays in the test, via a tiny `AfpBytes` builder)
  - `readsSingleField` — offset, length, ID, flags, sequence, data all correct
  - `readsMinimalDocument` — BDT, BPG, EPG, EDT in order
  - `lengthCountsItselfButNotIntroducer` — off-by-one guard
  - `zeroLengthDataIsAllowed`
  - `missingIntroducerFailsWithOffset`
  - `truncatedFieldFailsWithOffset`
  - `emptyFileYieldsNoFields`
- Unit — `FieldTypeTest`
  - `knownIdsMapToNames`, `unknownIdRendersAsHex`
- Unit — `EbcdicTest`
  - `decodesUppercaseDigitsAndSpace` (known bytes `0xC1 0xC2 0xF1 0x40` → `"AB1 "`)
  - `roundTripsAscii`
- Unit — `TleDecoderTest`
  - `decodesNameAndValue`, `ignoresUnknownTriplets`, `missingValueTripletFails`
- Golden-file — `DumpAfpTest`
  - dump of `fixtures/afp/minimal.afp` equals `fixtures/afp/minimal.dump.txt` (update the
    golden file deliberately when output format changes)
  - `nestingIndentsBetweenBeginAndEnd`
- Manual: dump a real sample AFP file and compare with an AFP viewer's view; note the viewer and
  result in the PR.

### PR 4 — AFP test-data writer `M`

**Goal:** generate AFP statement runs with the same content as the PDF generator.

- `AfpWriter` that emits structured fields (computes lengths, sequence numbers).
- `StatementAfpGenerator`: one BNG/ENG page group per statement, TLEs for `accountNumber`,
  `name`, `zip`; pages with minimal PTOCA text using a standard coded font reference.
- CLI: `App generate-afp <count> <out.afp>`.

**Tests**
- Unit — `AfpWriterTest`
  - `writtenFieldIsReadBackIdentically` (writer → reader, field by field)
  - `lengthIsComputedFromData`
  - `rejectsDataLongerThanMaxFieldLength` (32,759 bytes)
  - `sequenceNumbersIncrement`
- Unit — `TleEncoderTest`
  - `encodeThenDecodeRoundTrips` (shares cases with `TleDecoderTest`)
  - `rejectsCharactersNotInCodePage`
- Integration — `StatementAfpGeneratorTest`
  - `sameSeedProducesIdenticalBytes`
  - `groupCountEqualsStatementCount`
  - `everyGroupHasAccountNumberNameAndZipTles`
  - `beginsAndEndsAreBalanced` (stack check over the whole file)
  - `contentMatchesPdfGeneratorForSameSeed` (same account numbers, names, zips, page counts)
- Manual: generated file opens in a free AFP viewer; screenshot in the PR.

> Short on time? Skip this PR and test PR 5 against a sample AFP file with page groups and TLEs
> checked into `fixtures/afp/`.

### PR 5 — `SplitAfp` step `M`

**Goal:** the AFP equivalent of `SplitPdf` — no guessing from text positions.

- Split on BNG/ENG page groups; write each group as a standalone AFP document (with the
  file's BDT/EDT and any inline resource group carried over).
- Every TLE in the group becomes a document property; add `pageCount`.
- Param `requiredProperties`: a group missing one is **marked** (`indexError` property), so PR 2's
  rules route it to `exceptions` instead of failing the job.
- `workflows/statements-afp.yaml` mirroring the PDF workflow.

**Tests**
- Unit — `SplitAfpStepTest`
  - `oneDocumentPerPageGroup`
  - `tlesBecomeDocumentProperties`
  - `pageCountCountsBpgWithinGroup`
  - `groupMissingRequiredTleIsMarkedNotFatal`
  - `inlineResourceGroupIsCopiedIntoEveryDocument`
  - `fileWithNoPageGroupsFailsWithClearMessage`
  - `unbalancedGroupFailsWithOffset`
- Unit — `SplitAfpOutputTest`
  - `everySplitDocumentReadsBackCleanly` (each output passes `AfpReader` + balance check)
- Workflow — `AfpStatementWorkflowTest`
  - `afpAndPdfWorkflowsProduceSameIndexForSameSeed` (same document count, properties, zip order)
  - `missingTleRoutesToExceptions` (with PR 2 rules)

### PR 6 — Multiple input devices `S`

**Goal:** route incoming files to the right workflow, like configured input devices.

- `config/input-devices.yaml`: list of `{ name, folder, glob, workflow }`.
- One `HotFolderWatcher` per device, sharing the job executor.
- Unmatched files are left alone and logged once.

**Tests**
- Unit — `InputDeviceConfigTest`
  - `loadsDevicesFromYaml`
  - `missingWorkflowFileFailsAtStartup`
  - `duplicateDeviceNamesFail`
  - `twoDevicesOnSameFolderAndGlobFail`
- Unit — `HotFolderWatcherTest` (backfills tests the skeleton lacks)
  - `picksUpFilesPresentAtStartup`
  - `picksUpNewFile` (await with a timeout, no fixed sleeps)
  - `waitsUntilFileStopsGrowing` (write in chunks from another thread)
  - `ignoresFilesNotMatchingGlob`
- Integration — `InputDevicesTest`
  - `pdfAndAfpDroppedInTheirFoldersRunTheirWorkflows`

---

## Phase 3 — Print-shop features

### PR 7 — Barcode step `M`

**Goal:** stamp an inserter-control barcode on every page, the way mail-finishing equipment
expects.

- Add ZXing. `AddBarcode` step for PDF documents: Data Matrix (or Code 128) at a configured
  position/size, encoding a template such as
  `${jobId}|${sequence}|${page}|${pageCount}|${accountNumber}`.
- Optional human-readable text under the barcode.

**Tests**
- Unit — `BarcodeTemplateTest`
  - `substitutesJobDocumentAndPageValues`
  - `unknownPlaceholderFailsAtLoad`
  - `valueTooLongForSymbologyFailsWithDocumentSequence`
- Integration — `AddBarcodeStepTest` (render page with PDFBox `PDFRenderer`, decode with ZXing)
  - `firstPageBarcodeDecodesToExpectedValues`
  - `lastPageBarcodeHasPageEqualsPageCount`
  - `everyPageOfEveryDocumentHasABarcode`
  - `barcodeDoesNotOverlapConfiguredRegions` (compare rectangles with the split regions)
  - `existingPageTextIsStillExtractable` (stamping doesn't break `SplitPdf` regions)
- Manual: print or zoom a page and scan the barcode with a phone app.

---

## Phase 4 — Platform

### PR 8 — Persistence `L`

**Goal:** jobs and documents survive restarts and can be searched.

- PostgreSQL via Docker; H2 (PostgreSQL mode) for tests. Flyway migrations.
- Tables: `jobs`, `job_steps`, `documents` (`queue`, `file_path`, `sequence`, `job_id`) and
  `document_properties` (name/value rows, indexed on `(name, value)`).
- Plain JDBC repositories (`JobRepository`, `DocumentRepository`) — no ORM, to practice SQL.
- The engine saves after every step through a `JobStore` interface (file-based `job.json` from
  PR 1 and JDBC are two implementations).

**Tests**
- Integration — `MigrationTest`
  - `migrationsApplyToEmptyDatabase`
  - `migrationsAreRepeatableNoOpOnSecondRun`
- Integration — `JobRepositoryTest` (H2, fresh schema per test)
  - `insertAndFindById`
  - `updateStateAndCurrentStep`
  - `stepHistoryIsOrdered`
  - `listNewestFirstWithPaging`
  - `filterByState`
- Integration — `DocumentRepositoryTest`
  - `insertWithPropertiesAndFindByJob`
  - `findByPropertyValue` (account number search)
  - `findByQueue`
  - `deletingJobCascadesToDocumentsAndProperties`
  - `saveIsTransactional` (failure mid-save leaves no partial rows)
- Workflow — `PersistentWorkflowTest`
  - `completedJobIsReadableAfterEngineRestart` (new engine + repository instance, same DB)
  - `failedJobIsStoredWithErrorAndStep`
- Optional CI job: the same repository tests against real PostgreSQL via Testcontainers.

### PR 9 — REST API (read-only) `M`

**Goal:** expose jobs and documents over HTTP.

- Javalin. Endpoints:
  - `GET /api/jobs?state=&limit=` — newest first
  - `GET /api/jobs/{id}` — job, properties, step history
  - `GET /api/jobs/{id}/documents?queue=`
  - `GET /api/documents?property=accountNumber&value=…` — search
  - `GET /api/documents/{id}/file` — streams the PDF/AFP
- JSON error body `{ "error": "...", "status": 404 }` everywhere.

**Tests**
- Integration — `JobsApiTest` (Javalin test tools + seeded H2)
  - `listsJobsNewestFirst`, `filtersByState`, `limitIsCapped`
  - `getJobIncludesStepHistory`
  - `unknownJobReturns404Json`
  - `invalidStateParamReturns400`
- Integration — `DocumentsApiTest`
  - `listsDocumentsForJobFilteredByQueue`
  - `searchByAccountNumberReturnsMatch`
  - `searchWithoutPropertyReturns400`
  - `fileEndpointStreamsPdfWithCorrectContentType`
  - `fileEndpointDoesNotServePathsOutsideOutputDir` (path traversal guard)
- Contract — `ApiJsonShapeTest`: response JSON for one job matches
  `fixtures/api/job.json` (the dashboard depends on this shape).

### PR 10 — Reprint and retry `M`

**Goal:** the two operator actions every production print system needs.

- `POST /api/documents/reprint` with document ids → a **new child job** with just those
  documents, run through a `reprint` workflow (adds a "REPRINT" marker, skips presort). The child
  job records its parent job id.
- `POST /api/jobs/{id}/retry` → re-runs a failed job from the step that failed.
- Both actions are recorded in `job_steps` for an audit trail.

**Tests**
- Unit — `ReprintServiceTest`
  - `createsChildJobWithOnlySelectedDocuments`
  - `childJobRecordsParentId`
  - `originalJobIsUnchanged`
  - `documentsFromDifferentJobsCanBeReprintedTogether` (or are rejected — decide and test it)
  - `unknownDocumentIdFailsWholeRequest` (no partial reprint)
- Unit — `RetryServiceTest`
  - `retriesFromFailedStepNotFromStart` (`RecordingStep` shows steps 1 was not re-run)
  - `retryingCompletedJobIsRejected`
  - `retryIsRecordedInStepHistory`
- Integration — `ActionsApiTest`
  - `reprintReturns201WithChildJobId`
  - `reprintWithEmptyListReturns400`
  - `retryUnknownJobReturns404`
  - `doubleSubmitRetryDoesNotRunTwice` (idempotency)
- Workflow — `ReprintWorkflowTest`
  - `reprintedPdfHasReprintMarkerOnEveryPage`

---

## Phase 5 — Dashboard

### PR 11 — Dashboard: jobs, documents, search `M`

**Goal:** the operator view. Plain JavaScript (or Preact + htm, no build step), served by Javalin
as static files.

- Jobs list with state badges and current step, auto-refreshing.
- Job detail: properties, step timeline, documents grouped by queue.
- Document search by account number / name / zip; open a document's file.
- Text-only rendering of all values (never `innerHTML` with data).

**Tests** (`node --test`, no browser needed for these)
- `apiClient.test.js`
  - `buildsQueryStringsAndEncodesValues`
  - `throwsReadableErrorFromJsonErrorBody`
  - `abortsPreviousSearchWhenNewOneStarts`
- `format.test.js`
  - `formatsDurationsAndTimestamps`
  - `mapsJobStateToBadge`
- `render.test.js` (with a minimal DOM such as `linkedom`)
  - `rendersValuesAsTextNotHtml` (a name like `<img onerror=…>` shows literally)
  - `emptyStatesRenderMessage`
- Contract: the JS tests load `fixtures/api/job.json` from PR 9, so an API shape change breaks
  them.
- Manual checklist in the PR: list refreshes, job detail, search, open file, narrow screen.
- CI: add a `node --test` step to the workflow.

### PR 12 — Dashboard actions `S`

- Select documents → **Reprint**; failed job → **Retry**; an **Exceptions** view listing
  documents in the exception queue across jobs.
- Confirmation before actions, inline result, links to the new child job.

**Tests**
- `actions.test.js`
  - `reprintSendsSelectedIdsOnly`
  - `buttonsDisabledWhileRequestInFlight` (no double submit)
  - `showsErrorMessageWhenApiFails`
- Integration — `ExceptionsApiTest` (if a new endpoint is added for the cross-job view)
- Manual demo script in the PR: generate a run with bad addresses → find one in Exceptions →
  reprint a good statement by account number → see the child job complete.

---

## Phase 6 — Presentation

### PR 13 — Docker Compose, demo script, README polish `S`

- Multi-stage `Dockerfile`; `docker compose up` starts the app and PostgreSQL with mounted
  `hotfolder/` and `output/`.
- `docs/DEMO.md`: the 5-minute walkthrough, step by step.
- README: architecture diagram, a 30-second GIF of the demo, updated concept map and roadmap.
- Add a license (MIT) before making the repository public.

**Tests**
- CI job: build the Docker image.
- CI smoke test: `docker compose up -d`, wait for `/api/health` (add the endpoint here), drop a
  generated file in the hot folder, poll `/api/jobs` until the job is `COMPLETE`, then
  `docker compose down`.
- Manual: from a clean clone, follow `docs/DEMO.md` exactly.

---

## Conventions for every PR

- One concern per PR; tests in the same PR as the code (see *Testing strategy*).
- CI (`./mvnw verify`, plus `node --test` from PR 11) green before merging.
- PR description: what and why, the tests added, how to test manually, screenshots for UI changes.
- Update the README roadmap checkbox in the PR that completes the item.
