# PRD-01 — Error folder and job summary file

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Core | S | 00 | `feature/01-error-folder` |

## Summary

When a job fails, move its input file to `error/<jobId>/` with an `error.txt` explaining what
failed. Every job, failed or not, keeps a `job.json` summary that's rewritten after each step,
with per-step start and end times.

## Why

Today a failed job only leaves a log line, and the input file sits in `work/` with nothing
explaining it. In production print, the first questions an operator asks are "what failed, where,
and when?" and "where is the file now?" A predictable error folder and a machine-readable job
record answer both. They're also the base for persistence (PRD-13), retry (PRD-15) and the
dashboard's step timeline (PRD-16).

## Users and scenarios

- **Operator** sees a file vanish from the hot folder, opens `error/` and reads `error.txt`:
  "step `split-and-index` failed at 14:02:11: No 'accountNumber' found on page 37".
- **Developer** opens `work/<jobId>/job.json` to see which steps ran and how long each took.

## Goals

- Failed jobs move their input to `error/<jobId>/` with an `error.txt`.
- `work/<jobId>/job.json` after every step.
- A `StepRecord` history on `Job`, timed with an injectable `Clock`.

## Non-goals

- Automatic retry (PRD-15), notifications, or log aggregation.
- Cleaning up old `work/` and `error/` folders.

## Requirements

**Functional**
- **FR-1** On failure, the engine moves the job's input file to `error/<jobId>/<originalName>`.
- **FR-2** `error/<jobId>/error.txt` contains the step name, the error message and an ISO-8601
  timestamp.
- **FR-3** `Job` holds a list of `StepRecord(name, startedAt, finishedAt, outcome)`, where
  `outcome` is `COMPLETED` or `FAILED` (PRD-02 adds `SKIPPED`).
- **FR-4** Steps after a failure are not run and not recorded.
- **FR-5** After each step, `work/<jobId>/job.json` holds: id, state, current step, error
  message (null unless the job failed), job properties, document count and step records.
- **FR-6** Two failed jobs whose input files have the same name never overwrite each other.

**Non-functional**
- **NFR-1** `job.json` is written atomically (temp file + move), so a reader never sees half a
  file.
- **NFR-2** All times come from an injected `java.time.Clock`.

## Design notes

- `JobSummaryWriter` (Jackson) writes `job.json`; it reads back into the same shape for tests and
  for PRD-13's file-based `JobStore`.
- A failure whose exception has no message is described by its class name, so `error.txt`
  never says `null`.
- If the input can't be moved to `error/<jobId>/`, `error.txt` gets a `Note:` line saying where
  it was left. Failing to write `job.json` is logged, not a job failure: it's a diagnostic record.
- The error folder root is configurable alongside `hotfolder`/`work`/`output` (PRD-14 moves all
  of them under `PRINTLAB_HOME`).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Every step gets a start and finish time from the injected clock | `WorkflowEngineTest.recordsStartAndFinishForEveryStep` |
| AC-2 | The failing step is recorded as `FAILED`; later steps are absent | `failedStepIsRecordedWithOutcomeFailed`, `stepsAfterFailureAreNotRecorded` |
| AC-3 | `job.json` round-trips through Jackson and is rewritten after each step | `JobSummaryWriterTest.*` |
| AC-4 | A failed job's input is in `error/<jobId>/` with a descriptive `error.txt` | `ErrorHandlingTest.failedJobMovesInputToErrorFolder`, `errorTxtContainsStepNameAndMessage` |
| AC-5 | Successful jobs leave `error/` empty; same-named failures don't collide | `successfulJobLeavesNothingInErrorFolder`, `twoFailedJobsWithSameFileNameDoNotCollide` |

## Test plan

- Unit — `WorkflowEngineTest`: `recordsStartAndFinishForEveryStep` (fixed `Clock`),
  `failedStepIsRecordedWithOutcomeFailed`, `stepsAfterFailureAreNotRecorded`,
  `rewritesSummaryAfterEachStep`, `failedJobSummaryRecordsTheError`,
  `exceptionWithoutMessageIsDescribedByItsClass`
- Unit — `JobSummaryWriterTest`: `writesJobJsonThatRoundTrips`, `overwritesSummaryAfterEachStep`,
  `writesTimesAsIso8601`, `leavesNoTempFilesBehind`
- Integration — `ErrorHandlingTest`: `failedJobMovesInputToErrorFolder`,
  `errorTxtContainsStepNameAndMessage`, `successfulJobLeavesNothingInErrorFolder`,
  `twoFailedJobsWithSameFileNameDoNotCollide`, `errorTxtSaysWhereInputWasLeftWhenItCannotBeMoved`
- Manual — Windows: drop a blank PDF in `hotfolder/`; it fails in `split-and-index` after
  PDFBox has opened it, and still moves to `error/<jobId>/` (checked 2026-11-12).

## Risks and open questions

- On Windows, a file still held open by a step can't be moved. Close every stream before
  failing the job, and test this on Windows at least once by hand.

## Definition of done

- Shared DoD, plus: README explains the `error/` folder and `job.json`.
