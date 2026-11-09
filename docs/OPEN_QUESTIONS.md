# Open questions

Decisions the PRDs leave to the PR that implements them. When you start a PR, check this file
for its PRD, settle each question in that PR, and record the answer here.

**When a question is answered**

1. Fill in **Answer**, change **Status** to `Answered`, and add the PR link.
2. Update the PRD (requirements, design notes and tests) to match the answer.
3. Update `docs/PR_PLAN.md` too if the answer changes a test name or the PR's scope.

The leanings below are suggestions, not decisions.

## Summary

| ID | PRD | Question | Status |
|---|---|---|---|
| [Q-01](#q-01--exception-and-queue-csv-layout) | [PRD-02](prd/PRD-02-routing-rules-and-exception-queue.md) | One CSV per queue, or one CSV with a `queue` column? | Open |
| [Q-02](#q-02--real-afp-sample-for-the-manual-check) | [PRD-03](prd/PRD-03-afp-structured-field-reader.md) | Is there a freely shareable real AFP file for the manual check? | Open |
| [Q-03](#q-03--build-the-afp-writer-or-check-in-a-sample) | [PRD-04](prd/PRD-04-afp-test-data-writer.md) | Build the AFP test-data writer, or check in a sample AFP file? | Open |
| [Q-04](#q-04--tle-name-mapping) | [PRD-05](prd/PRD-05-split-afp-step.md) | Add an optional `rename:` map for TLE names? | Open |
| [Q-05](#q-05--require-the-postgresql-ci-job) | [PRD-13](prd/PRD-13-persistence.md) | Make the Testcontainers PostgreSQL job required? | Open |
| [Q-06](#q-06--reprint-across-jobs) | [PRD-15](prd/PRD-15-reprint-and-retry.md) | Can one reprint combine documents from different jobs? | Open |
| [Q-07](#q-07--marking-afp-reprints) | [PRD-15](prd/PRD-15-reprint-and-retry.md) | How are reprinted AFP documents marked? | Open |
| [Q-08](#q-08--exceptions-endpoint) | [PRD-17](prd/PRD-17-dashboard-actions.md) | Reuse document search for the Exceptions view, or add `GET /api/exceptions`? | Open |
| [Q-09](#q-09--host-port) | [PRD-18](prd/PRD-18-docker-demo-and-polish.md) | Which host port does Compose use by default? | Open |
| [Q-10](#q-10--fake-bank-name) | [PRD-18](prd/PRD-18-docker-demo-and-polish.md) | Is the generator's fake bank name a real institution? | Open |

## PRD-02 — Routing rules and exception queue

### Q-01 — Exception and queue CSV layout

- **Question:** does the job write one CSV per queue (`standard.csv`, `exceptions.csv`, …), or
  one `documents.csv` with a `queue` column?
- **Options:**
  - **One per queue:** matches how inserter lines take their own output.
  - **One combined:** easier for the dashboard and for reporting.
- **Leaning:** none yet.
- **Affects:** PRD-02 FR for the job summary, its CSV tests, and what PRD-14 and PRD-16 read.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-03 — AFP structured field reader

### Q-02 — Real AFP sample for the manual check

- **Question:** is there a real AFP file with a license that allows checking it in or sharing
  it, to run the reader against by hand?
- **Fallback:** if not, the manual check uses PRD-04 output, and the PR says so.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-04 — AFP test-data writer

### Q-03 — Build the AFP writer, or check in a sample

- **Question:** build the writer, or skip PRD-04 and check a sample AFP file with page groups
  and TLEs into `fixtures/afp/`, then test PRD-05 against it?
- **Trade-off:** the writer teaches AFP structure and gives seeded, varied test data. A fixture is
  faster but fixed.
- **Knock-on:** if skipped, PRD-10's `IndexParityTest` compares DPart with region only (see
  PRD-10).
- **Leaning:** build it unless time is short.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-05 — Split AFP step

### Q-04 — TLE name mapping

- **Question:** add an optional `rename:` map in the step params (for example
  `ACCT_NO: accountNumber`), since TLE names vary between files?
- **Leaning:** leave it out unless a sample file needs it.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-13 — Persistence

### Q-05 — Require the PostgreSQL CI job

- **Question:** make the Testcontainers PostgreSQL job a required CI check, or keep it optional?
- **Trigger:** H2's PostgreSQL mode differs from real PostgreSQL in edge cases such as
  `ON CONFLICT`. Make it required if those differences cause a bug.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-15 — Reprint and retry

### Q-06 — Reprint across jobs

- **Question:** can one reprint request combine documents from different jobs?
- **Trade-off:** allowing it fits real use, since a damaged inserter tray can mix jobs, but a child
  job then has several parents.
- **Affects:** the data model for the parent link, and the test
  `documentsFromDifferentJobsCanBeReprintedTogether` (or its rejecting counterpart).
- **Answer:** —
- **Status:** Open · **PR:** —

### Q-07 — Marking AFP reprints

- **Question:** for AFP, is the "REPRINT" marker added as a PTOCA text overlay, or left off?
- **Affects:** the `reprint` workflow, and whether `ReprintWorkflowTest` needs an AFP case.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-17 — Dashboard actions

### Q-08 — Exceptions endpoint

- **Question:** should the Exceptions view use
  `GET /api/documents?property=queue&value=exceptions`, or a dedicated `GET /api/exceptions`?
- **Leaning:** reuse the search endpoint, unless it can't page or sort well enough.
- **Affects:** whether `ExceptionsApiTest` exists.
- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-18 — Docker demo and polish

### Q-09 — Host port

- **Question:** which host port does Compose use by default?
- **Background:** 8080 was taken during the scaffold's manual check.
- **Leaning:** make it configurable as `${PRINTLAB_PORT:-8080}`, and mention it in
  `docs/DEMO.md`.
- **Answer:** —
- **Status:** Open · **PR:** —

### Q-10 — Fake bank name

- **Question:** is the bank name used by `StatementPdfGenerator` a real institution?
- **Action:** check before making the repository public, and rename it if it is.
- **Answer:** —
- **Status:** Open · **PR:** —
