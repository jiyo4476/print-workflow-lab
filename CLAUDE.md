# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A learning project: a small production-print workflow engine in Java 21 (hot-folder intake with
an optional job ticket → YAML workflows whose steps are grouped into ProcessDirector's six phases
→ split/index (PDF or AFP), route, presort → output + `documents.csv`), packaged as a WAR
for Tomcat 10.1 with a Marionette.js v4 dashboard. Work proceeds through a numbered plan:
`docs/PR_PLAN.md` (order, testing strategy, conventions), one PRD per item in `docs/prd/`, and
undecided design points in `docs/OPEN_QUESTIONS.md`.

## Commands

Use `./mvnw` (or `mvnw.cmd` on Windows); it downloads Maven on first use.

```bash
./mvnw verify                                   # build, Java tests, JaCoCo report + coverage gate
./mvnw test -Dtest=WorkflowEngineTest           # one test class (no coverage gate in `test`)
./mvnw test -Dtest=WorkflowEngineTest#someMethod  # one test method
./mvnw -q exec:java -Dexec.args="generate 200 run.pdf"  # fake 200-statement print run
./mvnw -q exec:java -Dexec.args="generate 50 bad.pdf 5" # ...with 5 bad addresses
./mvnw -q exec:java -Dexec.args="generate-afp 50 run.afp 5 42" # same run as AFP; seed 42
./mvnw -q exec:java -Dexec.args="generate-afp 50 run.afp --tle-names legacy" # ACCT_NO, ... TLEs
./mvnw -q exec:java                             # start the input devices in config/input-devices.yaml
./mvnw -q exec:java -Dexec.args="run workflows/statements.yaml"  # one device on ./hotfolder
./mvnw package cargo:run [-Dtomcat.port=8095]   # WAR on Tomcat at http://localhost:8080/

npm ci && npm test                              # dashboard tests (Mocha + jsdom, Node >= 22)
npx mocha --grep marionetteIsVersion4           # one dashboard test; passing a file path still
                                                # runs every spec because .mocharc.json lists them
```

CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` and `npm ci --ignore-scripts && npm test`.
Both must be green. (Q-10 decided to move CI to Jenkins only, in PRD-19.)

**Coverage gate:** `coverage.line.minimum` in `pom.xml` (JaCoCo; `testdata/` and `App` are
excluded). Never lower it to get a change through; add tests. To raise it, set it to the
report's line total rounded *down* to two decimals, in its own commit.

## Architecture

- **`workflow/`** is the core. `WorkflowDefinition` is a record loaded from YAML
  (`workflows/*.yaml`; unknown properties fail). Steps are grouped under `phases:` into the six
  ProcessDirector phases (`Phase`: receive, prepare, assemble, print, insert, complete), which
  must be listed in that order; `steps()` is the flat list with each `StepDefinition.phase()`,
  and the record's constructor rejects steps out of phase order. In code,
  `new StepDefinition(name, type, params)` is in `prepare`; use `.inPhase(...)` for another.
  `Job.phase()` starts at receive, follows the running step, ends at complete (a failed job keeps
  the failed step's phase); `StepRecord`, `job.json` and `error.txt` carry it. `StepRegistry`
  maps a YAML step `type` to a `Function<Map<String,Object>, Step>` factory; built-ins are registered in `withBuiltIns()`.
  `WorkflowEngine(definition, registry, errorDir, clock)` builds **all** steps (and parses their
  `when:` conditions) in its constructor, so bad config fails at startup, then runs them in
  order; a step whose `when:` is false for the job's properties is recorded as `SKIPPED`.
  `StepRegistry.create` prefixes a factory's `IllegalArgumentException` with the step name. It times each step with
  the injected `Clock` into a `StepRecord` on the job and rewrites `work/<jobId>/job.json`
  (`JobSummaryWriter`, atomic temp-file + move) after each one. The first exception puts the job
  in `ERROR`, records the step as `FAILED`, stops the run, and `ErrorFolder` moves the input to
  `error/<jobId>/` with an `error.txt`. `Job` holds job properties and an ordered, mutable list
  of `Document` records, each with its own property map. Steps communicate only by changing the
  job's documents and properties.
- **`workflow/rules/`** is the rule model shared by `when:` and `RouteDocuments`: a sealed
  `Condition` (`Rule`, `All`, `Any`) built by `RuleParser`, which rejects every config mistake
  and compiles regexes at load time.
- **`steps/`** are the step implementations. Each takes its raw YAML params in the constructor and
  reads them through the package-private `Params` helper, whose errors name the missing param. To
  add a step type: implement `Step`, register it in `StepRegistry.withBuiltIns()`, and use it from YAML.
  `SplitPdfStep` finds document boundaries and properties from text in page regions
  (`[x, y, w, h]` in points from the top-left). The regions in `statements.yaml` match the
  layout `testdata/StatementLayout` describes, so change both together. Its optional
  `newDocumentWhen` (rule syntax, tested against the page's region values) starts a document even
  when `splitOn` repeats; a PDF with no pages fails. `SplitAfpStep` is the AFP counterpart: one
  document per top-level BNG/ENG, written as a standalone AFP file (fields before the first
  group + fields between groups + the group + Ends for the Begins still open), with the group's
  own TLEs as properties. Both split steps set `pageCount` and `format` (`pdf`/`afp`).
  Per-document index problems (`requiredProperties`, repeated TLEs, `MapProperties` rename
  conflicts) are appended to the `indexError` property through `IndexErrors`, never thrown, so
  routing sends them to `exceptions`; structural problems in the file fail the job.
  `MapPropertiesStep` renames properties (all at once; every name comes from the YAML).
  `SetJobPropsFromTextFileStep` (first in every sample workflow's receive phase) reads the job
  ticket `<input>.jobprops` (`name=value` lines) from the work folder into job properties
  (`PROTECTED` names such as `inputFileName` are ignored); no file is a no-op unless
  `required: true`. `ErrorFolder` moves a failed job's ticket next to its input.
  `RouteDocuments` sets the `queue` document
  property; `WriteOutput` writes each queue to `output/<jobId>/<queue>/`, keeping each
  document's file extension, and one `documents.csv` for the job, whose `position`, `sequence`
  and `file` columns are reserved (a property with one of those names fails the step).
- **`input/`**: `InputDeviceConfig` loads `config/input-devices.yaml` (`{name, folder, glob,
  workflow}`) and validates it: unique names, **one device per folder** (compared normalized,
  case-insensitively on Windows), valid globs, existing workflow files. `InputDevices` builds
  every device's `WorkflowEngine` up front (bad workflow → startup fails), runs one
  `HotFolderWatcher` thread per device, and queues claimed jobs on one shared single-thread
  executor; its `onJobDone` callback is how tests wait without sleeping. A watcher that fails
  (folder deleted or not creatable) is restarted after the rescan interval; a file claimed after
  `close()` has shut the job queue goes back to its folder (`JobIntake.unclaim`). `JobIntake.claim(file,
  device)` sets the `inputDevice` job property, which a ticket can't override
  (`SetJobPropsFromTextFileStep.PROTECTED`). `App run` with no arguments uses the devices file;
  `run <workflow> [glob]` is a single `default` device on `hotfolder/`.
- **`input/HotFolderWatcher`** hands off a file only once its size (and its ticket's) stops
  changing, never hands off `*.jobprops` tickets, and logs a non-matching file once while it
  stays. Its handler must **claim** the file (move it out of the folder) before returning;
  `JobIntake.claim` moves its `<input>.jobprops` ticket (name matched ignoring case) first and then the input into
  `work/<jobId>/`; if either move fails, both stay put. Because anything
  still in the folder is unclaimed, the watcher can safely rescan: at once on `OVERFLOW`, and every 5 s so a file whose claim failed (locked on
  Windows) is retried. The glob ignores case. Jobs run one at a time across all devices.
- **Interrupts:** `WorkflowEngine` fails an interrupted job (an `InterruptedException`, or any
  exception while the flag is set) with an "interrupted" message, clears the flag while it writes
  the error folder, then restores it. `RunExternalProgram` kills the program and its children on
  interrupt or timeout.
- **`web/`** holds the servlets, registered with `@WebServlet` (there is no `web.xml`). Currently
  only `GET /api/health`.
- **`afp/`** reads MO:DCA, following the MO:DCA Reference AFPC-0004-11. `AfpReader` is a
  streaming `Iterator<StructuredField>` (one field in memory; bad bytes throw
  `AfpFormatException` with the byte offset); it enforces lengths 8-32,767, splits off the
  extension and strips padding. Bytes 6-7 are `reserved()`, not a sequence number. `FieldType`
  lists all 98 structured fields from Appendix E; Begin/End is the middle ID byte (`A8`/`A9`).
  `TleDecoder` reads triplets `01` (code page), `02` type `0B` (name) and `36` (value); text is
  EBCDIC via `Ebcdic` (IBM037 default, code page is a parameter).
  `AfpDump` backs `App dump-afp` and reports structural problems instead of stopping.
  `AfpWriter` and `TleEncoder` are the inverses (lengths computed, reserved bytes `0000`,
  `Ebcdic.encodeStrict` rejects characters the code page lacks).
  `AfpWriter.write(StructuredField)` copies a field read from a file (padding dropped). Build
  small AFP test input with `testsupport/AfpBytes`.
- **`pdf/`** reads PDF without PDFBox (ISO 32000-1, section 7); PDFBox stays as the test
  oracle (`PdfParserVsPdfBoxTest`, `PdfReaderVsPdfBoxTest`). `PdfLexer` tokenizes a byte array (it can `seek`, because
  PDFs are read from xref offsets) and decodes name `#xx` and string escapes. `PdfParser` builds
  the sealed `PdfObject` records (`PdfDictionary`, `PdfArray`, `PdfName`, `PdfString` (bytes),
  `PdfNumber`, `PdfBoolean`, `PdfNull`, `PdfReference`, `PdfStream` (raw, still filtered
  bytes)). It looks two tokens ahead to tell `12 0 R` from two numbers, and reads
  `n g obj ... endobj` with stream data, sized by `/Length`, which may be a reference resolved
  through the constructor's resolver. Null dictionary entries are dropped; nesting is capped at
  500 so bad input can't overflow the stack. Errors are `PdfFormatException` with the byte
  offset. `XrefReader` finds the last `startxref` and follows the `/Prev` chain (a loop
  fails), reading each `Section`: a classic table, a cross-reference stream (`/W`, `/Index`), or
  a hybrid table with `/XRefStm`. Sections are revisions numbered from the oldest; each object's
  newest entry wins, and `revision(n)` sees only sections 1 to n. An `Entry` is `Free`, `InFile`
  or `Compressed` (inside an object stream, decoded once and cached). Objects resolve lazily
  (cached; free, missing or wrong-generation references are `PdfNull`).
  `StreamDecoder` undoes `/FlateDecode` (with a decoded-size cap) and PNG predictors 10-15
  (`PngPredictor`), and throws `UnsupportedFilterException` for anything else, including the
  TIFF predictor.
  `ContentStreamParser` turns a page's decoded content (`PdfDocumentReader.content(page)`, its
  streams joined with newlines) into `Operation(operator, operands, offset)`; operands are the
  ordinary objects, any other keyword is an operator (not checked against `ContentOperators`,
  Annex A's 73). An inline image is one `BI` operation whose operand is a `PdfStream`; its data
  ends by `/L`, else the size its parameters give when unfiltered, else (or when that size
  doesn't end at `EI`) a scan for an `EI` followed by content. Operands are capped (1,000 per
  operator, 100,000 items per array); operators print with `#xx` escapes (`PdfLexer.printable`). PDFBox's `PDFStreamParser` is the oracle for operators but not inline
  images (3.0.3 loses what follows them).
  `PdfDocumentReader` reads the version (header, raised by the catalog's `/Version`; none is
  rejected), the catalog, and the page tree, walked with a stack (a node seen twice fails), with
  `Resources`, `MediaBox`, `CropBox` and `Rotate` inherited into each `PdfPage`. `PdfString.text()`
  decodes UTF-16BE, UTF-8 (PDF 2.0) and PDFDocEncoding. `PdfDump` backs `App dump-pdf` (and
  `dump-pdf --xref`, the sections and what each update changed) and `App dump-content`
  (operators indented inside `q`/`Q`, `BT`/`ET`, marked content, each described); names
  and strings print as PDF syntax, so file bytes never reach the terminal raw. Build small PDF
  test input with `testsupport/PdfBytes` (classic tables, cross-reference streams, object
  streams and incremental updates, all uncompressed); `testsupport/ClassicPdf` writes a seeded
  statement PDF with a classic table and a fixed `/ID` (the `fixtures/pdf/` fixture), while
  `generate` saves PDFBox's default compressed form.
- **`testdata/`**: `StatementDataGenerator` draws the statements for a seed, `StatementLayout`
  places their text, and `StatementPdfGenerator` / `StatementAfpGenerator` write them, so one
  seed gives the same statements in both formats (`AfpStatementWorkflowTest` checks that the PDF
  and AFP workflows give the same index). `StatementAfpGenerator.TleNames` picks the TLE names
  (`PROPERTY_NAMES` or `LEGACY`). Its draw order is fixed: changing it changes
  every seeded run. The package is excluded from the coverage gate.
- **Dashboard** (`src/main/webapp/`): plain browser scripts with no JS build. `index.html` loads
  the libraries from WebJars (`/webjars/<name>/<version>/...`) as globals, in a fixed order, then
  `js/*.js` (an IIFE namespace `PrintLab`; `main.js` starts the app). The tests in
  `src/test/js/support/dashboard-dom.mjs` load the same scripts into jsdom, mapping WebJar URLs to
  `node_modules`, skip `main.js`, and stub `Backbone.ajax`.

## Rules that tests enforce or the plan requires

- **Library versions are pinned in three places:** `pom.xml` properties (WebJars), the script
  tags in `src/main/webapp/index.html`, and `package.json`. `versions.test.mjs` fails if they
  disagree. Upgrades are their own PR and change all three. Use only Marionette 4.1.3's public
  v4 API.
- **`package.json` `overrides` pin `glob` 14.0.2 and `minimatch` 11.0.1** under mocha 12.0.3,
  which still asks for `glob@^13` / `minimatch@^10.2` (GHSA-3g3m-2fjr-6pw4, GHSA-g3hv-75wg-m249).
  Drop the overrides once a mocha release depends on patched versions; `npm audit` should stay
  at 0. Don't run `npm audit fix --force`: it downgrades mocha to 11.0.0, which is still affected.
- **Templates must escape:** use `<%- %>`, never `<%= %>`. `security.test.mjs` scans
  `src/main/webapp/js`. More generally, scraped or file-derived values are always shown as text,
  never HTML (logs, API JSON, dashboard).
- **Java tests use JUnit 4 only** (4.13.2 + AssertJ). Do not add JUnit 5 or Vintage. Test classes
  and methods are `public`; use the `TemporaryFolder` rule for files and `assertThrows`; put the
  message first in `assertEquals(message, expected, actual)`. Name tests after behavior
  (`failedJobMovesInputToErrorFolder`).
- **Deterministic tests:** seeded generators, no wall-clock time, no fixed sleeps. Never touch the
  real `hotfolder/`, `work/` or `output/`.
- **Test helpers** in `src/test/java/.../testsupport/`: `TestJobs.newJob` (job in a temp folder,
  fixed id `test0001`), `TestWorkflows.of`/`step`/`load` (`load` reads a real YAML and redirects
  `WriteOutput.outputDir`), `RecordingStep` (records calls, can throw on the Nth call),
  `TickingClock` (deterministic clock, +1 s per read), `AfpBytes` (builds AFP fields, TLEs and
  triplets as bytes), `PdfBytes` (builds a PDF from object text, with the xref offsets worked
  out), `ClassicPdf` (a seeded statement PDF, same bytes every time). Always give the engine an `errorDir` under
  the temp folder.
  `web/TomcatTestServer` is embedded Tomcat on a random port for HTTP-level servlet tests.
  Fixtures go in `src/test/resources/fixtures/` and are loaded from the classpath. Update golden
  files deliberately, never to make a test pass.

## PR conventions

- "Phase" means two things here: the **workflow phases** (`Phase`, receive ... complete) and the
  **plan phases** in `PR_PLAN.md`'s headings and the PRD tables' "Plan phase" column. Say which.

- Plan item numbers are not GitHub PR numbers. Branch: `feature/07-pdf-object-reader`; PR title:
  `PR 7: PDF object reader`. One concern per PR, with tests in the same PR.
- When a PR settles an open question, record the answer in `docs/OPEN_QUESTIONS.md` and update the
  PRD (and `PR_PLAN.md` if test names or scope change) in that same PR.
- Tick the README roadmap checkbox in the PR that completes the item.
- PR description: what and why, tests added, how to test manually, screenshots for UI changes.
