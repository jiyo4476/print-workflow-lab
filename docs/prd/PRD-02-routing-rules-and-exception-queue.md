# PRD-02 — Routing rules and exception queue

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Core | M | 01 | `feature/02-routing-rules` |

## Summary

A small rules language in workflow YAML decides where each document goes: an exception queue
for bad data, a large-envelope queue for thick statements, the standard queue for everything
else. Any step can also be made conditional on a job property.

## Why

Real print runs always contain bad records: missing names, malformed zip codes, oversized
statements. Production workflow systems handle this with connectors that carry rules, not by
failing the whole job. Rules are also what later PRDs plug into: AFP indexing errors (PRD-05),
DPart errors (PRD-10) and preflight failures (PRD-11) all become "route to exceptions".

## Users and scenarios

- **Workflow designer** adds a `RouteDocuments` step in YAML: zip not five digits, or name
  missing, goes to `exceptions`; more than 6 pages goes to `large-envelope`; everything else is
  `standard`.
- **Workflow designer** skips presort when a job has fewer than 50 documents, with
  `when: { property: documentCount, operator: lessThan, value: 50 }`.
- **Operator** finds bad statements in `output/<jobId>/exceptions/` instead of a failed job.

## Goals

- A rule model with operators and `all` / `any` combinators, validated when the workflow loads.
- A `RouteDocuments` step that assigns a `queue` document property; first match wins.
- An optional `when:` on any step, evaluated against job properties.
- `WriteOutput` writes one subfolder per queue.
- A generator option that injects a known number of bad addresses.

## Non-goals

- Expressions or scripting beyond the listed operators.
- Routing whole jobs to different workflows (PRD-06 routes by input device).
- Changing documents' content based on rules.

## Requirements

**Functional**
- **FR-1** Operators: `equals`, `notEquals`, `matches`, `notMatches`, `greaterThan`, `lessThan`,
  `missing`.
- **FR-2** `greaterThan` / `lessThan` compare numerically when both sides parse as numbers.
- **FR-3** `missing` is true for absent **or blank** values.
- **FR-4** `all` / `any` nest to any depth; empty `all` is true, empty `any` is false.
- **FR-5** Routes are evaluated in order; the first match sets `queue`; unmatched documents get
  `default`.
- **FR-6** If there's no `default` and a document matches no route, the job fails, naming the
  document sequence.
- **FR-7** A step with a false `when:` is skipped and recorded with outcome `SKIPPED`.
- **FR-8** `WriteOutput` writes each queue's documents to `output/<jobId>/<queue>/`, and one
  `output/<jobId>/documents.csv` for the whole job, with a `queue` column on every row
  (decided in [Q-01](../OPEN_QUESTIONS.md#q-01--exception-and-queue-csv-layout)).
- **FR-9** The statement generator accepts a count of bad addresses (blank name, 4-digit zip),
  placed deterministically for a given seed.

**Non-functional**
- **NFR-1** Configuration errors fail when the workflow loads, never mid-job: unknown operator,
  invalid regex, comparison without `value`. Each message names the step.
- **NFR-2** Regexes compile once at load time.

## Design notes

- A sealed `Condition` type: `Rule`, `All`, `Any`. `RuleParser` turns YAML maps into
  `Condition`s.
- The engine evaluates `when:` against `Job.properties()` before running each step.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Every operator behaves correctly for true and false cases | `RuleTest` (parameterized) |
| AC-2 | Numeric comparison: `"10" > "9"` | `RuleTest` |
| AC-3 | The YAML example in the plan parses; bad config fails at load with the step name | `RuleParserTest.*` |
| AC-4 | First match wins; unmatched go to default; no default + no match fails | `RouteDocumentsStepTest.*` |
| AC-5 | A false `when:` skips the step and records `SKIPPED` | `WorkflowEngineTest.stepWithFalseWhenIsSkippedAndRecordedAsSkipped` |
| AC-6 | With 5 bad addresses in 50, exactly 5 go to exceptions and every document is in exactly one queue | `RoutingWorkflowTest.*` |
| AC-7 | One `documents.csv` lists every document once, with its queue | `RoutingWorkflowTest.documentsCsvListsEveryDocumentWithItsQueue` |

## Test plan

- Unit — `RuleTest` (parameterized): every operator true/false; numeric comparison; `missing`
  for absent and blank; nested `all`/`any`; empty `all`/`any`
- Unit — `RuleParserTest`: parses the plan example, `unknownOperatorFailsWithStepName`,
  `invalidRegexFailsAtLoadNotAtRuntime`, `missingValueForComparisonOperatorFails`
- Unit — `RouteDocumentsStepTest`: `firstMatchingRouteWins`,
  `unmatchedDocumentsGoToDefaultQueue`, `noDefaultAndNoMatchFailsTheJob`
- Unit — `WorkflowEngineTest.stepWithFalseWhenIsSkippedAndRecordedAsSkipped`
- Workflow — `RoutingWorkflowTest` (seed with 5 bad of 50): `badAddressesLandInExceptions`,
  `everyDocumentIsInExactlyOneQueue`, `outputHasOneFolderPerQueue`,
  `documentsCsvListsEveryDocumentWithItsQueue`

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-01). Record the answers there and here.

- **Decided (Q-01):** one combined `documents.csv` with a `queue` column. The per-queue print
  folders already separate the physical output, and one file is simpler for PRD-13, PRD-14 and
  PRD-16 to read.
- YAML regex escaping (`"\\d{5}"`) catches people out. Show it in the sample workflow with a
  comment.

## Implementation notes

Decisions made while building this PR, beyond the requirements above:

- The rules live in `workflow/rules/` (`Condition`, `Rule`, `Operator`, `RuleParser`) because
  both the engine (`when:`) and `RouteDocumentsStep` use them.
- `matches` / `notMatches` test the whole value, so `\d{5}` means exactly five digits. An absent
  property never equals, matches or compares; `notEquals` and `notMatches` are therefore true
  for it, so a document with no zip goes to `exceptions`.
- `greaterThan` / `lessThan` fall back to text comparison when either side isn't a number.
- `missing` rejects a `value`, and unknown keys in a condition or a route are errors, so typos
  fail at load.
- Queue names become folder names, so they must match `[A-Za-z0-9][A-Za-z0-9._-]*`; checked at
  load and again in `WriteOutput`.
- `StepRegistry.create` prefixes every step factory's `IllegalArgumentException` with
  `Step '<name>' (<type>): `, which covers NFR-1 for `RouteDocuments` and every existing step.
- In `WriteOutput`, files are numbered from `00001.pdf` within each queue, the CSV's `position`
  is the position within the queue, and `file` is relative to `output/<jobId>/`
  (`standard/00001.pdf`). Documents without a `queue` (no routing step) go in
  `output/<jobId>/` as before.
- The generator alternates bad addresses (blank name, then 4-digit zip) and picks which
  statements are bad from a separate `Random`, so a seed's good statements are the same with or
  without bad ones.
- Generated statements have 1-3 pages, so the `large-envelope` route never fires on generated
  data; `RouteDocumentsStepTest` covers it.

## Definition of done

- Shared DoD, plus: `workflows/statements.yaml` uses `RouteDocuments`, and the README concept
  map lists "connectors with rules → `RouteDocuments` / `when:`".
