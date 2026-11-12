# PRD-00 — Test infrastructure: coverage, fixtures, helpers

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Foundation | S | — | `feature/00-test-infrastructure` |

## Summary

Add coverage reporting, readable assertions, a fixtures folder and a few test helpers, so that
every later PR can add tests cheaply and coverage can't quietly drop.

## Why

Almost every later PR parses binary formats or runs workflows end to end. Without shared helpers,
each test rebuilds jobs, workflows and temp folders by hand (the skeleton already does this with
an ad-hoc `withOutputDir` in `StatementWorkflowTest`). Coverage numbers also make "tests ship with
the code" checkable rather than a promise.

## Users and scenarios

- **Developer** writes a step test in a few lines: a job in a temp folder, a workflow built
  from step definitions, a fake step that records calls or throws.
- **Developer / reviewer** opens the JaCoCo report from CI to see what a PR left untested.

## Goals

- JaCoCo HTML report on every `./mvnw verify`, plus a minimum line-coverage gate.
- AssertJ available for collection and file assertions.
- `src/test/resources/fixtures/` as the home for small checked-in test files.
- Reusable helpers: `TestJobs`, `TestWorkflows`, `RecordingStep`.
- Coverage report uploaded as a CI artifact.

## Non-goals

- Raising coverage on existing code (the gate starts at today's number).
- Mutation testing, a coverage service, or JavaScript coverage.

## Requirements

**Functional**
- **FR-1** `./mvnw verify` produces `target/site/jacoco/index.html`.
- **FR-2** The build fails if line coverage falls below a configured minimum, initially the
  current measured value (rounded down).
- **FR-3** `TestJobs.newJob(Path tempDir, Path input)` returns a `Job` whose work dir is under
  the given temp dir.
- **FR-4** `TestWorkflows.of(StepDefinition...)` builds a workflow; `TestWorkflows.load(name,
  outputDir)` loads a YAML from `workflows/` and redirects `WriteOutput` to a temp folder,
  leaving every other step unchanged.
- **FR-5** `RecordingStep` records each `execute` call (job id, order) and can be configured to
  throw on its Nth call.
- **FR-6** CI uploads the JaCoCo report as a build artifact. **Deferred to PRD-19:** GitHub
  Actions is being replaced by Jenkins (Q-10), so the artifact is archived by the Jenkinsfile.

**Non-functional**
- **NFR-1** Adds no more than ~10 s to `./mvnw verify` on CI.
- **NFR-2** Helpers live in test scope only; nothing new on the runtime classpath or in the WAR.

## Design notes

- `jacoco-maven-plugin`: `prepare-agent` + `report` + `check` (`LINE`, `COVEREDRATIO`) in
  `verify`. Exclude `testdata/` generators and `App.main` from the gate if they distort it.
- Helpers in `src/test/java/dev/yjimmy/printlab/testsupport/`.
- Refactor `StatementWorkflowTest` onto `TestWorkflows.load` as the first user.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Existing tests pass unchanged in behavior after moving onto the helpers | existing suite |
| AC-2 | `TestWorkflows.load` changes only the `WriteOutput` step's `outputDir` | `TestWorkflowsTest.outputOverrideOnlyChangesWriteOutputStep` |
| AC-3 | Lowering coverage below the gate fails `./mvnw verify` | manual: temporarily delete a test |
| AC-4 | CI run has a downloadable `jacoco-report` artifact | CI run page (deferred to PRD-19) |

## Test plan

- Existing tests refactored onto the helpers and still green.
- Unit — `TestWorkflowsTest.outputOverrideOnlyChangesWriteOutputStep`.

## Risks and open questions

- A gate set too high blocks unrelated PRs. Start at the measured value and raise it deliberately.

## Definition of done

- Shared DoD (see [README](README.md)), plus: the coverage threshold and how to raise it are
  documented in the PR plan's *Testing strategy*.
