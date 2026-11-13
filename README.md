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
4. **presorts** statements by zip code (postal presort),
5. writes the ordered documents plus a `documents.csv` index to `output/<jobId>/`.

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
| `RunExternalProgramStep` | External steps calling other programs |
| `documents.csv` | Document properties file |
| `afp/` (planned) | AFP page groups and TLEs as document properties |
| WAR on Tomcat, `/api/*` servlets | The workflow server and its web services |
| Marionette dashboard (`src/main/webapp/`) | The operator web interface |

## Run it

Requires Java 21. The Maven wrapper downloads Maven on first use.

```bash
./mvnw verify                                     # build + tests
./mvnw -q exec:java -Dexec.args="generate 200 run.pdf"   # make a fake 200-statement print run
./mvnw -q exec:java                               # start watching ./hotfolder
```

Then move `run.pdf` into `hotfolder/` and look in `output/`. On Windows use `mvnw.cmd`.

### Where a job's files go

| Folder | What's in it |
|---|---|
| `work/<jobId>/` | The input file while it's processed, intermediate documents, and `job.json` |
| `output/<jobId>/` | The finished documents in presort order, plus `documents.csv` |
| `error/<jobId>/` | Only for failed jobs: the input file and `error.txt` |

`job.json` is rewritten after every step, so it always shows how far a job has got: its id,
state, current step, error message (if it failed), job properties, document count, and each
step's start and finish time and outcome (`COMPLETED` or `FAILED`). It's written to a temp file and then moved into place, so it's
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
  App.java                 entry point: run | generate
  workflow/                Job, Document, Step, WorkflowDefinition, WorkflowEngine, StepRegistry
  steps/                   SplitPdf, SortDocuments, RunExternalProgram, WriteOutput
  input/HotFolderWatcher   folder intake
  testdata/                fake statement print-run generator (PDFBox)
  afp/                     AFP support (planned, see package-info.java)
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
- [ ] **AFP:** structured-field reader and dump tool, test-data writer with page groups + TLEs,
      `SplitAfp` step that reads document properties from TLEs
- [ ] Routing rules: send documents to an exception queue (e.g. invalid zip), flag large
      documents for a different envelope
- [ ] Barcode step (ZXing) for inserter control
- [x] Tomcat WAR, `/api/health`, Marionette 4 dashboard shell
- [ ] Persist jobs and documents (PostgreSQL), REST API (servlets on Tomcat)
- [ ] Marionette dashboard: jobs by step, document search, single-document reprint
- [ ] Docker Compose setup
- [ ] CI/CD: Jenkins running locally in Docker, deploying to a k3s server on ARM
