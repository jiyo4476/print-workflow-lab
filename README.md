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

## Run it

Requires Java 21. The Maven wrapper downloads Maven on first use.

```bash
./mvnw verify                                     # build + tests
./mvnw -q exec:java -Dexec.args="generate 200 run.pdf"   # make a fake 200-statement print run
./mvnw -q exec:java                               # start watching ./hotfolder
```

Then move `run.pdf` into `hotfolder/` and look in `output/`. On Windows use `mvnw.cmd`.

## Layout

```
src/main/java/dev/yjimmy/printlab/
  App.java                 entry point: run | generate
  workflow/                Job, Document, Step, WorkflowDefinition, WorkflowEngine, StepRegistry
  steps/                   SplitPdf, SortDocuments, RunExternalProgram, WriteOutput
  input/HotFolderWatcher   folder intake
  testdata/                fake statement print-run generator (PDFBox)
  afp/                     AFP support (planned, see package-info.java)
workflows/statements.yaml  the sample workflow
```

## Roadmap

- [x] Workflow engine, YAML workflows, step registry
- [x] Hot folder intake that waits for files to finish copying
- [x] PDF split + region-based indexing, zip presort, output with document index
- [ ] **AFP:** structured-field reader and dump tool, test-data writer with page groups + TLEs,
      `SplitAfp` step that reads document properties from TLEs
- [ ] Routing rules: send documents to an exception queue (e.g. invalid zip), flag large
      documents for a different envelope
- [ ] Barcode step (ZXing) for inserter control
- [ ] Persist jobs and documents (PostgreSQL), REST API
- [ ] JavaScript dashboard: jobs by step, document search, single-document reprint
- [ ] Docker Compose setup
