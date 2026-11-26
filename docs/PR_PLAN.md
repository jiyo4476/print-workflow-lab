# Pull request plan

How to take `print-workflow-lab` from the initial skeleton to the full demo:
**drop an AFP or PDF print file in a hot folder → split and index it → route, sort and barcode it
→ watch it in a dashboard → reprint a single statement.**

Each PR below is small enough to review in one sitting, leaves `main` working, and ships with the
tests listed under it. Sizes: **S** ≈ an evening, **M** ≈ 2–3 evenings, **L** ≈ a week of
evenings.

Each PR also has a PRD in [`docs/prd/`](prd/README.md) (`PRD-NN-*.md`, where NN is the PR number
below): why it exists, numbered requirements, non-goals, acceptance criteria mapped to these
tests, and open questions. If a PR changes its scope, update its PRD and this plan together.
Unanswered decisions are collected in [`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md); check it when
starting a PR and record the answer there.

## Overview

| # | PR | Plan phase | Size | Depends on |
|---|---|---|---|---|
| 0 | Test infrastructure: coverage, fixtures, helpers | Foundation | S | — |
| 1 | Error folder and job summary file | Core | S | 0 |
| 2 | Routing rules and exception queue | Core | M | 1 |
| 3 | AFP structured-field reader + dump tool | AFP | M | 0 |
| 4 | AFP test-data writer (page groups + TLEs) | AFP | M | 3 |
| 5 | `SplitAfp` step: TLEs become document properties; `MapProperties` renaming | AFP | M | 4 |
| 6 | Multiple input devices (folder → workflow mapping) | AFP | S | 5 |
| 7 | PDF object reader + dump tool | PDF | L | 0 |
| 8 | Modern PDF structure: xref streams, object streams, incremental updates | PDF | M | 7 |
| 9 | Content streams and text positions | PDF | L | 7 |
| 10 | PDF/VT-style document parts (DPart) indexing | PDF | M | 8, 2 |
| 11 | PDF preflight step | PDF | M | 7, 2 |
| 12 | Barcode step for inserter control | Print features | M | 0 |
| 13 | Persistence: jobs and documents in PostgreSQL | Platform | L | 1, 19 |
| 14 | REST API on Tomcat (read-only servlets) | Platform | M | 13 |
| 15 | Reprint and retry (AFP reprints marked with a text object per page) | Platform | M | 14 |
| 16 | Marionette 4 dashboard: jobs, documents, search | Dashboard | L | 14 |
| 17 | Dashboard actions: reprint, retry, exception queue | Dashboard | M | 15, 16 |
| 18 | Docker Compose, demo script, README polish | Presentation | S | 17 |
| 19 | Jenkins CI, running locally in Docker (land right after 0) | Foundation | M | 0 |
| 20 | Deploy to k3s on ARM | Delivery | M | 18, 19 |
| 21 | Mark AFP reprints with a page overlay | AFP | M | 15 |

```
0 ──► 1 ──► 2
│     └──► 13 ──► 14 ──► 15 ──► 17 ──► 18 ──► 20
│          ▲       │     └──► 21            ▲
│          │       └──► 16 ──┘              │
├──► 19 ───┴────────────────────────────────┘
├──► 3 ──► 4 ──► 5 ──► 6
├──► 7 ──► 8 ──► 10
│     ├──► 9
│     └──► 11
└──► 12
```

**Fast track if the interview is soon:** 0 → 3 → 5 (use a sample AFP file instead of PR 4) → 7 → 9
→ 2. That covers AFP parsing and indexing, PDF objects and content streams, and rules-based
routing — the most interview-relevant topics — without the platform work. Then 12 (barcodes), and
13 → 14 → 16 for a visible dashboard.

PRs 19, 20 and 21 were added after the plan was written (CI/CD moved to Jenkins and k3s, and the
AFP overlay was split out of PR 15), so their numbers don't show their order: PR 19 lands right
after PR 0, PR 21 any time after PR 15, and PR 20 comes last.

Branch naming: `feature/NN-short-name` (e.g. `feature/03-afp-reader`).

"Phase" in this plan (Phase 0 — Foundation, ...) groups the PRs. It isn't the same as the
**workflow phases** every workflow runs in (receive, prepare, assemble, print, insert, complete),
described below.

### Done outside the numbered plan

These landed between plan items, without a plan number. Later PRs build on them.

| GitHub PR | What | Affects |
|---|---|---|
| #17 | Review fixes: hot-folder retries and rescans, interrupt handling, `newDocumentWhen` for `SplitPdf`, output keeps each document's extension, 12-digit job IDs | 6, 13, 15 |
| #18 | npm `overrides` for `glob` / `minimatch` under mocha (`npm audit` 0) | 16 |
| #20 | **Workflow phases**: steps grouped under `phases:` into receive, prepare, assemble, print, insert, complete (ProcessDirector's phases), in that order; `Job.phase()` in `job.json`, `error.txt` and the log | 6, 12, 13, 15, 16 |
| #21 | **Job tickets**: `SetJobPropsFromTextFile`, first in every sample workflow's receive phase, sets job properties from `<input>.jobprops`, which `JobIntake` moves with the input | 6, 14, 15 |

What later items should do with them:
- New steps go in the phase where ProcessDirector would run them: a barcode step (12) in
  prepare or assemble, preflight (11) in receive or prepare, reprint (15) re-enters at prepare
  or print.
- Persistence (13) stores each job's phase and each step record's phase; the dashboard (16)
  shows the phase next to the current step; the REST API (14) returns it.
- Every input device (6) carries job tickets the same way.

---

## Web platform

Stack: **Marionette.js v4 (`backbone.marionette` 4.x) with Backbone and jQuery, served by Tomcat as a WAR**.
The WAR targets **Apache Tomcat 10.1** (Jakarta Servlet 6.0, Java 21). The scaffold for this is
already on `main`:

- `pom.xml` builds `target/print-workflow-lab.war`. `./mvnw package cargo:run` downloads Tomcat
  10.1.55 from Maven Central and serves the app at `http://localhost:8080/`
  (`-Dtomcat.port=...` to change the port).
- `GET /api/health`: `HealthServlet`, a plain `jakarta.servlet` servlet writing JSON with Jackson.
- `src/main/webapp/`: the dashboard shell, a Marionette `Application` showing a `ShellView`
  (header and main regions) with an API-health badge.
- Browser libraries come from **WebJars** in `WEB-INF/lib`, which Tomcat serves at
  `/webjars/<name>/<version>/...`, so there is **no JavaScript build step**:
  `backbone.marionette@4.1.3`, `backbone@1.6.0`, `backbone.radio@2.0.0`, `underscore@1.13.8`,
  `jquery@3.7.1` (Marionette 4 predates jQuery 4, so jQuery stays on 3.x).
- App code is plain scripts under a `PrintLab` namespace, loaded by `index.html` after the
  libraries. `main.js` only starts the app, so tests can load everything else without side
  effects.
- **Version pinning:** `pom.xml`, `index.html` and `package.json` (npm copies for tests) must
  name the same versions; `src/test/js/versions.test.mjs` fails if they don't. Upgrades change
  all three in one PR.

**Marionette.js v4 ground rules.** The reference is the
[`v4.1.3` tag's `docs/`](https://github.com/marionettejs/backbone.marionette/tree/v4.1.3/docs),
since the npm package ships no docs. Use only the 4.x API those docs describe.

- One `Application` (`DashboardApp`, `region: '#app'`) owns startup and the shell. Features are
  `MnObject` controllers that own their collections, polling and requests; Views only render and
  emit intent through `triggers` / `childViewTriggers`. No View holds a reference to another View
  or to a controller.
- Routing uses `Backbone.Router` + `Backbone.history` (hash routes). Marionette 4 doesn't bundle
  `AppRouter`, and we don't add it.
- App-wide events go through a `Backbone.Radio` channel (`dashboard`), via the Application's
  `channelName` / `radioEvents`.
- Templates are `_.template` strings using **only `<%- %>`** (escaped) for data, never `<%= %>`.
  A test fails the build if `<%=` appears under `src/main/webapp/js/`.
- HTTP goes through Backbone `fetch`/`save` or one `PrintLab.api` helper around `$.ajax`, so
  tests can replace `Backbone.ajax` and never touch the network.

---

## Testing strategy

Every PR adds tests at the levels below, in proportion to what it changes:

| Level | What it covers | Tools | Runs in CI |
|---|---|---|---|
| **Unit** | One class in isolation: parsers, rules, steps with fake jobs | JUnit 4, `TemporaryFolder` rule | Yes |
| **Workflow / integration** | A real workflow YAML end to end on generated input; repositories against a database; HTTP endpoints | JUnit 4, H2, embedded Tomcat (`TomcatTestServer`) + `java.net.http.HttpClient` | Yes |
| **Differential** | Our PDF readers against a trusted implementation (PDFBox) on the same files; for AFP, writer → reader round trips | JUnit 4 + PDFBox as oracle | Yes |
| **Dashboard** | Marionette Views, controllers and the Application: rendering, escaping, empty states, requests, polling, teardown | Mocha + jsdom (`npm test`, specs in `src/test/js/`), `Backbone.ajax` stubbed, Sinon fake timers for polling | Yes |
| **Manual** | Things that need eyes: opening AFP in a viewer, the dashboard in a real Tomcat, the demo | Checklist in the PR description | No |

Java tests use **JUnit 4** (4.13.2), the version common in long-lived enterprise Java
codebases such as RICOH ProcessDirector's. Test classes and methods are `public`; use
`@Before`/`@After`, the `TemporaryFolder` rule for files, `@RunWith(Parameterized.class)` for
table-driven tests, and `assertThrows` (added in 4.13). Assertion messages come first:
`assertEquals(message, expected, actual)`. Don't add JUnit 5 (Jupiter) or the Vintage engine.

Rules for every PR:

- **Tests ship in the same PR as the code.** A PR with no new tests needs a sentence explaining why.
- **Test the failure paths**, not just the happy path — bad input, missing config, truncated
  files. Production print is mostly about what happens when things go wrong.
- **Deterministic data.** Generators take a seed; tests never depend on the current time or on
  random order.
- **No test touches the real `hotfolder/`, `work/` or `output/`.** Use the `TemporaryFolder` rule.
- **Golden files** (expected dump output checked into `fixtures/`) are updated deliberately, never
  regenerated blindly to make a test pass.
- Test names describe behavior: `failedJobMovesInputToErrorFolder`, not `testError1`.
- CI (`./mvnw verify` and `npm test`) must be green before merging; coverage must not drop (from PR 0 on).

**Coverage.** `./mvnw verify` runs JaCoCo: the report is `target/site/jacoco/index.html`, and the
build fails if line coverage is below `coverage.line.minimum` in `pom.xml`. Test-data generators
(`testdata/`) and `App` are excluded. To raise the gate, run `./mvnw verify`, read the *Lines*
total in the report, and set the property to that value rounded **down** to two decimals, in its
own commit. Never lower it to get a PR through; add tests instead.

**Test helpers** (`src/test/java/dev/yjimmy/printlab/testsupport/`): `TestJobs.newJob` for a job
in a temp folder, `TestWorkflows.of`/`step`/`load` to build or load workflows with output
redirected, and `RecordingStep` for a fake step that records calls or throws on its Nth call.
Small checked-in test files go in `src/test/resources/fixtures/`.

---

## Phase 0 — Foundation

### PR 0 — Test infrastructure `S`

**Goal:** make the following PRs cheap to test.

- Add **JaCoCo**: HTML report on `./mvnw verify`, and a minimum line-coverage check (start at the
  current number, ratchet up later).
- Add **AssertJ** for readable assertions on collections and files.
- `src/test/resources/fixtures/` for small checked-in test files (AFP samples, workflow YAMLs).
- Test helpers in `src/test/java/.../testsupport/`:
  - `TestJobs.newJob(Path tempDir, Path input)` — a `Job` with a work dir under a `TemporaryFolder`.
  - `TestWorkflows.of(StepDefinition...)` and `TestWorkflows.load(name, outputDir)` — build or
    load a workflow and redirect output to a temp folder (replaces the ad-hoc
    `withOutputDir` in `StatementWorkflowTest`).
  - `RecordingStep` — a `Step` that records calls, can be told to throw.
- Upload the JaCoCo report as a CI artifact — **deferred to PR 19** (Jenkins), since GitHub
  Actions is being retired (Q-10).

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
- Write `work/<jobId>/job.json` after every step: job id, state, current step, error message,
  job properties, document count, and per-step start/end times.
- Add a `StepRecord` list (`name`, `startedAt`, `finishedAt`, `outcome`) to `Job`. Inject a
  `Clock` into the engine so times are testable.

**Tests**
- Unit — `WorkflowEngineTest`
  - `recordsStartAndFinishForEveryStep` (fixed `Clock`)
  - `failedStepIsRecordedWithOutcomeFailed`
  - `stepsAfterFailureAreNotRecorded`
  - `rewritesSummaryAfterEachStep`, `failedJobSummaryRecordsTheError`,
    `exceptionWithoutMessageIsDescribedByItsClass`
  - `stackOverflowInStepFailsTheJobCleanly`, `linkageErrorInStepFailsTheJob`,
    `outOfMemoryErrorIsNotSwallowed`
- Unit — `JobSummaryWriterTest`
  - `writesJobJsonThatRoundTrips` (write → read with Jackson → equal)
  - `overwritesSummaryAfterEachStep`
  - `writesTimesAsIso8601`, `leavesNoTempFilesBehind`
- Integration — `ErrorHandlingTest`
  - `failedJobMovesInputToErrorFolder`
  - `errorTxtContainsStepNameAndMessage`
  - `successfulJobLeavesNothingInErrorFolder`
  - `twoFailedJobsWithSameFileNameDoNotCollide`
  - `errorTxtSaysWhereInputWasLeftWhenItCannotBeMoved`

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
- `WriteOutput` writes each queue to its own subfolder, plus one `documents.csv` for the whole
  job with a `queue` column (decided in `OPEN_QUESTIONS.md`, Q-01).
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
  - `documentsCsvListsEveryDocumentWithItsQueue` (one row per document, with its queue)

---

## Phase 2 — AFP

### PR 3 — AFP structured-field reader + dump tool `M`

**Goal:** read any AFP file as a sequence of structured fields.

- `StructuredField` record: offset, length, type ID (3 bytes), flags, reserved bytes, data.
- `AfpReader` implementing `Iterator<StructuredField>` over an `InputStream`: expects `0x5A`,
  reads the 2-byte big-endian length (counts itself but not the `0x5A`), the 3-byte ID, flag
  byte, 2 reserved bytes, then `length - 8` data bytes. Errors include the byte offset.
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

- `AfpWriter` that emits structured fields (computes lengths; reserved bytes `0000`).
- `StatementAfpGenerator`: one BNG/ENG page group per statement, TLEs for `accountNumber`,
  `name`, `zip`; pages with minimal PTOCA text using a standard coded font reference.
- CLI: `App generate-afp <count> <out.afp>`.

**Tests**
- Unit — `AfpWriterTest`
  - `writtenFieldIsReadBackIdentically` (writer → reader, field by field)
  - `lengthIsComputedFromData`
  - `rejectsDataLongerThanMaxFieldLength` (32,759 bytes)
  - `reservedBytesAreZero`
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

### PR 5 — `SplitAfp` step and property mapping `M`

**Goal:** the AFP equivalent of `SplitPdf` — no guessing from text positions.

- Split on BNG/ENG page groups; write each group as a standalone AFP document (with the
  file's BDT/EDT and any inline resource group carried over).
- Every TLE in the group becomes a document property; add `pageCount`.
- Param `requiredProperties`: a group missing one is **marked** (`indexError` property), so PR 2's
  rules route it to `exceptions` instead of failing the job.
- `workflows/statements-afp.yaml` mirroring the PDF workflow.
- A reusable `MapProperties` step that renames document properties with a `rename:` map, plus an
  optional `requiredProperties` checked after renaming
  ([Q-04](OPEN_QUESTIONS.md#q-04--tle-name-mapping)). A different value already under the new
  name marks the document (`indexError`) instead of overwriting it.
- `App generate-afp --tle-names legacy` writes `ACCT_NO`, `CUST_NAME` and `ZIP_CODE` TLEs, and
  `workflows/statements-afp-legacy.yaml` maps them back with `MapProperties`.

**Tests**
- Unit — `SplitAfpStepTest`
  - `oneDocumentPerPageGroup`
  - `tlesBecomeDocumentProperties`
  - `pageCountCountsBpgWithinGroup`
  - `groupMissingRequiredTleIsMarkedNotFatal`
  - `inlineResourceGroupIsCopiedIntoEveryDocument`
  - `laterImmAppliesToEveryFollowingDocument`, `immBetweenAGroupsPagesCarriesIntoTheNextDocument`
    (an IMM stays in effect until the next one)
  - `fileWithNoPageGroupsFailsWithClearMessage`
  - `unbalancedGroupFailsWithOffset`
- Unit — `SplitAfpOutputTest`
  - `everySplitDocumentReadsBackCleanly` (each output passes `AfpReader` + balance check)
- Workflow — `AfpStatementWorkflowTest`
  - `afpAndPdfWorkflowsProduceSameIndexForSameSeed` (same document count, properties, zip order)
  - `missingTleRoutesToExceptions` (with PR 2 rules)
  - `legacyTleNamesGiveSameIndexAfterMapping`
- Unit — `MapPropertiesStepTest`
  - `renamesListedProperties`
  - `keepsUnlistedProperties`
  - `missingOldNameIsSkipped`
  - `conflictingValueMarksIndexErrorAndKeepsBoth`
  - `requiredPropertiesAreCheckedAfterRenaming`
  - `renamesApplyTogetherSoOrderDoesNotMatter`
  - `twoOldNamesToOneNewNameFailsAtLoad`
  - `mapsArbitraryNamesFromWorkflowConfig` (no names hardcoded; everything comes from the YAML)
- Unit — `StatementAfpGeneratorTest.legacyTleNamesOptionWritesProductionStyleNames`

### PR 6 — Multiple input devices `S`

**Goal:** route incoming files to the right workflow, like configured input devices.

- `config/input-devices.yaml`: list of `{ name, folder, glob, workflow }`.
- One `HotFolderWatcher` per device, sharing the job executor. **Each device has its own
  folder**: two devices can't share one, even with different globs.
- Unmatched files are left alone and logged once; job tickets are never inputs.
- Jobs record their device (`inputDevice`), which a ticket can't override.

**Tests**
- Unit — `InputDeviceConfigTest`
  - `loadsDevicesFromYaml`
  - `missingWorkflowFileFailsAtStartup`
  - `duplicateDeviceNamesFail`
  - `twoDevicesOnTheSameFolderFail`
  - `nestedFoldersAreDifferentFolders`, `invalidGlobFails`, `missingFieldFailsNamingIt`,
    `emptyDeviceListFails`, `unknownKeyFails`
- Unit — `HotFolderWatcherTest` (the basics landed in GitHub #17)
  - `jobTicketsAreNeverHandedOffEvenWhenTheGlobMatches`
  - `unmatchedFileIsLoggedOnceWhileItStays`
- Unit — `AppDevicesTest` (`run` with no arguments, `--devices`, `run <workflow>`)
- Integration — `InputDevicesTest` (waits on a job-done callback, no fixed sleeps)
  - `pdfAndAfpDroppedInTheirFoldersRunTheirWorkflows`
  - `jobRecordsItsInputDevice`
  - `jobTicketTravelsWithItsInputOnAnyDevice`
  - `badWorkflowFailsAtStartupNamingTheDevice`, `devicesSharingAFolderFailAtStartup`

---

## Phase 3 — PDF data streams

The skeleton uses PDFBox's high-level API, which hides how a PDF is actually built. These PRs read
the file format directly — objects, cross-reference tables, compressed streams and content
streams — the same way the AFP phase reads structured fields. PDFBox stays in the project as a
**test oracle**: every reader here is checked against what PDFBox reports for the same file.

| Concept | AFP | PDF |
|---|---|---|
| Low-level reader + dump tool | PR 3 (structured fields) | PR 7 (objects, xref), PR 8 (modern structure) |
| What's on the page | PTOCA text in PR 4 | Content streams, PR 9 |
| Test-data writer | PR 4 | Existing generator, extended in PR 10 |
| Built-in document index | Page groups + TLEs, PR 5 | PDF/VT document parts (DPart), PR 10 |
| Index without metadata | — | Text by page region (skeleton, rebuilt in PR 9) |
| Print-readiness checks | — | Preflight, PR 11 |

### PR 7 — PDF object reader + dump tool `L`

**Goal:** open a PDF without PDFBox: find the cross-reference table, resolve objects, decode
streams, and walk the document tree.

**Split in two** (as PRD-07 allowed): **7a**, the lexer, the parser and the `PdfObject` types;
**7b**, `XrefReader`, `StreamDecoder`, `PdfDocumentReader` and `dump-pdf`.

**PDF versions:** the object syntax is the same from PDF 1.0 to 2.0, so nothing rejects a
version. 7b reads the version from the header and the catalog's `/Version`, and decodes PDF 2.0
UTF-8 text strings (`EF BB BF`) as well as UTF-16BE; PRD-07's "PDF versions" table says which
PR handles each version's changes.

- `PdfLexer`: numbers, names (with `#xx` escapes), literal strings (nested parentheses, `\n`,
  `\)` and octal escapes), hex strings, arrays, dictionaries, keywords, comments.
- `PdfParser` producing a sealed `PdfObject` hierarchy: `PdfDictionary`, `PdfArray`, `PdfName`,
  `PdfString`, `PdfNumber`, `PdfBoolean`, `PdfNull`, `PdfReference`, `PdfStream`.
- `XrefReader`: find `startxref` from the end of the file, parse the classic `xref` table and
  `trailer`, resolve indirect objects lazily by byte offset. Handles a stream `/Length` that is
  itself an indirect reference.
- `StreamDecoder`: `FlateDecode` (`java.util.zip.Inflater`) and unfiltered streams; any other
  filter is reported as unsupported, not a crash.
- `PdfDocumentReader`: version, trailer, catalog → page tree (inherited `MediaBox`/`Resources`
  resolved) → pages with their content streams.
- CLI: `App dump-pdf <file>` prints the header, trailer and page tree (MediaBox, font names,
  content length per page); `App dump-pdf <file> <objNum>` prints one object, with its decoded
  stream if it's text.
- Test fixtures are saved with `CompressParameters.NO_COMPRESSION` so they use a classic xref
  table; compressed structure is PR 8.

**Tests**
- 7a, Unit — `PdfLexerTest` (parameterized `Numbers` and `MalformedNumbers`, plus `Tokens`)
  - numbers: `42`, `-3`, `+7`, `3.14`, `-.5`, `4.`; `1.2.3`, `--5`, `.` fail with their offset
  - `nameWithHexEscapeDecodes` (`/A#20B` → `A B`)
  - `literalStringWithNestedParentheses`, `literalStringEscapes` (`\n`, `\)`, `\\`, `\053`),
    `octalEscapesTakeAtMostThreeDigits`, `backslashAtEndOfLineContinuesTheString`,
    `unescapedEndOfLineInAStringIsOneLineFeed`
  - `hexStringWithOddDigitCountPadsZero` (`<901FA>` → `90 1F A0`)
  - `commentsAreSkipped`, `keywordsAreRecognized`, `delimitersNeedNoSpaces`
- 7a, Unit — `PdfParserTest`
  - `nestedDictionariesAndArrays`
  - `indirectReferenceVsTwoNumbers` (`[12 0 R]` vs `[12 0]`)
  - `streamWithDirectLength`, `streamWithIndirectLength`,
    `streamAfterCrLfAndBinaryDataThatLooksLikeEndstream`,
    `wrongStreamLengthFailsAtWhereEndstreamShouldBe`
  - `unterminatedDictionaryFailsWithOffset`, `nullEntryIsTheSameAsNoEntry`, `missingEndobjFails`
  - `deeplyNestedArraysFailWithOffset` (at most 500 levels, never a `StackOverflowError`),
    `blanksBeforeTheEndOfLineAfterStreamAreSkipped`
- 7a, Unit — `PdfObjectTest` (equality, text forms, constructor checks)
- 7a, Differential — `PdfParserVsPdfBoxTest.everyObjectMatchesPdfBoxForSeveralSeeds`: every
  object of a generated statement PDF, parsed at the offset PDFBox's xref gives, equals the
  object PDFBox read (dictionaries, arrays and references all the way down; raw stream bytes),
  with one stream's `/Length` made indirect first
- 7b, Unit — `PdfStringTest`: `utf8TextStringWithBomDecodes`,
  `pdfDocEncodingMatchesPdfBoxForEveryDefinedByte`
- 7b, Unit — `PdfDocumentReaderTest`
  - `versionFromHeader`, `catalogVersionOverridesAnEarlierHeader`, `aLaterVersionIsReadNotRejected`,
    `pdf20FileReads`
  - `pagesInheritMediaBoxAndResourcesFromTheirAncestors`, `pageTreeLoopFails`,
    `contentsMayBeOneStreamOrAnArray`
- 7b, Unit — `XrefReaderTest`
  - `findsStartxrefWithTrailingWhitespaceVariants` (`\n`, `\r\n`, CR, missing final newline)
  - `parsesMultipleSubsections`, `freeEntriesResolveToNull`
  - `offsetPointingAtWrongObjectFailsWithMessage`, `streamWhoseLengthIsItselfFailsCleanly`
  - `crossReferenceStreamWithTooLittleDataFails`, `prevMustBeAnOffsetInTheFile` (named
    `...IsReportedAsPr8` until PR 8 read those files)
- 7b, Unit — `StreamDecoderTest`
  - `flateRoundTrip` (compress with `Deflater`, decode, compare)
  - `unknownFilterReportedAsUnsupported`, `flateWithAPredictorIsReportedAsUnsupported`
  - `truncatedFlateDataFails`, `decodingStopsPastTheSizeLimit`
- 7b, Differential — `PdfReaderVsPdfBoxTest` (generated files, seeds 1, 7 and 42)
  - `xrefOffsetsMatchPdfBox`
  - `pagesMediaBoxesAndFontNamesMatchPdfBox`
  - `decodedContentStreamsMatchPdfBoxByteForByte`
- 7b, Golden-file — `DumpPdfTest`: dump of `fixtures/pdf/two-statements.pdf` equals
  `fixtures/pdf/two-statements.dump.txt`, and object 10 (a content stream) equals
  `two-statements.object-10.dump.txt`; `fixtureIsWhatClassicPdfWritesForSeed7`.
- Manual: compare `dump-pdf` with `qpdf --qdf` or `mutool show` output for a real-world PDF.

### PR 8 — Modern PDF structure: xref streams, object streams, incremental updates `M`

**Goal:** read PDFs the way most current tools write them (PDF 1.5+), and understand edits.

- Cross-reference **streams** (`/Type /XRef`, `/W`, `/Index`) including the **PNG predictors**
  (`/DecodeParms /Predictor 10–15`) they almost always use.
- **Object streams** (`/Type /ObjStm`, `/N`, `/First`): objects stored compressed inside another
  object.
- **Incremental updates:** follow the `/Prev` chain; the newest entry for an object wins. Keep
  each revision so `reader.revision(n)` can show the file as it was.
- `App dump-pdf --xref <file>` lists every xref section and which objects each update changed.

**Tests**
- Unit — `PngPredictorTest`
  - `KnownVectors` (parameterized: None, Sub, Up, Average, Paeth, worked by hand)
  - `randomRowsRoundTripForEveryFilterAndPixelSize`, `leftIsOnePixelBackNotOneByte`
- Integration — `XrefStreamTest`
  - `compressedAndUncompressedSavesResolveTheSameObjects` (PDFBox default save vs
    `NO_COMPRESSION` save of the same document)
  - `indexRangesAndAMissingTypeField`, `flateStreamWithAPngPredictorReads`
  - `hybridFileReadsObjectsFromBothTheTableAndTheStream`
- Integration — `ObjectStreamTest`
  - `objectInsideObjectStreamResolves`
  - `objectStreamWithBadFirstOffsetFailsWithMessage`, `objectStreamInsideAnObjectStreamFails`
- Integration — `IncrementalUpdateTest` (PDFBox `saveIncremental` after changing `/Info /Title`,
  from a classic and a compressed original)
  - `newestRevisionWins`
  - `previousRevisionStillReadable`
  - `xrefDumpShowsTwoSectionsAndChangedObjects` (a `PdfBytes` file, golden
    `fixtures/pdf/two-revisions.xref.dump.txt`)
  - `objectFreedInAnUpdateResolvesToNullButIsInTheEarlierRevision`, `prevLoopFails`
- Differential — `PdfReaderVsPdfBoxTest` extended to compressed files (and the AFPWorld sample
  PDF when downloaded).

### PR 9 — Content streams and text positions `L`

**Goal:** understand what is drawn on a page and where — and why PDF text extraction is hard.

- `ContentStreamParser` → `Operation(operator, operands)`; inline images (`BI` … `ID` … `EI`)
  skipped correctly even when the image bytes contain `EI`.
- `GraphicsState` tracking: `q`/`Q` stack, `cm`, `BT`/`ET`, `Tf`, `Td`/`TD`/`Tm`/`T*`, `TL`,
  `Tc`/`Tw`/`Tz`, `Tj`/`TJ`/`'`/`"`.
- `TextPositionExtractor`: glyph positions for simple fonts (Standard 14 metrics,
  WinAnsiEncoding), converted to the top-left coordinates the workflow regions use.
- `RegionTextExtractor` and a new `SplitPdf` param `extractor: builtin | pdfbox`.
- CLI: `App dump-content <file> <page>` prints operators, indented inside `q`/`Q` and `BT`/`ET`.
- Out of scope, documented: CID/Type 0 fonts and `ToUnicode` maps (detected and reported).

**Tests**
- Split in two (PRD-09): 9a is the parser and `dump-content`, 9b the state, positions and
  extractor. Follow-up 9c (done): `App dump-text <file> <page>` prints each glyph's text, position,
  width and size (`DumpTextTest`, a golden file for page 1 of the fixture).
- Unit — `ContentStreamParserTest` (9a)
  - `operandsAttachToFollowingOperator`
  - `tjArrayWithKerningNumbers`
  - `inlineImageContainingEiBytesIsSkipped` (ended by the size its parameters give)
  - `inlineImageLengthIsUsedWhenGiven`, `filteredInlineImageEndsAtTheEiFollowedByContent`
- Differential — `ContentStreamVsPdfBoxTest` (9a): the same operators as PDFBox's
  `PDFStreamParser` for generated statements; inline images checked by hand, since PDFBox
  3.0.3 loses what follows them
- Golden — `DumpContentTest.twoStatementsPageOneMatchesGoldenDump` (9a)
- Unit — `GraphicsStateTest`
  - `qAndQRestoreState`
  - `cmConcatenatesInCorrectOrder`
  - `tdMovesLineMatrix`, `tStarUsesLeading`
- Unit — `TextPositionTest`
  - `accountNumberFoundAtGeneratorPosition` (x = 400, y = 40 from top, ±0.5 pt)
  - `tjKerningShiftsFollowingGlyphs` (thousandths of text space)
- Unit — `RegionTextExtractorTest`: `aGlyphBelongsWhereItsBaselineStarts`,
  `overlappingRegionsEachGetTheirGlyphs`, `fakeBoldTextIsReadOnce`
- Differential — `SimpleFontTest`: encodings and Standard 14 widths match PDFBox
- Differential — `RegionExtractorVsPdfBoxTest`: builtin and PDFBox region text identical for 100
  generated statements.
- Workflow — `BuiltinExtractorWorkflowTest`: `documents.csv` identical with `extractor: builtin`
  and `extractor: pdfbox`.
- Limitation — `cidFontReportedAsUnsupportedNotGarbled`, `builtInEncodingsAreReportedNotGuessed`,
  `textThatIsNotHorizontalIsReported`, `formsDrawnOverAndOverFailPastTheContentLimit`;
  `SplitPdfStepTest.builtinExtractorFailsOnAPageItCantRead`.

### PR 10 — PDF/VT-style document parts (DPart) indexing `M`

**Goal:** the PDF counterpart of AFP page groups and TLEs: put the document index **inside the
file** so splitting no longer depends on where text sits on the page.

- Generator option `--dpart`: adds a `/DPartRoot` to the catalog with one DPart node per
  statement — its page range (`/Start`, `/End`) and a `/DPM` metadata dictionary
  (`AccountNumber`, `Name`, `Zip`). Follows the PDF/VT (ISO 16612-2) document-part structure;
  full PDF/VT conformance (output intents, etc.) is out of scope.
- `SplitPdfByDPart` step using the PR 7/8 reader: one document per DPart node at the configured
  record level; DPM entries become document properties. A file with no DPart tree fails with a
  message suggesting region-based `SplitPdf`.
- `workflows/statements-dpart.yaml`.

**Tests**
- Unit — `DPartWriterTest`
  - `everyPageCoveredExactlyOnce`, `rangesAreOrderedAndStartBeforeEnd`
- Unit — `DPartReaderTest`
  - `readsFlatTree`
  - `readsTwoLevelTreeAtConfiguredRecordLevel` (batch → statement)
  - `overlappingRangesFail`
  - `missingRequiredDpmKeyMarksIndexError` (routes to exceptions via PR 2)
- Workflow — `IndexParityTest`: for one seed, **DPart split, region split and AFP split** produce
  the same documents, properties and zip order (three-way parity).

### PR 11 — PDF preflight step `M`

**Goal:** catch files that will print badly before they reach a printer — a routine job in
production print.

- `PreflightPdf` step with configurable checks:
  - PDF version at or below a maximum
  - not encrypted
  - every font embedded (`FontFile`/`FontFile2`/`FontFile3` in the font descriptor), for fonts
    in page resources and in every Form XObject the page draws (nested, with cycle protection);
    `allowStandard14: true|false` (default `false`)
  - page size in an allowed list (e.g. Letter ± 1 pt), no unexpected `/Rotate`
- Results stored as document properties `preflight=pass|fail` and `preflightIssues`; PR 2 rules
  route failures to `exceptions`, or `failJob: true` stops the job.
- Generator options for failing files (`--page-size a4`, `--encrypt`, `--form-xobject-font`) are
  in this PR. The switch to embedded Liberation Sans is its own follow-up (below).
- Encrypted input: in a workflow that splits first, a file that needs a password never reaches
  preflight. `SplitPdf` fails the job with the same plain words preflight uses ("file is
  encrypted, so it cannot be printed as supplied"), not PDFBox's "the password is incorrect".

**Decided in this PR**
- A file with only an owner password (no password needed to open it) splits normally. Its split
  documents come out unencrypted, so preflight passes them. This is intended (PRD-11 FR-2).
- `SplitPdfByDPart` keeps its own wording for a password-protected input ("needs a password to
  open"). It was left as it is.

**Follow-ups**
- **Liberation Sans switch** (branch `feature/11b-liberation-sans`, after PR 36 merged). The
  generator embeds Liberation Sans 2.1.5 (SIL OFL 1.1; unmodified release files and license in
  `src/main/resources/fonts/`) as a per-document subset, a simple TrueType font with
  `/WinAnsiEncoding` that PR 9's extractor reads. The sample workflow is back to the strict
  default. PR 9's fixture `two-statements.pdf` and its golden dumps were regenerated and checked
  by hand: glyph positions and widths are unchanged, only font names and objects differ.
  `--form-xobject-font` now uses a non-embedded Liberation Sans, so it fails even with
  `allowStandard14: true`.
- Group repeated per-page issues: an A4 statement lists the same page-size issue once per page.
  Report it once with a page range (for example `pages 1–3: page size … not allowed (letter)`).
- Reason for every exception: documents routed to `exceptions` for a bad address show
  `preflight=pass` and no reason, which confuses operators now that preflight failures share the
  queue. The routing step should record why each document was routed (for example an
  `exceptionReason` property in `documents.csv`).

**Tests**
- Unit — `PreflightPdfStepTest`, one pass and one fail case per check:
  - `encryptedFileFails` (PDFBox `StandardProtectionPolicy`)
  - `a4PageFailsLetterOnlyCheck`
  - `nonEmbeddedFontFails`, `embeddedFontPasses`
  - `nonEmbeddedFontInFormXObjectFails`, `embeddedFontInFormXObjectPasses`
  - `fontInNestedFormXObjectIsChecked`, `formXObjectCycleDoesNotLoop`,
    `formNestingLimitFailsWithPlainMessage`
  - `standard14AllowedWhenConfigured`
  - `versionAboveMaximumFails`
  - `issuesListNamesPageAndFont` (useful messages for operators)
- Workflow — `PreflightRoutingTest`: failing documents land in `exceptions` with their issues in
  `documents.csv`.

---

## Phase 4 — Print-shop features

### PR 12 — Barcode step `M`

**Goal:** stamp an inserter-control barcode on every page, the way mail-finishing equipment
expects.

- Add ZXing. `AddBarcode` step for PDF documents, drawn as vector modules (Q-18): Data Matrix
  (or Code 128) at a configured position/size in points (Q-21), encoding a template such as
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
  - `barcodeDrawnAsVectorAtConfiguredPosition` — uses PR 9's content-stream parser to
    check the `re`/`f` operators inside the configured rectangle
  - `sequenceIsMailingOrderAfterPresort`, `barcodeRunsAfterPresort` — `${sequence}` is
    1..N in mailing order, and the step runs in the print phase after `presort` (Q-19, Q-20)
  - `preflightStillPassesAfterStamping` (if PR 11 is merged)
- Manual: print or zoom a page and scan the barcode with a phone app.

---

## Phase 5 — Platform

### PR 13 — Persistence `L`

**Goal:** jobs and documents survive restarts and can be searched.

- PostgreSQL via Docker; H2 (PostgreSQL mode) for tests. Flyway migrations.
- Tables: `jobs`, `job_steps`, `documents` (`format` = `afp`|`pdf`, `queue`, `file_path`,
  `sequence`, `job_id`) and
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
- Jenkins stage (required, see PR 19): the same repository tests against real PostgreSQL via
  Testcontainers.

### PR 14 — REST API on Tomcat (read-only servlets) `M`

**Goal:** expose jobs and documents over HTTP from the Tomcat web app, and run the workflow engine
inside it.

- **Plain Jakarta servlets** (no JAX-RS framework), to practice the servlet API directly:
  - `JsonServlet` base class: writes JSON with Jackson, maps exceptions to the error body
    `{ "error": "...", "status": 404 }`, sets `Cache-Control: no-store`.
  - `JobsServlet` on `/api/jobs/*` and `DocumentsServlet` on `/api/documents/*`, parsing
    `getPathInfo()` for ids and sub-resources.
- Endpoints:
  - `GET /api/jobs?state=&limit=`: newest first
  - `GET /api/jobs/{id}`: job, properties, step history
  - `GET /api/jobs/{id}/documents?queue=`
  - `GET /api/documents?property=&value=&queue=&limit=&offset=`: search across jobs by property
    or queue, paged, with `X-Total-Count` ([Q-08](OPEN_QUESTIONS.md#q-08--exceptions-endpoint))
  - `GET /api/documents/{id}/file`: streams the PDF/AFP
  - `GET /api/documents/{id}/structure`: the document's structure as JSON, built from the
    `dump-afp` / `dump-pdf` readers (structured fields, or object tree + page operators)
- **Engine lifecycle in Tomcat:** a `WorkflowContextListener` (`@WebListener`) builds the
  `WorkflowEngine`, repositories and `HotFolderWatcher` on `contextInitialized`, runs the watcher
  on its own thread, and shuts it down (interrupt + executor shutdown) on `contextDestroyed`, so a
  redeploy doesn't leave threads behind. Servlets get the repositories from a `ServletContext`
  attribute.
- **Configuration** from environment variables with context-param fallbacks (`PRINTLAB_HOME`
  for `hotfolder/`, `work/`, `output/`; JDBC URL, user and password), so the same WAR runs
  locally, under Cargo, and in Docker.
- The CLI (`./mvnw exec:java`) keeps working for scripts and tests.

**Tests**
- Integration — `JobsApiTest` (`TomcatTestServer` + seeded H2)
  - `listsJobsNewestFirst`, `filtersByState`, `limitIsCapped`
  - `getJobIncludesStepHistory`
  - `unknownJobReturns404Json`
  - `invalidStateParamReturns400`
- Integration — `DocumentsApiTest`
  - `listsDocumentsForJobFilteredByQueue`
  - `searchByAccountNumberReturnsMatch`
  - `searchWithoutAnyFilterReturns400`
  - `filtersByQueueAcrossJobs`
  - `limitAndOffsetPageResultsWithTotalCount`
  - `invalidLimitOrOffsetReturns400`
  - `fileEndpointStreamsPdfWithCorrectContentType`
  - `fileEndpointDoesNotServePathsOutsideOutputDir` (path traversal guard)
  - `structureEndpointReturnsFieldsForAfpAndObjectsForPdf`
  - `structureOfCorruptFileReturns422WithOffset`
- Unit — `JsonServletTest`
  - `uncaughtExceptionBecomes500JsonWithoutStackTrace`
  - `unknownSubPathReturns404Json`
- Integration — `WorkflowContextListenerTest`
  - `startsWatcherOnStartupAndStopsItOnShutdown` (no live watcher threads after
    `contextDestroyed`)
  - `fileDroppedInHotFolderBecomesAJobVisibleInApi`
- Contract — `ApiJsonShapeTest`: response JSON for one job matches
  `fixtures/api/job.json` (the dashboard depends on this shape).
- Manual: `./mvnw package cargo:run`, drop a generated file, `curl /api/jobs`.

### PR 15 — Reprint and retry `M`

**Goal:** the two operator actions every production print system needs.

- `POST /api/documents/reprint` with document ids → a **new child job** with just those
  documents, run through a `reprint` workflow (adds a "REPRINT" marker, skips presort). The child
  job records its parent job id.
- `POST /api/jobs/{id}/retry` → re-runs a failed job from the step that failed. It first moves
  the input back from `error/<jobId>/`, where the failure put it, and refuses if it's gone.
  `WriteOutput` replaces the job's earlier output, so a retry after partial output is clean.
- Both actions are recorded in `job_steps` for an audit trail.
- **AFP reprints** (Q-07) get a `Reprint` TLE and a separate "REPRINT" text object
  (`BPT`/`PTX`/`EPT`) on every page, positioned in the page's own units from its `PGD`. Existing
  text is never edited. New AFP building blocks: `PageBuilder` (encoders moved out of
  `StatementAfpGenerator`) and `AfpRewriter` for streaming field-by-field edits. A page overlay
  comes later, in PR 21.

**Tests**
- Unit — `ReprintServiceTest`
  - `createsChildJobWithOnlySelectedDocuments`
  - `childJobRecordsParentId`
  - `originalJobIsUnchanged`
  - `documentsFromDifferentJobsAreRejected` (one source job per reprint, Q-06; extending
    across jobs is a noted follow-up)
  - `unknownDocumentIdFailsWholeRequest` (no partial reprint)
- Unit — `RetryServiceTest`
  - `retriesFromFailedStepNotFromStart` (`RecordingStep` shows steps 1 was not re-run)
  - `retryingCompletedJobIsRejected`
  - `retryIsRecordedInStepHistory`
  - `retryMovesTheInputBackFromTheErrorFolder`, `retryWithTheInputGoneIsRefused`
  - `retryAfterPartialOutputReplacesIt`
- Integration — `ActionsApiTest`
  - `reprintReturns201WithChildJobId`
  - `reprintWithEmptyListReturns400`
  - `retryUnknownJobReturns404`
  - `doubleSubmitRetryDoesNotRunTwice` (idempotency)
- Workflow — `ReprintWorkflowTest`
  - `reprintedPdfHasReprintMarkerOnEveryPage`
- Unit — `ReprintAfpTest`
  - `reprintedAfpHasReprintTleOnEveryDocument`
  - `everyPageGetsOneReprintTextObject`, `markPositionUsesPageUnitsFromPgd`
  - `pageWithoutFontMappingGetsMcfEntry`
  - `reprintingTwiceDoesNotDuplicateTheMark`
  - `reprintedAfpReadsBackCleanly`, `originalPageFieldsAreUnchanged`
- Unit — `AfpRewriterTest`, `PageBuilderTest`
- Manual: "REPRINT" shows in Papyrus AFP Viewer (screenshot in the PR).

### PR 21 — Mark AFP reprints with a page overlay `M` (any time after PR 15)

**Goal:** the production way to mark pages: a `REPRINT` overlay defined once as a resource and
referenced from every page ([PRD-21](prd/PRD-21-afp-reprint-overlay.md), Q-07).

- `OverlayWriter`: a `BRS`/`BMO … EMO`/`ERS` resource in the document's inline resource group,
  created if missing, added to if present, never duplicated.
- `MPO` in each page's environment group and `IPO` after it, at the same position as PR 15's
  mark. Both are decoded by `dump-afp`.
- A `mark: text | overlay` param on the reprint step; the default stays `text` until the overlay
  is checked in a viewer. The `Reprint` TLE is added either way.
- Medium overlays (form definitions) are out of scope, but the difference is a design note.

**Tests**
- Unit — `ReprintOverlayTest`
  - `overlayResourceIsInlineOnce`, `existingResourceGroupGetsOverlayAdded`
  - `everyPageMapsAndIncludesTheOverlay`
  - `reprintingTwiceDoesNotDuplicateTheOverlay`
  - `reprintedAfpReadsBackCleanly`, `originalPageFieldsAreUnchanged`
- Unit — `OverlayWriterTest.writesBalancedOverlayResource`,
  `SplitAfpStepTest.overlayInResourceGroupSurvivesSplit`,
  `DumpAfpTest.dumpShowsOverlayNameAndPositionForMpoAndIpo`
- Manual: both marks show in the same place in Papyrus AFP Viewer (screenshots in the PR).

---

## Phase 6 — Dashboard

### PR 16 — Marionette 4 dashboard: jobs, documents, search `L`

**Goal:** the operator view, grown from the scaffold's shell (see *Web platform*): Marionette.js
v4 Views, CollectionViews and Regions over Backbone Models and Collections, with jQuery for
DOM and Ajax. Served by Tomcat from the WAR (`src/main/webapp/`); no build step.

**Structure** (who owns what)
- `DashboardApp` (`Marionette.Application`, `region: '#app'`, `channelName: 'dashboard'`) shows
  the `ShellView` (header, nav and main regions) and starts `Backbone.history`.
- `DashboardRouter` (`Backbone.Router`) maps hash routes to the app:
  `#jobs`, `#jobs/:id`, `#search?property=...&value=...&offset=...`, `#documents/:id/structure`. Route
  handlers only call app methods; the app picks a controller and shows its View in `main`.
- Entities (`js/entities/`): `Job` / `JobCollection` (`url: 'api/jobs'`), `Document` /
  `DocumentCollection`, `StructureNode` with child nodes for nested AFP groups and PDF objects.
- Controllers (`Marionette.MnObject`), one per feature, owning data and timers:
  - `JobsController`: fetches `JobCollection`, with **auto-refresh** every 5 s. Before each
    refresh it aborts the previous in-flight request (`jqXHR.abort()`), so an old response never
    overwrites a newer one. The timer is cleared in `onDestroy`, which runs when the feature is
    left.
  - `SearchController`: each search aborts the previous request; results go into a
    `DocumentCollection`.
  - `StructureController`: loads `/api/documents/{id}/structure` into `StructureNode`s.
- Views (`js/views/`):
  - `JobsListView`: a `CollectionView` of `JobRowView`s (state badge, phase, current step) with an
    `emptyView`. Rows `trigger('select')`; the list forwards it with `childViewTriggers`, and the
    app navigates to `#jobs/:id`.
  - `JobDetailView`: a `View` with regions for properties, step timeline and documents.
    `DocumentsView` is a `CollectionView` that switches queues with `setFilter` (no re-fetch, no
    re-render of the whole detail).
  - `SearchView`: a form with a `ui` hash and `triggers: { 'submit form': 'search:submit' }`.
  - `StructureView`: a recursive `CollectionView` of `StructureNodeView`s, each showing its
    children in a nested `CollectionView` (collapsible).
- **Text-only rendering:** `<%- %>` only, everywhere; values such as account names never reach
  `innerHTML` unescaped.

**Tests** (`npm test`: Mocha specs in jsdom with `Backbone.ajax` stubbed; no network, no sleeps)
- `entities.test.mjs`
  - `jobCollectionUrlIncludesStateAndLimit`
  - `parsesFixtureJobJsonIntoModels` (loads `fixtures/api/job.json` from PR 14: the contract)
- `jobs.test.mjs`
  - `listRendersOneRowPerJobWithStateBadge`
  - `emptyListShowsEmptyView`
  - `selectingARowNavigatesToJobDetail`
  - `refreshAbortsPreviousRequest`
  - `refreshKeepsSelectedJob`
  - `pollingStopsWhenControllerIsDestroyed` (Sinon fake timers; add `sinon` as a dev dependency)
- `job-detail.test.mjs`
  - `switchingQueueFiltersDocumentsWithoutRefetch`
  - `failedLoadShowsErrorWithRetry`
- `search.test.mjs`
  - `newSearchAbortsPreviousRequest`
  - `encodesQueryValues`
  - `emptyResultShowsMessage`
  - `showsRangeAndTotalFromHeader` ("Showing 1–50 of N" from `X-Total-Count`)
  - `nextAndPreviousChangeOffset`, `pagingButtonsDisabledAtEnds`
  - `offsetInUrlRestoresPage` (back, forward and bookmarks return to the same page)
- `structure.test.mjs`
  - `rendersNestedAfpGroupsAndPdfObjects` (from fixture JSON)
  - `collapsingANodeHidesItsChildren`
- `router.test.mjs`
  - `eachRouteShowsItsView`
  - `unknownRouteShowsJobs`
- `security.test.mjs`
  - `rendersValuesAsTextNotHtml` (a name like `<img onerror=...>` shows literally, in every view)
  - `noUnescapedTemplateTagsInSource` (fails on `<%=` under `src/main/webapp/js/`)
- Manual checklist in the PR, against `./mvnw package cargo:run`: list refreshes, job detail,
  queue filter, search, open file, structure view, back/forward buttons, narrow screen.

### PR 17 — Dashboard actions `M`

- Select documents → **Reprint**; failed job → **Retry**; an **Exceptions** view listing
  documents in the exception queue across jobs.
- Views only emit intent (`triggers`: `reprint:documents`, `retry:job`); an `ActionsController`
  (`MnObject`) makes the `POST`, then refreshes the affected collection and navigates to the new
  child job.
- Confirmation before actions, inline result, buttons disabled while a request is in flight.

**Tests**
- `actions.test.mjs`
  - `reprintSendsSelectedIdsOnly`
  - `buttonsDisabledWhileRequestInFlight` (no double submit)
  - `showsErrorMessageWhenApiFails`
  - `successfulReprintRefreshesJobsAndNavigatesToChild`
  - `leavingThePageMidRequestDoesNotUpdateDestroyedViews`
- `exceptions.test.mjs`
  - `requestsExceptionsQueueWithPaging` (calls `GET /api/documents?queue=exceptions` with
    `limit`/`offset`; no separate endpoint, see Q-08)
- Manual demo script in the PR: generate a run with bad addresses → find one in Exceptions →
  reprint a good statement by account number → see the child job complete.

---

## Phase 7 — Presentation

### PR 18 — Docker Compose, demo script, README polish `S`

- Multi-stage `Dockerfile`: a Maven stage (`eclipse-temurin:21-jdk`) builds the WAR; the runtime
  stage is `tomcat:10.1-jdk21-temurin` with the WAR copied in as `webapps/ROOT.war`. No Node
  stage is needed, because the dashboard libraries are inside the WAR as WebJars.
- `docker compose up` starts Tomcat and PostgreSQL with mounted `hotfolder/` and `output/`
  (`PRINTLAB_HOME` and the JDBC settings from PR 14 come from the compose file).
- `docs/DEMO.md`: the 5-minute walkthrough, step by step.
- README: architecture diagram, a 30-second GIF of the demo, updated concept map and roadmap.
- Add a license (MIT) before making the repository public.
- Host port `${PRINTLAB_PORT:-8080}` (Q-09), listed in `.env.example` and `docs/DEMO.md`.

**Tests**
- CI job: build the Docker image.
- CI smoke test: `docker compose up -d`, wait for `/api/health` (there since the scaffold), drop a
  generated file in the hot folder, poll `/api/jobs` until the job is `COMPLETE`, then
  `docker compose down`.
- Manual: from a clean clone, follow `docs/DEMO.md` exactly.

---

## Phase 8 — CI/CD

CI/CD runs on a **Jenkins controller running locally in Docker**, and deploys to a **k3s
server on ARM**. Jenkins is the only CI: GitHub Actions is removed in PR 19, because its
minutes cost money. Jenkins runs build containers on Docker or as pods on the k3s cluster.
Decisions are in `OPEN_QUESTIONS.md` (Q-05, Q-10 to Q-17).

### PR 19 — Jenkins CI, running locally in Docker `M` (land right after PR 0)

- Uses the existing shared Jenkins, which runs in Docker with a `docker:dind` sidecar over TLS
  (Q-17) and lives outside this repository. The repository ships the `Jenkinsfile` and
  `docs/CI.md`: required plugins and tested versions, credential IDs, and how to create the
  multibranch job.
- A declarative `Jenkinsfile`, each stage in a pinned container: checkout → `./mvnw -B verify`
  → `npm ci && npm test` → PostgreSQL integration (Testcontainers, required, added by PR 13) →
  coverage report.
- JUnit results published (Surefire/Failsafe plus a Mocha JUnit reporter); JaCoCo archived.
- Agents on Docker (Testcontainers, image builds) or as pods on k3s (Kubernetes plugin).
- Jenkins polls GitHub and posts each build's result back as a commit status. Once the pipeline
  is green on `main`, delete `.github/workflows/ci.yml`.
- Credentials only in the Jenkins credential store. `docs/CI.md` explains setup.

**Tests**
- Manual, recorded in the PR: recreating the job from `docs/CI.md` works; a green build publishes
  results and coverage; a broken Java, Mocha and PostgreSQL test each fail the right stage;
  no containers are left running afterwards.

### PR 20 — Deploy to k3s on ARM `M`

- The pipeline builds the PR 18 image for `linux/arm64` (Maven stage on the build platform,
  arm64 runtime) with `docker buildx`, tags it with the commit SHA, and pushes it to
  `registry.yjimmy.dev/print-workflow-lab`.
- `deploy/k8s/` (plain YAML + Kustomize, `kubectl apply -k`): namespace; app `Deployment` with **one replica** and `Recreate` strategy (the
  hot-folder watcher must run in exactly one pod); `Service` and Traefik `Ingress`; PostgreSQL
  as an in-cluster `StatefulSet`;
  persistent volumes for `PRINTLAB_HOME`; `ConfigMap` and a `Secret` template. Readiness and
  liveness probes on `/api/health`.
- Every green `main` build deploys: set the image tag, `kubectl apply -k`,
  `kubectl rollout status`, then the smoke test against the server.
  `kubectl rollout undo` if either fails.
- The hot folder is a folder on the server shared over SMB and mounted into the pod
  (`hostPath`, pinned with a `nodeSelector`), so operators drag files onto a network drive (Q-16).
- `docs/DEPLOY.md`: k3s server, Samba share, registry and Jenkins credential setup.

**Tests**
- Pipeline: manifests validated (`kubectl apply --dry-run=server` or `kubeconform`); post-deploy
  smoke test (health → drop a file → job `COMPLETE`).
- Manual: first deploy from `docs/DEPLOY.md`; a broken build rolls back; data survives
  `kubectl delete pod`.

---

## Conventions for every PR

- One concern per PR; tests in the same PR as the code (see *Testing strategy*).
- CI (`./mvnw verify` and `npm test`) green before merging.
- Dashboard code uses only Marionette 4's public API as documented for v4.1.3; library
  upgrades are their own PR and change `pom.xml`, `index.html` and `package.json` together.
- PR description: what and why, the tests added, how to test manually, screenshots for UI changes.
- Update the README roadmap checkbox in the PR that completes the item.
