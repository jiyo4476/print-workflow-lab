# PRD-13 — Persistence: jobs and documents in PostgreSQL

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Platform | L | 01 | `feature/13-persistence` |

## Summary

Store jobs, step history, documents and document properties in PostgreSQL (H2 in PostgreSQL mode
for tests), using Flyway migrations and plain JDBC repositories. The engine saves after every
step through a `JobStore` interface.

## Why

Without a database, everything the system knows lives in folders and disappears with them, and
nothing can be searched. Operators need to find "the statement for account 1234567890 from last
Tuesday" in seconds. The API (PRD-14), reprint (PRD-15) and dashboard (PRD-16) all depend on this.
Plain JDBC rather than an ORM is deliberate practice in SQL schema design and queries.

## Users and scenarios

- **Operator** (through PRD-14/16) searches documents by account number across all jobs.
- **Developer** restarts the app; completed and failed jobs are still there.

## Goals

- Flyway migrations for `jobs`, `job_steps`, `documents`, `document_properties`.
- `JobRepository` and `DocumentRepository` in plain JDBC.
- A `JobStore` interface with file (`job.json`, from PRD-01) and JDBC implementations; the engine
  saves after every step.

## Non-goals

- An ORM, connection pooling beyond a simple `DataSource`, multi-tenant schemas.
- Storing document files in the database; files stay on disk, and the database holds their
  paths.
- Retention and archiving.

## Requirements

**Functional**
- **FR-1** Tables:
  - `jobs`
  - `job_steps` (ordered)
  - `documents`, with columns `format` (`afp` / `pdf`), `queue`, `file_path`, `sequence` and
    `job_id`
  - `document_properties` (name/value rows, indexed on `(name, value)`)
- **FR-2** Migrations apply to an empty database, and a second run is a no-op.
- **FR-3** `JobRepository`: insert, find by id, update state/current step, ordered step history,
  list newest first with paging, filter by state.
- **FR-4** `DocumentRepository`: insert with properties, find by job, find by property value, find
  by queue.
- **FR-5** Deleting a job cascades to its documents and properties.
- **FR-6** Saving a job with its documents is one transaction; a failure leaves no partial rows.
- **FR-7** A job completed before a restart is readable after it; a failed job is stored with its
  error and failing step.

**Non-functional**
- **NFR-1** Every query uses `PreparedStatement` parameters; no string-built SQL.
- **NFR-2** Repository tests run against H2 with a fresh schema per test. An optional CI job runs
  them on real PostgreSQL via Testcontainers.

## Design notes

- PostgreSQL runs in Docker for local use (Compose arrives fully in PRD-18; a minimal
  `docker run` line in the README is enough here).
- `JobStore` keeps the CLI usable without a database (the file implementation).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Migrations apply and are idempotent | `MigrationTest.*` |
| AC-2 | Job CRUD, history order, paging and filtering work | `JobRepositoryTest.*` |
| AC-3 | Document storage, search by property and by queue work; delete cascades | `DocumentRepositoryTest.*` |
| AC-4 | Saves are transactional | `saveIsTransactional` |
| AC-5 | State survives an engine restart; failures are stored | `PersistentWorkflowTest.*` |

## Test plan

- Integration — `MigrationTest`: `migrationsApplyToEmptyDatabase`,
  `migrationsAreRepeatableNoOpOnSecondRun`
- Integration — `JobRepositoryTest`: `insertAndFindById`, `updateStateAndCurrentStep`,
  `stepHistoryIsOrdered`, `listNewestFirstWithPaging`, `filterByState`
- Integration — `DocumentRepositoryTest`: `insertWithPropertiesAndFindByJob`,
  `findByPropertyValue`, `findByQueue`, `deletingJobCascadesToDocumentsAndProperties`,
  `saveIsTransactional`
- Workflow — `PersistentWorkflowTest`: `completedJobIsReadableAfterEngineRestart`,
  `failedJobIsStoredWithErrorAndStep`
- Optional CI: the same repository tests on PostgreSQL via Testcontainers.

## Risks and open questions

- H2's PostgreSQL mode differs from real PostgreSQL in edge cases (e.g. `ON CONFLICT`). That's
  why the Testcontainers job matters; make it required if H2 differences bite.
- Size **L**: split into (a) schema + repositories and (b) `JobStore` + engine integration if
  needed.

## Definition of done

- Shared DoD, plus: README shows how to start PostgreSQL locally and which settings the app reads.
