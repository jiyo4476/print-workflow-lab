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
- **FR-8** `WriteOutput` writes `output/<jobId>/<queue>/` and one `documents.csv` per queue, or
  one CSV with a `queue` column (decide in the PR and document it).
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

## Test plan

- Unit — `RuleTest` (parameterized): every operator true/false; numeric comparison; `missing`
  for absent and blank; nested `all`/`any`; empty `all`/`any`
- Unit — `RuleParserTest`: parses the plan example, `unknownOperatorFailsWithStepName`,
  `invalidRegexFailsAtLoadNotAtRuntime`, `missingValueForComparisonOperatorFails`
- Unit — `RouteDocumentsStepTest`: `firstMatchingRouteWins`,
  `unmatchedDocumentsGoToDefaultQueue`, `noDefaultAndNoMatchFailsTheJob`
- Unit — `WorkflowEngineTest.stepWithFalseWhenIsSkippedAndRecordedAsSkipped`
- Workflow — `RoutingWorkflowTest` (seed with 5 bad of 50): `badAddressesLandInExceptions`,
  `everyDocumentIsInExactlyOneQueue`, `outputHasOneFolderPerQueue`

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-01). Record the answers there and here.

- **Open:** one CSV with a `queue` column, or one CSV per queue? One per queue matches how
  inserter lines consume output; one combined CSV is easier for the dashboard. Decide in the PR.
- YAML regex escaping (`"\\d{5}"`) catches people out. Show it in the sample workflow with a
  comment.

## Definition of done

- Shared DoD, plus: `workflows/statements.yaml` uses `RouteDocuments`, and the README concept
  map lists "connectors with rules → `RouteDocuments` / `when:`".
