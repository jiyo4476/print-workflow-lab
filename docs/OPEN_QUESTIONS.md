# Open questions

Decisions the PRDs leave to the PR that implements them. When you start a PR, check this file
for its PRD, settle each question in that PR, and record the answer here.

**When a question is answered**

1. Fill in **Answer** and change **Status** to `Decided` (decision made, not built yet) or
   `Answered` (built in a PR). Add the date, and the PR link once there is one.
2. Update the PRD (requirements, design notes and tests) to match the answer.
3. Update `docs/PR_PLAN.md` too if the answer changes a test name or the PR's scope.

Each question lists its options with pros and cons, and a recommendation. The recommendations
are suggestions, not decisions: the PR that answers the question makes the call.

## Summary

| ID | PRD | Question | Recommended | Status |
|---|---|---|---|---|
| [Q-01](#q-01--exception-and-queue-csv-layout) | [PRD-02](prd/PRD-02-routing-rules-and-exception-queue.md) | One CSV per queue, or one CSV with a `queue` column? | One combined CSV | **Decided:** one combined CSV |
| [Q-02](#q-02--real-afp-sample-for-the-manual-check) | [PRD-03](prd/PRD-03-afp-structured-field-reader.md) | Where does a real AFP file for the manual check come from? | Generate one with Apache FOP | Open |
| [Q-03](#q-03--build-the-afp-writer-or-check-in-a-sample) | [PRD-04](prd/PRD-04-afp-test-data-writer.md) | Build the AFP test-data writer, or check in a sample AFP file? | Build the writer; keep the FOP file as a cross-check | Open |
| [Q-04](#q-04--tle-name-mapping) | [PRD-05](prd/PRD-05-split-afp-step.md) | Add an optional `rename:` map for TLE names? | Not yet; configurable key names only | Open |
| [Q-05](#q-05--require-the-postgresql-ci-job) | [PRD-13](prd/PRD-13-persistence.md) | Make the Testcontainers PostgreSQL job required? | Required from the start | **Decided:** required, in Jenkins |
| [Q-06](#q-06--reprint-across-jobs) | [PRD-15](prd/PRD-15-reprint-and-retry.md) | Can one reprint combine documents from different jobs? | Not yet: one job per reprint | Open |
| [Q-07](#q-07--marking-afp-reprints) | [PRD-15](prd/PRD-15-reprint-and-retry.md) | How are reprinted AFP documents marked? | A `Reprint` TLE now; a visible mark later | Open |
| [Q-08](#q-08--exceptions-endpoint) | [PRD-17](prd/PRD-17-dashboard-actions.md) | Reuse document search for the Exceptions view, or add `GET /api/exceptions`? | Add a `queue` filter to the existing documents endpoint | Open |
| [Q-09](#q-09--host-port) | [PRD-18](prd/PRD-18-docker-demo-and-polish.md) | Which host port does Compose use by default? | `${PRINTLAB_PORT:-8080}` | Open |
| [Q-10](#q-10--github-actions-alongside-jenkins) | [PRD-19](prd/PRD-19-jenkins-ci.md) | Keep GitHub Actions alongside Jenkins? | Keep Actions until Jenkins reports status to GitHub | **Decided:** Jenkins only |
| [Q-11](#q-11--docker-access-for-jenkins) | [PRD-19](prd/PRD-19-jenkins-ci.md) | How does Jenkins in Docker get Docker access? | Docker-in-Docker sidecar | **Decided:** Docker or k3s |
| [Q-12](#q-12--building-arm-images) | [PRD-20](prd/PRD-20-k3s-deployment-on-arm.md) | How are arm64 images built? | `buildx` cross-build, Maven stage on the build platform | **Decided:** cross-build |
| [Q-13](#q-13--image-registry-for-k3s) | [PRD-20](prd/PRD-20-k3s-deployment-on-arm.md) | Where are images pushed for k3s to pull? | A registry on the LAN | **Decided:** `registry.yjimmy.dev` |
| [Q-14](#q-14--postgresql-on-k3s) | [PRD-20](prd/PRD-20-k3s-deployment-on-arm.md) | Where does PostgreSQL run for the k3s deployment? | A StatefulSet in the cluster | **Decided:** in the cluster |
| [Q-15](#q-15--manifest-format-and-deploy-trigger) | [PRD-20](prd/PRD-20-k3s-deployment-on-arm.md) | Manifest format, and what triggers a deploy? | Kustomize; auto-deploy `main` | **Decided:** Kustomize; deploy green `main` |
| [Q-16](#q-16--getting-files-into-the-hot-folder-on-k3s) | [PRD-20](prd/PRD-20-k3s-deployment-on-arm.md) | How do files reach the hot folder on the server? | A host folder shared over SMB | Open |
| [Q-17](#q-17--how-jenkins-reaches-the-local-docker) | [PRD-19](prd/PRD-19-jenkins-ci.md) | How does Jenkins reach its local Docker? | Docker-in-Docker sidecar | Open |

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

- **Answer:** B. One `output/<jobId>/documents.csv` per job, with a `queue` column on every row.
  PRD-02 FR-8 and the new test `documentsCsvListsEveryDocumentWithItsQueue` reflect this.
- **Status:** Decided (2026-11-09) · **PR:** plan PR 2 (`feature/02-routing-rules`)

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

- **Answer:** B, with a change of CI platform. The PostgreSQL tests are a **required** stage, and
  CI/CD runs in **Jenkins, running locally in Docker**, which then deploys to a **k3s server on
  ARM**. H2 stays for the fast local loop. This added two plan items:
  [PRD-19](prd/PRD-19-jenkins-ci.md) (Jenkins CI, landing right after PRD-00 so this stage has
  a home) and [PRD-20](prd/PRD-20-k3s-deployment-on-arm.md) (deploy to k3s). It also raised
  Q-10 to Q-16 below.
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PRs 19 and 13)

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

## PRD-19 — Jenkins CI, running locally in Docker

### Q-10 — GitHub Actions alongside Jenkins

**Question:** the repository already has a GitHub Actions workflow. Once Jenkins runs CI, what
happens to it? A Jenkins on the local machine can't receive GitHub webhooks unless it's exposed,
but it can poll GitHub and post build results back as commit statuses using a token.

| Option | Pros | Cons |
|---|---|---|
| **A. Replace: delete the Actions workflow once Jenkins is green** | One CI system, one set of rules. Matches the "run locally" goal. | PRs only show a status if Jenkins posts it back; when the local machine is off, nothing builds. |
| **B. Keep both permanently** | PRs always get a check, even when Jenkins is off. | Two pipelines to keep in step; they can disagree. |
| **C. Keep Actions for PR checks, use Jenkins for `main` and deployment** | Hosted CI for reviews, local Jenkins for CD. | The PostgreSQL stage needs Docker in both places; split responsibility is harder to explain. |

**Recommendation:** keep Actions while PRD-19 is being built, then A once Jenkins posts commit
statuses to GitHub (GitHub Branch Source plugin with a token). Polling every few minutes avoids
exposing Jenkins.

- **Answer:** A, Jenkins only. GitHub Actions minutes cost money, so PRD-19 deletes `.github/workflows/ci.yml` once the Jenkins pipeline is green. Jenkins polls GitHub (it isn't exposed to the internet) and posts each build's result back as a commit status, so PRs still show a check.
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PR 19)

### Q-11 — Docker access for Jenkins

**Question:** Jenkins runs in a container but must start containers itself (pipeline agents,
Testcontainers, image builds). How does it reach Docker?

| Option | Pros | Cons |
|---|---|---|
| **A. Mount the host's Docker socket** | Simplest; fastest; images are shared with the host. | Anything in a build gets root-equivalent access to the host. On Windows, Docker Desktop's socket paths and networking need extra care. |
| **B. A Docker-in-Docker (`docker:dind`) sidecar, reached over TLS** | The setup in Jenkins' own Docker install guide. Builds are isolated from the host's containers. | Privileged container; a separate image cache (slower first builds); Testcontainers needs `TESTCONTAINERS_HOST_OVERRIDE`. |
| **C. Jenkins agents as pods in the k3s cluster (Kubernetes plugin)** | Reuses the cluster; no Docker on the Jenkins host. | Builds now depend on the ARM server; image builds need Kaniko or BuildKit; Testcontainers in pods is harder. Contradicts "run locally". |

**Recommendation:** B. It's documented, keeps builds off the host's Docker, and works the same on
Windows and Linux.

- **Answer:** Docker (A or B) plus k3s (C): Jenkins starts containers either on Docker or on the k3s cluster it's connected to (Kubernetes plugin). Testcontainers and image builds stay on Docker, since they need a Docker daemon; other stages can run as pods on k3s. How Jenkins reaches its local Docker (socket mount or Docker-in-Docker) is still open as [Q-17](#q-17--how-jenkins-reaches-the-local-docker).
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PR 19)

## PRD-20 — Deploy to k3s on ARM

### Q-12 — Building ARM images

**Question:** how does the pipeline produce an image that runs on the ARM server?

| Option | Pros | Cons |
|---|---|---|
| **A. `docker buildx` cross-build on the Jenkins machine; the Maven stage on the build platform** | The WAR doesn't depend on CPU architecture, so only the small runtime layer is arm64 and QEMU emulation barely runs. Can push `amd64` and `arm64` as one multi-arch image, so Compose (PRD-18) keeps working locally. | Needs `buildx` and QEMU binfmt set up in the Docker that Jenkins uses (Q-11). |
| **B. Build natively on the ARM server (a Jenkins agent there)** | No emulation; the image is exactly what runs. | Builds load the server that's also running the app; another agent to maintain. |
| **C. Full QEMU emulation of the whole build** | No Dockerfile changes. | Maven under emulation is very slow (many minutes). |

**Recommendation:** A, with `FROM --platform=$BUILDPLATFORM` on the Maven stage, building
`linux/amd64,linux/arm64`.

- **Answer:** A. Cross-build on the Jenkins machine with `docker buildx`, with the Maven stage on the build platform, producing `linux/amd64` and `linux/arm64`.
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PR 20)

### Q-13 — Image registry for k3s

**Question:** where does Jenkins push images, and where does k3s pull them from?

| Option | Pros | Cons |
|---|---|---|
| **A. A registry on the LAN (`registry:2`, next to Jenkins or on the server)** | Everything stays local; fast pulls; no external account. | One more service to run. Needs TLS, or k3s `registries.yaml` set to trust a plain-HTTP registry. No off-site copy. |
| **B. GitHub Container Registry (GHCR), private** | Nothing to host; versions kept off-site; works from anywhere. | Images leave the local network; k3s needs an image pull secret; pulls depend on the internet. |
| **C. No registry: `docker save`, copy to the server, `k3s ctr images import`** | No registry at all. | Not how real deployments work; needs SSH from Jenkins to the server; rollbacks need the old image still on the node. |

**Recommendation:** A, matching the local-first choice in Q-05. Configure `registries.yaml` on
k3s and document it in `docs/DEPLOY.md`.

- **Answer:** A registry at `registry.yjimmy.dev`. Images are pushed as `registry.yjimmy.dev/print-workflow-lab:<commit SHA>`. Its login is a Jenkins credential, and k3s gets pull access through `registries.yaml` or an image pull secret.
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PR 20)

### Q-14 — PostgreSQL on k3s

**Question:** where does the deployed app's database run?

| Option | Pros | Cons |
|---|---|---|
| **A. A single-instance StatefulSet in the cluster, on a `local-path` volume** | Self-contained; the manifests define the whole system; uses k3s's built-in storage. | No backups or failover; data lives on one disk. |
| **B. PostgreSQL installed directly on the server (or a NAS), outside k3s** | Simple to back up with normal tools; survives cluster rebuilds. | Part of the system isn't in the manifests; a manual setup step. |
| **C. An operator such as CloudNativePG** | Backups, failover and upgrades handled; a production-grade pattern. | Heavy for a small ARM board; much more to learn and explain. |

**Recommendation:** A, using the official `postgres` image (it has arm64 variants), with a
`pg_dump` CronJob as a light backup if wanted. Use the same major version as Compose and the
Testcontainers stage.

- **Answer:** A. A single-instance PostgreSQL StatefulSet inside the cluster, on a `local-path` volume, using the same major version as Compose and the Testcontainers stage.
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PR 20)

### Q-15 — Manifest format and deploy trigger

**Question:** how are the manifests written, and what triggers a deployment?

| Option | Pros | Cons |
|---|---|---|
| **A. Plain YAML with Kustomize; auto-deploy every green `main` build** | Kustomize is built into `kubectl`, so no extra tool. Setting the image tag per deploy is one command. Every merge reaches the server, which is what CD means. | A bad merge reaches the server quickly (mitigated by the smoke test and automatic undo). |
| **B. A Helm chart** | Common in industry; templating and release history. | Heavier than this app needs; another tool in the pipeline. |
| **C. Deploy only on a tag, or after a manual approval step** | Deliberate releases. | Slower feedback; more ceremony for a one-person project. |
| **D. GitOps (Argo CD or Flux in the cluster)** | The cluster pulls the desired state; drift is visible. | Another always-on service on a small board; changes PRD-20's shape. |

**Recommendation:** A. Revisit C if the server is used for interview demos and must stay stable
at specific times.

- **Answer:** A. Plain manifests with Kustomize (`kubectl apply -k`), and every green `main` build deploys.
- **Status:** Decided (2026-11-09) · **PR:** — (built in plan PR 20)

### Q-16 — Getting files into the hot folder on k3s

**Question:** on a laptop, you drop a file into `hotfolder/`. On the k3s server the hot folder is a
volume inside the cluster. How do operators get files into it?

| Option | Pros | Cons |
|---|---|---|
| **A. A host folder on the server, shared over SMB or NFS and mounted into the pod** | Closest to production: print files arrive on a network share. Drag and drop from any machine. | Ties the pod to that node (fine with one node). Share permissions to set up. |
| **B. An upload endpoint (`POST /api/inputs`) and a dashboard button** | Works anywhere the app is reachable; easy to demo. | New API and UI work outside PRD-20's scope; large files over HTTP; not how hot folders work. |
| **C. `kubectl cp` into the pod** | No extra work. | Developer-only; poor for a demo. |

**Recommendation:** A with SMB, since the demo machine runs Windows. The pipeline's smoke test
can still use `kubectl cp` or `kubectl exec` to drop its file.

- **Answer:** —
- **Status:** Open · **PR:** —

## PRD-19, continued

### Q-17 — How Jenkins reaches the local Docker

**Question:** Q-11 decided that Jenkins starts containers on Docker or on k3s. For the Docker
side, how does the Jenkins container reach a Docker daemon? This is what Testcontainers and the
`buildx` cross-build (Q-12) use.

| Option | Pros | Cons |
|---|---|---|
| **A. Mount the host's Docker socket** | Simplest and fastest; images and the build cache are shared with the host. | Anything in a build gets root-equivalent access to the host. On Windows, Docker Desktop's socket path and networking need extra care. |
| **B. A Docker-in-Docker (`docker:dind`) sidecar, reached over TLS** | The setup in Jenkins' own Docker install guide. Builds are isolated from the host's containers and work the same on Windows and Linux. | Runs privileged; a separate image cache (slower first builds); Testcontainers needs `TESTCONTAINERS_HOST_OVERRIDE`. |

**Recommendation:** B.

- **Answer:** —
- **Status:** Open · **PR:** —
