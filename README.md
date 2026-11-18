# print-workflow-lab

A small production-print workflow engine in Java, built to learn how high-volume document
workflows operate: hot-folder intake, configurable workflows, splitting a print run into
documents, indexing them with document properties, presorting, and (coming next) AFP support and
single-document reprint.

It's a learning project, not a product. The concepts follow the way enterprise print workflow
systems model jobs, documents and steps.

## The demo scenario

A billing system writes one big PDF containing hundreds of customer statements. Drop it in
`hotfolder/` and the workflow:

1. picks it up once the file has finished copying,
2. **splits** it into one PDF per statement, detecting boundaries from the account number in each
   page header,
3. **indexes** each statement: account number, name, address, zip and page count become
   document properties,
4. **routes** each statement to a queue: a bad address (blank name, zip not five digits) goes to
   `exceptions`, more than 6 pages to `large-envelope`, everything else to `standard`,
5. **presorts** statements by zip code (postal presort),
6. writes each queue's documents in order to `output/<jobId>/<queue>/`, plus one
   `documents.csv` index for the whole job.

## Concept map

| This project | Print workflow concept |
|---|---|
| `HotFolderWatcher` | Input device / hot folder |
| `workflows/*.yaml` | Workflow definition, configured not coded |
| `Step` + `StepRegistry` | Step templates and the steps built from them |
| `Job` / `Job.properties()` | Job and job properties |
| `Document` / `Document.properties()` | Documents and document properties |
| `SplitPdfStep` | Identifying PDF documents by page regions |
| `SortDocumentsStep` | Sorting documents for postal presort |
| `RouteDocumentsStep` / `when:` | Connectors with rules |
| `RunExternalProgramStep` | External steps calling other programs |
| `documents.csv` | Document properties file |
| `afp/`: `AfpReader`, `TleDecoder`, `dump-afp` | AFP page groups and TLEs as document properties (the split step is planned) |
| WAR on Tomcat, `/api/*` servlets | The workflow server and its web services |
| Marionette dashboard (`src/main/webapp/`) | The operator web interface |

## Run it

Requires Java 21. The Maven wrapper downloads Maven on first use.

```bash
./mvnw verify                                     # build + tests
./mvnw -q exec:java -Dexec.args="generate 200 run.pdf"   # make a fake 200-statement print run
./mvnw -q exec:java -Dexec.args="generate 50 bad.pdf 5"  # ...50 statements, 5 with bad addresses
./mvnw -q exec:java                               # start watching ./hotfolder
./mvnw -q exec:java -Dexec.args="dump-afp statement.afp"  # print an AFP file's structure
```

Then move `run.pdf` into `hotfolder/` and look in `output/`. On Windows use `mvnw.cmd`. In
PowerShell, quote any `-D` argument; otherwise PowerShell splits it at the dot and Maven reports
an unknown lifecycle phase:

```powershell
.\mvnw.cmd -q exec:java "-Dexec.args=generate 200 run.pdf"
```

### Where a job's files go

| Folder | What's in it |
|---|---|
| `work/<jobId>/` | The input file while it's processed, intermediate documents, and `job.json` |
| `output/<jobId>/<queue>/` | One queue's finished documents (`standard`, `exceptions`, ...) in presort order, from `00001.pdf` |
| `output/<jobId>/documents.csv` | One row per document with its properties, including `queue`; `file` is the path below `output/<jobId>/` |
| `error/<jobId>/` | Only for failed jobs: the input file and `error.txt` |

`job.json` is rewritten after every step, so it always shows how far a job has got: its id,
state, current step, error message (if it failed), job properties, document count, and each
step's start and finish time and outcome (`COMPLETED`, `FAILED`, or `SKIPPED` when the step's
`when:` was false). It's written to a temp file and then moved into place, so it's
never half-written.

When a step throws, the job stops there: later steps don't run, the input file moves to
`error/<jobId>/`, and `error.txt` says which step failed, when, and why:

```
Job:    3fc8bd7b
Input:  blank.pdf
Step:   split-and-index
Time:   2026-11-12T19:13:04.699Z
Error:  No 'accountNumber' found on page 1
```

Each failed job gets its own folder, so two failed files with the same name never overwrite each
other. If the input can't be moved, `error.txt` adds a `Note:` line saying where it was left.

### Routing rules

A `RouteDocuments` step sets each document's `queue` property. Routes are checked in order and
the first match wins; documents that match none go to `default` (without a `default`, they fail
the job). Any step can also take a `when:`, tested against the job's properties, and is skipped
when it's false. See `workflows/statements.yaml`.

A condition is `{ property, operator, value }`, or `{ all: [...] }` / `{ any: [...] }`, nested as
deep as needed (an empty `all` is true, an empty `any` false). Operators:

| Operator | True when |
|---|---|
| `equals` / `notEquals` | the value is / isn't exactly `value` |
| `matches` / `notMatches` | the **whole** value does / doesn't match the regex `value` |
| `greaterThan` / `lessThan` | compared as numbers when both sides are numbers (`10 > 9`), else as text |
| `missing` | the property is absent or blank (takes no `value`) |

An absent property never equals, matches or compares, so `notEquals` and `notMatches` are true
for it. Mistakes in rules (unknown operator, bad regex, no `value`) stop the workflow from
loading, with the step's name in the message. In double-quoted YAML a regex backslash is doubled
(`"\\d{5}"`); in single quotes it isn't (`'\d{5}'`).

### Reading AFP

An AFP (MO:DCA) file is a flat run of *structured fields*, each preceded by `0x5A`: a 2-byte
length (8 to 32,767) that counts itself but not the `0x5A`, a 3-byte type ID (`D3A8AD` is BNG,
Begin Named Page Group), a flags byte, two reserved bytes (often used as a sequence number),
then data. The reader strips padding and splits off any extension. Begin and End fields nest: document, page groups,
pages. In a statement run each page group is one customer, and the Tag Logical Elements (TLEs)
at its start are that document's index. Text inside AFP is EBCDIC (code page IBM037 by default).

`dump-afp` prints one line per field, indented between each Begin and its End, with TLE values
inline:

```
Offset    Length  Field
00000000      16  BDT  Begin Document  "STMTRUN "
00000011      16    BNG  Begin Named Page Group  "G0000001"
00000022      49      TLE  Tag Logical Element  accountNumber = "1234567890"
00000054      32      TLE  Tag Logical Element  name = "AVERY NGUYEN"
0000008E      16      BPG  Begin Page  "P0000001"
...
17 fields, 0 problems
```

Offsets are hex and lengths are each field's own length value. Problems are marked `!!` and don't
stop the dump: an End with no Begin or with a different name, a page group never closed, a
segmented field interrupted by another field, a TLE with no value. Bytes that aren't a structured
field end it with their offset. The exit code is 1 when there are problems.
For a file from another country pass its code page: `dump-afp file.afp IBM500`. A TLE that names
its own code page (an `X'01'` triplet) uses that one. The rules follow the MO:DCA Reference,
AFPC-0004-11.

### Web app on Tomcat

The project builds a WAR (`target/print-workflow-lab.war`) for Apache Tomcat 10.1
(Jakarta Servlet 6.0). To run it locally:

```bash
./mvnw package cargo:run                      # http://localhost:8080/
./mvnw package cargo:run -Dtomcat.port=8095   # if 8080 is taken
```

Cargo downloads Tomcat from Maven Central on first run and keeps it under `target/`. Stop with
Ctrl+C. To deploy to your own Tomcat instead, copy the WAR into its `webapps/` folder.

- `GET /api/health` returns `{"status":"UP","service":"print-workflow-lab"}`.
- `/` is the dashboard: **Marionette.js v4** (`backbone.marionette` 4.1.3) on Backbone 1.6.0,
  Backbone.Radio 2.0.0,
  Underscore 1.13.8 and jQuery 3.7.1. Tomcat serves the libraries from WebJars
  (`/webjars/...`), so there's no JavaScript build step. The app code is plain scripts in
  `src/main/webapp/js/`.

### Dashboard tests

The JavaScript tests are Mocha specs running in Node with jsdom, loading the same library
versions from npm:

```bash
npm ci
npm test
```

Library versions are pinned in three places: `pom.xml` (WebJars), `src/main/webapp/index.html`
(script tags) and `package.json` (tests). `versions.test.mjs` fails if they disagree, so change
all three together.

## Layout

```
src/main/java/dev/yjimmy/printlab/
  App.java                 entry point: run | generate | dump-afp
  workflow/                Job, Document, Step, WorkflowDefinition, WorkflowEngine, StepRegistry
  workflow/rules/          Condition, Rule, RuleParser: routing rules and `when:`
  steps/                   SplitPdf, RouteDocuments, SortDocuments, RunExternalProgram, WriteOutput
  input/HotFolderWatcher   folder intake
  testdata/                fake statement print-run generator (PDFBox)
  afp/                     AFP reader, field names, EBCDIC, TLE decoder, dump tool
  web/                     servlets (HealthServlet)
src/main/webapp/
  index.html               dashboard page; loads WebJars, then js/
  js/                      Marionette app: health.js, app.js, main.js
src/test/js/               dashboard tests (Mocha + jsdom)
workflows/statements.yaml  the sample workflow
```

## Roadmap

The step-by-step plan is in [`docs/PR_PLAN.md`](docs/PR_PLAN.md), with a requirements doc
(PRD) for each PR in [`docs/prd/`](docs/prd/README.md). Decisions still to be made are in
[`docs/OPEN_QUESTIONS.md`](docs/OPEN_QUESTIONS.md).

- [x] Workflow engine, YAML workflows, step registry
- [x] Hot folder intake that waits for files to finish copying
- [x] PDF split + region-based indexing, zip presort, output with document index
- [ ] **AFP:**
  - [x] Structured-field reader and dump tool
  - [ ] Test-data writer with page groups + TLEs
  - [ ] `SplitAfp` step that reads document properties from TLEs
- [x] Routing rules: send documents to an exception queue (e.g. invalid zip), flag large
      documents for a different envelope
- [ ] Barcode step (ZXing) for inserter control
- [x] Tomcat WAR, `/api/health`, Marionette 4 dashboard shell
- [ ] Persist jobs and documents (PostgreSQL), REST API (servlets on Tomcat)
- [ ] Marionette dashboard: jobs by step, document search, single-document reprint
- [ ] Docker Compose setup
- [ ] CI/CD: Jenkins running locally in Docker, deploying to a k3s server on ARM
