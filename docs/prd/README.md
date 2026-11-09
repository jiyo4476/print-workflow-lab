# Product requirements, one per planned PR

Each file here is the PRD (product requirements document) for one item in
[`docs/PR_PLAN.md`](../PR_PLAN.md). The plan gives the order and the size; each PRD says **why**
the PR exists, **what** it must do, what it deliberately leaves out, and **how we'll know it's
done**.

> **Numbering:** `PRD-07` means item **7** of the PR plan, not GitHub pull request #7. GitHub
> numbers pull requests in the order they're opened. Put the plan number in the branch name
> (`feature/07-pdf-object-reader`) and in the PR title (`PR 7: PDF object reader`).

## Index

PRD-19 and PRD-20 were added after the plan was written, so their numbers don't reflect
their order: PRD-19 lands right after PRD-00.

| PRD | Title | Phase | Size | Depends on |
|---|---|---|---|---|
| [00](PRD-00-test-infrastructure.md) | Test infrastructure: coverage, fixtures, helpers | Foundation | S | — |
| [01](PRD-01-error-folder-and-job-summary.md) | Error folder and job summary file | Core | S | 00 |
| [02](PRD-02-routing-rules-and-exception-queue.md) | Routing rules and exception queue | Core | M | 01 |
| [03](PRD-03-afp-structured-field-reader.md) | AFP structured-field reader + dump tool | AFP | M | 00 |
| [04](PRD-04-afp-test-data-writer.md) | AFP test-data writer (page groups + TLEs) | AFP | M | 03 |
| [05](PRD-05-split-afp-step.md) | `SplitAfp` step: TLEs become document properties | AFP | M | 04 |
| [06](PRD-06-multiple-input-devices.md) | Multiple input devices | AFP | S | 05 |
| [07](PRD-07-pdf-object-reader.md) | PDF object reader + dump tool | PDF | L | 00 |
| [08](PRD-08-modern-pdf-structure.md) | Modern PDF structure: xref streams, object streams, incremental updates | PDF | M | 07 |
| [09](PRD-09-content-streams-and-text-positions.md) | Content streams and text positions | PDF | L | 07 |
| [10](PRD-10-pdf-dpart-indexing.md) | PDF/VT-style document parts (DPart) indexing | PDF | M | 08, 02 |
| [11](PRD-11-pdf-preflight.md) | PDF preflight step | PDF | M | 07, 02 |
| [12](PRD-12-barcode-step.md) | Barcode step for inserter control | Print features | M | 00 |
| [13](PRD-13-persistence.md) | Persistence: jobs and documents in PostgreSQL | Platform | L | 01 |
| [14](PRD-14-rest-api-on-tomcat.md) | REST API on Tomcat (read-only servlets) | Platform | M | 13 |
| [15](PRD-15-reprint-and-retry.md) | Reprint and retry | Platform | M | 14 |
| [16](PRD-16-marionette-dashboard.md) | Marionette.js v4 dashboard: jobs, documents, search | Dashboard | L | 14 |
| [17](PRD-17-dashboard-actions.md) | Dashboard actions: reprint, retry, exception queue | Dashboard | M | 15, 16 |
| [18](PRD-18-docker-demo-and-polish.md) | Docker Compose, demo script, README polish | Presentation | S | 17 |
| [19](PRD-19-jenkins-ci.md) | Jenkins CI, running locally in Docker (land after 00, before 13) | Foundation | M | 00 |
| [20](PRD-20-k3s-deployment-on-arm.md) | Deploy to k3s on ARM | Delivery | M | 18, 19 |

## Users

The PRDs refer to three users:

- **Operator:** runs print production day to day. Drops files, watches jobs, finds a
  customer's statement, reprints it, clears the exception queue. Uses the dashboard and the
  folders.
- **Workflow designer:** sets up workflows, rules and input devices in YAML. Needs clear errors
  when the configuration is wrong.
- **Developer (you):** builds and extends the system. Needs readable code, fast tests and dump
  tools for looking inside AFP and PDF files.

The PRDs also note what each PR gives you to **show and explain in an interview**, since that's
why the project exists.

## Format

Every PRD has the same sections:

1. **Summary:** one paragraph.
2. **Why:** the problem, and why it matters in production print.
3. **Users and scenarios:** who uses this, and what they do.
4. **Goals and non-goals:** what this PR does and doesn't do.
5. **Requirements:** numbered functional (`FR`) and non-functional (`NFR`) requirements.
6. **Design notes:** the intended approach. The PR can change it if it explains why.
7. **Acceptance criteria:** testable statements; each names the tests that verify it.
8. **Test plan:** tests grouped by level (see *Testing strategy* in the PR plan).
9. **Risks and open questions.** Decisions left to the PR are also listed in
   [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md), where their answers are recorded.
10. **Definition of done.**

The test names match the PR plan. If a PR changes a requirement, update its PRD and the plan in
that same PR.

## Shared definition of done

These apply to every PR; each PRD lists only what's extra.

- Tests in the same PR as the code; failure paths covered, not just the happy path.
- `./mvnw verify` and `npm test` green in CI; coverage doesn't drop (from PRD-00 on).
- Deterministic tests: seeded generators, no wall-clock time, no fixed sleeps, `@TempDir` for
  files.
- Scraped or file-derived values are shown as text, never HTML (Java logs, API JSON, dashboard).
- PR description: what and why, tests added, manual check, screenshots for UI changes.
- README roadmap checkbox ticked by the PR that completes it.
