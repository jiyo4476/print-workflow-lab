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

| # | PR | Phase | Size | Depends on |
|---|---|---|---|---|
| 0 | Test infrastructure: coverage, fixtures, helpers | Foundation | S | — |
| 1 | Error folder and job summary file | Core | S | 0 |
| 2 | Routing rules and exception queue | Core | M | 1 |
| 3 | AFP structured-field reader + dump tool | AFP | M | 0 |
| 4 | AFP test-data writer (page groups + TLEs) | AFP | M | 3 |
| 5 | `SplitAfp` step: TLEs become document properties | AFP | M | 4 |
| 6 | Multiple input devices (folder → workflow mapping) | AFP | S | 5 |
| 7 | PDF object reader + dump tool | PDF | L | 0 |
| 8 | Modern PDF structure: xref streams, object streams, incremental updates | PDF | M | 7 |
| 9 | Content streams and text positions | PDF | L | 7 |
| 10 | PDF/VT-style document parts (DPart) indexing | PDF | M | 8, 2 |
| 11 | PDF preflight step | PDF | M | 7, 2 |
| 12 | Barcode step for inserter control | Print features | M | 0 |
| 13 | Persistence: jobs and documents in PostgreSQL | Platform | L | 1 |
| 14 | REST API on Tomcat (read-only servlets) | Platform | M | 13 |
| 15 | Reprint and retry | Platform | M | 14 |
| 16 | Marionette 4 dashboard: jobs, documents, search | Dashboard | L | 14 |
| 17 | Dashboard actions: reprint, retry, exception queue | Dashboard | M | 15, 16 |
| 18 | Docker Compose, demo script, README polish | Presentation | S | 17 |

```
0 ──► 1 ──► 2
│     └──► 13 ──► 14 ──► 15 ──► 17 ──► 18
│                  └──► 16 ──┘
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

Branch naming: `feature/NN-short-name` (e.g. `feature/03-afp-reader`).

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
  `backbone.marionette@4.1.3`, `backbone@1.6.0`, `backbone.radio@2.0.0`, `underscore@1.13.7`,
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
| **Unit** | One class in isolation: parsers, rules, steps with fake jobs | JUnit 5, `@TempDir` | Yes |
| **Workflow / integration** | A real workflow YAML end to end on generated input; repositories against a database; HTTP endpoints | JUnit 5, H2, embedded Tomcat (`TomcatTestServer`) + `java.net.http.HttpClient` | Yes |
| **Differential** | Our PDF readers against a trusted implementation (PDFBox) on the same files; for AFP, writer → reader round trips | JUnit 5 + PDFBox as oracle | Yes |
| **Dashboard** | Marionette Views, controllers and the Application: rendering, escaping, empty states, requests, polling, teardown | Mocha + jsdom (`npm test`, specs in `src/test/js/`), `Backbone.ajax` stubbed, Sinon fake timers for polling | Yes |
| **Manual** | Things that need eyes: opening AFP in a viewer, the dashboard in a real Tomcat, the demo | Checklist in the PR description | No |

Rules for every PR:

- **Tests ship in the same PR as the code.** A PR with no new tests needs a sentence explaining why.
- **Test the failure paths**, not just the happy path — bad input, missing config, truncated
  files. Production print is mostly about what happens when things go wrong.
- **Deterministic data.** Generators take a seed; tests never depend on the current time or on
  random order.
- **No test touches the real `hotfolder/`, `work/` or `output/`.** Use `@TempDir`.
- **Golden files** (expected dump output checked into `fixtures/`) are updated deliberately, never
  regenerated blindly to make a test pass.
- Test names describe behavior: `failedJobMovesInputToErrorFolder`, not `testError1`.
- CI (`./mvnw verify` and `npm test`) must be green before merging; coverage must not drop (from PR 0 on).

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
- Unit — `PdfLexerTest` (parameterized)
  - numbers: `42`, `-3`, `+7`, `3.14`, `-.5`, `4.`
  - `nameWithHexEscapeDecodes` (`/A#20B` → `A B`)
  - `literalStringWithNestedParentheses`, `literalStringEscapes` (`\n`, `\)`, `\\`, `\053`)
  - `hexStringWithOddDigitCountPadsZero` (`<901FA>` → `90 1F A0`)
  - `commentsAreSkipped`, `keywordsAreRecognized`
- Unit — `PdfParserTest`
  - `nestedDictionariesAndArrays`
  - `indirectReferenceVsTwoNumbers` (`[12 0 R]` vs `[12 0]`)
  - `streamWithDirectLength`, `streamWithIndirectLength`
  - `unterminatedDictionaryFailsWithOffset`
- Unit — `XrefReaderTest`
  - `findsStartxrefWithTrailingWhitespaceVariants` (`\n`, `\r\n`, missing final newline)
  - `parsesMultipleSubsections`, `freeEntriesResolveToNull`
  - `offsetPointingAtWrongObjectFailsWithMessage`
- Unit — `StreamDecoderTest`
  - `flateRoundTrip` (compress with `Deflater`, decode, compare)
  - `unknownFilterReportedAsUnsupported`
- Differential — `PdfReaderVsPdfBoxTest` (generated files, several seeds)
  - same page count, MediaBox per page and font resource names as PDFBox
  - `decodedContentStreamsMatchPdfBoxByteForByte`
- Golden-file — `DumpPdfTest`: dump of `fixtures/pdf/two-statements.pdf` equals
  `fixtures/pdf/two-statements.dump.txt`.
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
- Unit — `PngPredictorTest` (parameterized: None, Sub, Up, Average, Paeth against known vectors)
- Integration — `XrefStreamTest`
  - `compressedAndUncompressedSavesResolveTheSameObjects` (PDFBox default save vs
    `NO_COMPRESSION` save of the same document)
- Integration — `ObjectStreamTest`
  - `objectInsideObjectStreamResolves`
  - `objectStreamWithBadFirstOffsetFailsWithMessage`
- Integration — `IncrementalUpdateTest` (PDFBox `saveIncremental` after changing `/Info /Title`)
  - `newestRevisionWins`
  - `previousRevisionStillReadable`
  - `xrefDumpShowsTwoSectionsAndChangedObjects`
- Differential — `PdfReaderVsPdfBoxTest` extended to compressed files.

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
- Unit — `ContentStreamParserTest`
  - `operandsAttachToFollowingOperator`
  - `tjArrayWithKerningNumbers`
  - `inlineImageContainingEiBytesIsSkipped`
- Unit — `GraphicsStateTest`
  - `qAndQRestoreState`
  - `cmConcatenatesInCorrectOrder`
  - `tdMovesLineMatrix`, `tStarUsesLeading`
- Unit — `TextPositionTest`
  - `accountNumberFoundAtGeneratorPosition` (x = 400, y = 40 from top, ±0.5 pt)
  - `tjKerningShiftsFollowingGlyphs` (thousandths of text space)
- Differential — `RegionExtractorVsPdfBoxTest`: builtin and PDFBox region text identical for 100
  generated statements.
- Workflow — `BuiltinExtractorWorkflowTest`: `documents.csv` identical with `extractor: builtin`
  and `extractor: pdfbox`.
- Limitation — `cidFontReportedAsUnsupportedNotGarbled`.

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
  - every font embedded (`FontFile`/`FontFile2`/`FontFile3` in the font descriptor);
    `allowStandard14: true|false`
  - page size in an allowed list (e.g. Letter ± 1 pt), no unexpected `/Rotate`
- Results stored as document properties `preflight=pass|fail` and `preflightIssues`; PR 2 rules
  route failures to `exceptions`, or `failJob: true` stops the job.
- Generator options to produce failing files: A4 pages, encryption, and an embedded font (bundle
  an openly licensed font such as Liberation Sans) to contrast with non-embedded Helvetica.

**Tests**
- Unit — `PreflightPdfStepTest`, one pass and one fail case per check:
  - `encryptedFileFails` (PDFBox `StandardProtectionPolicy`)
  - `a4PageFailsLetterOnlyCheck`
  - `nonEmbeddedFontFails`, `embeddedFontPasses`
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
  - `barcodeDrawnAsImageXObjectAtConfiguredPosition` — uses PR 9's content-stream parser to
    check the `cm`/`Do` operators (skip if PR 9 isn't merged yet)
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
- Optional CI job: the same repository tests against real PostgreSQL via Testcontainers.

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
  - `GET /api/documents?property=accountNumber&value=...`: search
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
  - `searchWithoutPropertyReturns400`
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

## Phase 6 — Dashboard

### PR 16 — Marionette 4 dashboard: jobs, documents, search `L`

**Goal:** the operator view, grown from the scaffold's shell (see *Web platform*): Marionette.js
v4 Views, CollectionViews and Regions over Backbone Models and Collections, with jQuery for
DOM and Ajax. Served by Tomcat from the WAR (`src/main/webapp/`); no build step.

**Structure** (who owns what)
- `DashboardApp` (`Marionette.Application`, `region: '#app'`, `channelName: 'dashboard'`) shows
  the `ShellView` (header, nav and main regions) and starts `Backbone.history`.
- `DashboardRouter` (`Backbone.Router`) maps hash routes to the app:
  `#jobs`, `#jobs/:id`, `#search?property=...&value=...`, `#documents/:id/structure`. Route
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
  - `JobsListView`: a `CollectionView` of `JobRowView`s (state badge, current step) with an
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
- Integration — `ExceptionsApiTest` (if a new endpoint is added for the cross-job view)
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

**Tests**
- CI job: build the Docker image.
- CI smoke test: `docker compose up -d`, wait for `/api/health` (there since the scaffold), drop a
  generated file in the hot folder, poll `/api/jobs` until the job is `COMPLETE`, then
  `docker compose down`.
- Manual: from a clean clone, follow `docs/DEMO.md` exactly.

---

## Conventions for every PR

- One concern per PR; tests in the same PR as the code (see *Testing strategy*).
- CI (`./mvnw verify` and `npm test`) green before merging.
- Dashboard code uses only Marionette 4's public API as documented for v4.1.3; library
  upgrades are their own PR and change `pom.xml`, `index.html` and `package.json` together.
- PR description: what and why, the tests added, how to test manually, screenshots for UI changes.
- Update the README roadmap checkbox in the PR that completes the item.
