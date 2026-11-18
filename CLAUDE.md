# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A learning project: a small production-print workflow engine in Java 21 (hot-folder intake →
YAML-configured steps → split/index/presort PDFs → output + `documents.csv`), packaged as a WAR
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
./mvnw -q exec:java                             # watch ./hotfolder with workflows/statements.yaml
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
  (`workflows/*.yaml`; unknown properties fail). `StepRegistry` maps a YAML step `type` to a
  `Function<Map<String,Object>, Step>` factory; built-ins are registered in `withBuiltIns()`.
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
  layout `testdata/StatementPdfGenerator` writes, so change both together. `RouteDocuments` sets
  the `queue` document property; `WriteOutput` writes each queue to `output/<jobId>/<queue>/`
  and one `documents.csv` for the job.
- **`input/HotFolderWatcher`** hands off a file only once its size stops changing. `App.run`
  moves each file into `work/<jobId>/` before processing it, so a file is never picked up twice,
  and runs jobs one at a time on a single-thread executor.
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
  `Ebcdic.encodeStrict` rejects characters the code page lacks). The `SplitAfp` step (PRD-05) is
  next. Build small AFP test input with `testsupport/AfpBytes`.
- **`testdata/`**: `StatementDataGenerator` draws the statements for a seed, `StatementLayout`
  places their text, and `StatementPdfGenerator` / `StatementAfpGenerator` write them, so one
  seed gives the same statements in both formats. Its draw order is fixed: changing it changes
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
  triplets as bytes). Always give the engine an `errorDir` under
  the temp folder.
  `web/TomcatTestServer` is embedded Tomcat on a random port for HTTP-level servlet tests.
  Fixtures go in `src/test/resources/fixtures/` and are loaded from the classpath. Update golden
  files deliberately, never to make a test pass.

## PR conventions

- Plan item numbers are not GitHub PR numbers. Branch: `feature/07-pdf-object-reader`; PR title:
  `PR 7: PDF object reader`. One concern per PR, with tests in the same PR.
- When a PR settles an open question, record the answer in `docs/OPEN_QUESTIONS.md` and update the
  PRD (and `PR_PLAN.md` if test names or scope change) in that same PR.
- Tick the README roadmap checkbox in the PR that completes the item.
- PR description: what and why, tests added, how to test manually, screenshots for UI changes.
