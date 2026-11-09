# Open questions

Decisions the PRDs leave to the PR that implements them. When you start a PR, check this file
for its PRD, settle each question in that PR, and record the answer here.

**When a question is answered**

1. Fill in **Answer**, change **Status** to `Answered`, and add the PR link.
2. Update the PRD (requirements, design notes and tests) to match the answer.
3. Update `docs/PR_PLAN.md` too if the answer changes a test name or the PR's scope.

Each question lists its options with pros and cons, and a recommendation. The recommendations
are suggestions, not decisions: the PR that answers the question makes the call.

## Summary

| ID | PRD | Question | Recommended | Status |
|---|---|---|---|---|
| [Q-01](#q-01--exception-and-queue-csv-layout) | [PRD-02](prd/PRD-02-routing-rules-and-exception-queue.md) | One CSV per queue, or one CSV with a `queue` column? | One combined CSV | Open |
| [Q-02](#q-02--real-afp-sample-for-the-manual-check) | [PRD-03](prd/PRD-03-afp-structured-field-reader.md) | Where does a real AFP file for the manual check come from? | Generate one with Apache FOP | Open |
| [Q-03](#q-03--build-the-afp-writer-or-check-in-a-sample) | [PRD-04](prd/PRD-04-afp-test-data-writer.md) | Build the AFP test-data writer, or check in a sample AFP file? | Build the writer; keep the FOP file as a cross-check | Open |
| [Q-04](#q-04--tle-name-mapping) | [PRD-05](prd/PRD-05-split-afp-step.md) | Add an optional `rename:` map for TLE names? | Not yet; configurable key names only | Open |
| [Q-05](#q-05--require-the-postgresql-ci-job) | [PRD-13](prd/PRD-13-persistence.md) | Make the Testcontainers PostgreSQL job required? | Required from the start | Open |
| [Q-06](#q-06--reprint-across-jobs) | [PRD-15](prd/PRD-15-reprint-and-retry.md) | Can one reprint combine documents from different jobs? | Not yet: one job per reprint | Open |
| [Q-07](#q-07--marking-afp-reprints) | [PRD-15](prd/PRD-15-reprint-and-retry.md) | How are reprinted AFP documents marked? | A `Reprint` TLE now; a visible mark later | Open |
| [Q-08](#q-08--exceptions-endpoint) | [PRD-17](prd/PRD-17-dashboard-actions.md) | Reuse document search for the Exceptions view, or add `GET /api/exceptions`? | Add a `queue` filter to the existing documents endpoint | Open |
| [Q-09](#q-09--host-port) | [PRD-18](prd/PRD-18-docker-demo-and-polish.md) | Which host port does Compose use by default? | `${PRINTLAB_PORT:-8080}` | Open |
| [Q-10](#q-10--fake-bank-name) | [PRD-18](prd/PRD-18-docker-demo-and-polish.md) | Is the generator's fake bank name safe to publish? | Rename it to an obviously fictional name | Open |

## PRD-02 — Routing rules and exception queue

### Q-01 — Exception and queue CSV layout

**Question:** does a job write one CSV per queue (`standard.csv`, `exceptions.csv`, …), or one
`documents.csv` with a `queue` column?

| Option | Pros | Cons |
|---|---|---|
| **A. One CSV per queue** | Matches production: each inserter line or exception desk gets only its own manifest. Each file's rows line up with that queue's print file. | The same columns are written several times. Readers (dashboard, PRD-13 import, tests) must find and merge several files. A queue with no documents needs a rule: empty file or no file. |
| **B. One combined CSV with a `queue` column** | One file to read, test and import. Matches the database shape PRD-13 will use. Moving a document between queues is just a value change. | Downstream equipment has to filter by queue. Mixes exception rows with good ones in one place. |
| **C. Both: combined, plus per-queue files derived from it** | Serves both readers. | Two outputs to keep consistent and test. More output files than the demo needs. |

**Recommendation:** B. The per-queue print files already separate the physical output, so the
manifest doesn't need to. Add C later only if a scenario needs per-queue manifests.

**Affects:** PRD-02's job summary requirement and CSV tests; what PRD-13, PRD-14 and PRD-16
read.

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-03 — AFP structured field reader

### Q-02 — Real AFP sample for the manual check

**Question:** the reader's manual check should run against AFP that this project didn't write.
Where does that file come from?

| Option | Pros | Cons |
|---|---|---|
| **A. A public sample file found online** | Truly independent: written by a real production tool. | Licensing is often unclear, so it may not be safe to check in. Hard to find one with page groups and TLEs. |
| **B. Only PRD-04's own writer output** | No extra tools or licensing. | The reader is checked only against our own writer, so a misunderstanding of the spec shared by both goes unnoticed. |
| **C. Generate one with Apache FOP's AFP output** | Apache-licensed, so the output can be checked in. Independent of our code. FOP has AFP extensions for TLEs, so page-level metadata can be included. | Adds a one-off tool step (FOP isn't a build dependency). Need to confirm the output has the page groups PRD-05 relies on. |

**Recommendation:** C. Check the generated file and its XSL-FO source into
`src/test/resources/afp/`, and note in the PR how it was made. Fall back to B if FOP's output
lacks what's needed.

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-04 — AFP test-data writer

### Q-03 — Build the AFP writer, or check in a sample

**Question:** build the writer, or skip PRD-04 and check a sample AFP file with page groups and
TLEs into the test resources, then test PRD-05 against it?

| Option | Pros | Cons |
|---|---|---|
| **A. Build the writer** | The best way to learn MO:DCA structure, which is what the interview probes. Seeded, varied test data (bad addresses, many sizes). Enables full DPart/AFP parity in PRD-10, and a visible AFP reprint marker in Q-07. | A medium-sized PR. Risk of the writer and reader sharing the same mistake (mitigated by Q-02). |
| **B. Check in a sample file instead** | Fastest: no PRD-04 at all. | One fixed file, so no varied or bad-data cases. PRD-10's `IndexParityTest` drops to region-only. Less AFP depth to talk about. |
| **C. A minimal writer: page groups and TLEs, no text** | Covers what PRD-05 and PRD-10 need for less work. | Pages are blank, so viewers show nothing useful and Q-07's visible marker isn't possible. |

**Recommendation:** A, with the FOP file from Q-02 kept as an independent cross-check. Drop to
C if time is short; B only as a last resort.

**Knock-on:** with B, PRD-10's `IndexParityTest` compares DPart with region only (see PRD-10).

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-05 — Split AFP step

### Q-04 — TLE name mapping

**Question:** TLE names vary between files (`Account Number`, `ACCT_NO`). Add an optional
`rename:` map to the step params (for example `ACCT_NO: accountNumber`)?

| Option | Pros | Cons |
|---|---|---|
| **A. No map: the step reads fixed property names, and the key TLE name is a param** | Smallest change; covers the files we actually have. | Every other TLE keeps its raw name, so properties differ between AFP and PDF sources. |
| **B. A `rename:` map in the split step's params** | Small, explicit, and easy to test. Lets AFP and PDF jobs share property names. | Only the AFP split step gets it, and PDF needs its own solution. Untested config if no sample needs it. |
| **C. A separate, reusable property-mapping step** | Mirrors how ProcessDirector maps input values to document properties; works for both AFP and PDF. | The most work; designing it without a real need risks the wrong shape. |

**Recommendation:** A now. If a real file needs renaming, prefer C over B, since that's the
ProcessDirector concept worth showing.

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-13 — Persistence

### Q-05 — Require the PostgreSQL CI job

**Question:** make the Testcontainers PostgreSQL job a required CI check, or keep it optional?
H2's PostgreSQL mode differs from real PostgreSQL in edge cases such as `ON CONFLICT`.

| Option | Pros | Cons |
|---|---|---|
| **A. Optional (non-blocking)** | A slow or flaky container start never blocks a merge. | Failures get ignored, and H2/PostgreSQL differences reach `main`, which is exactly what the job exists to catch. |
| **B. Required on every PR** | The database used in Docker (PRD-18) is tested on every change. GitHub's Ubuntu runners include Docker, so it costs about a minute. | Docker is also needed to run it locally; a container pull failure can block a merge. |
| **C. Only PostgreSQL: drop H2 and use Testcontainers for all repository tests** | One database, no dialect gaps, simpler SQL. | Every local test run needs Docker; slower tests. |

**Recommendation:** B. Keep H2 for the fast local loop and make the PostgreSQL job required
from the PR that adds it.

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-15 — Reprint and retry

### Q-06 — Reprint across jobs

**Question:** can one reprint request combine documents from different jobs?

| Option | Pros | Cons |
|---|---|---|
| **A. No: all documents must come from one job; mixed requests are a 400** | Simple model: one parent per child job. Easy API, tests and dashboard navigation. | An operator with damaged documents from several jobs sends several requests. |
| **B. Yes: one child job with several parents** | Matches real use (one inserter jam can damage several jobs' mail) in one action. | The parent link becomes a join table; the job detail view and audit trail show several parents; child properties that came from one parent need a rule. |
| **C. Yes, but split automatically: one child job per source job** | One operator action; each child still has a single parent. | The API returns several child ids, and the dashboard has no single child job to navigate to. |

**Recommendation:** A for this PR, with a clear error message. If cross-job reprint is wanted
later, C keeps the data model unchanged.

**Affects:** the parent link in the data model, and the test
`documentsFromDifferentJobsCanBeReprintedTogether` (rename it to
`documentsFromDifferentJobsAreRejected` under A).

- **Answer:** —
- **Status:** Open · **PR:** —

### Q-07 — Marking AFP reprints

**Question:** PDF reprints get a visible "REPRINT" mark on every page. What do AFP reprints get?

| Option | Pros | Cons |
|---|---|---|
| **A. No mark for AFP; the reprint is recorded only in the job history** | No AFP editing at all. | The printed sheet doesn't show it's a reprint, and the AFP and PDF behavior differ. |
| **B. A `Reprint` TLE in each page group** | Cheap with the PRD-04 writer: adds one structured field per document. Machine-readable by inserters and archives, which is how production systems flag reprints. | Not visible on paper. |
| **C. Visible PTOCA text added to each page** | Same visible result as PDF; shows real presentation-text knowledge. | Must insert text into existing pages: positioning units, code page, and preserving existing text objects. The most work and most risk. |
| **D. A page overlay (IPO) referencing an overlay resource** | How production systems usually add marks; the overlay is defined once. | Needs a resource group and overlay definition; more structure to read, write and test than C. |

**Recommendation:** B in this PR. Add C as a follow-up only if the PRD-04 writer already writes
presentation text that can be reused.

**Affects:** the `reprint` workflow, and whether `ReprintWorkflowTest` gets an AFP case
(`reprintedAfpHasReprintTleOnEveryDocument` under B).

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-17 — Dashboard actions

### Q-08 — Exceptions endpoint

**Question:** should the Exceptions view use
`GET /api/documents?property=queue&value=exceptions`, or a dedicated `GET /api/exceptions`?

| Option | Pros | Cons |
|---|---|---|
| **A. Reuse property search as is** | No new server code. | Only works if `queue` is stored as a document property; if it's a column (likely after PRD-13), the search won't find it. No paging or sorting. |
| **B. A dedicated `GET /api/exceptions`** | Can add exception-specific fields, such as the rule that routed the document. | A second endpoint that mostly repeats search; more tests and more API to maintain. |
| **C. Add `queue`, `limit` and `offset` filters to the existing `GET /api/documents`** | One endpoint serves search and Exceptions; paging helps search too; no special case for `queue`. | Changes PRD-14's endpoint, so its tests and contract fixture grow. |

**Recommendation:** C. Check first whether A already works with the PRD-13 schema; if it does
and paging isn't needed yet, A is fine.

**Affects:** whether `ExceptionsApiTest` exists (under C, its cases move into
`DocumentsApiTest`).

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-18 — Docker demo and polish

### Q-09 — Host port

**Question:** which host port does Compose use by default? Port 8080 was taken (by Docker)
during the scaffold's manual check.

| Option | Pros | Cons |
|---|---|---|
| **A. Fixed 8080** | Tomcat's default; what reviewers expect. | Fails on any machine where 8080 is in use, which is common. |
| **B. Configurable, defaulting to 8080: `${PRINTLAB_PORT:-8080}`** | Works by default and can be changed with one variable. Matches the Maven `tomcat.port` property already used for Cargo. | One more thing to document. |
| **C. Fixed, uncommon port (e.g. 18080)** | Rarely clashes. | Surprising; still fails if taken, and every doc and the smoke test must use it. |

**Recommendation:** B. Mention `PRINTLAB_PORT` in `docs/DEMO.md` and `.env.example`, and have
the smoke test read the same variable.

- **Answer:** —
- **Status:** Open · **PR:** —

### Q-10 — Fake bank name

**Question:** `StatementPdfGenerator` prints "Front Range Credit Union" on every statement. Is
it safe to publish?

**What we know:** a quick web search found no institution with exactly that name, but "Front
Range" is a common Colorado regional name used by several real credit unions and businesses.
The NCUA credit union locator is the authoritative check.

| Option | Pros | Cons |
|---|---|---|
| **A. Keep it after an NCUA check** | No change. | A regional name may still look like a real institution or be used by one later. |
| **B. Rename to an obviously fictional name (e.g. "Example Federal Credit Union")** | No risk of confusion; a one-line change. | Slightly less realistic-looking statements. |
| **C. B, plus a "SAMPLE — NOT A REAL STATEMENT" line on each page** | Makes generated files unmistakably fake if they're shared. | Adds text that PRD-09's extractor tests must account for. |

**Recommendation:** B. It costs one line and removes the risk; add C if the sample PDFs will be
shown publicly (README GIF, demo).

- **Answer:** —
- **Status:** Open · **PR:** —
