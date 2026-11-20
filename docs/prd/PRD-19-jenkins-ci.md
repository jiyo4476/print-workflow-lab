# PRD-19 — Jenkins CI, running locally in Docker

| Plan phase | Size | Depends on | Branch |
|---|---|---|---|
| Foundation | M | 00 | `feature/19-jenkins-ci` |

> **Order:** the number is new, not the order. Land this right after PRD-00, and before PRD-13,
> because PRD-13's PostgreSQL tests run as a required Jenkins stage
> ([Q-05](../OPEN_QUESTIONS.md#q-05--require-the-postgresql-ci-job)).

## Summary

Run the project's CI in a Jenkins controller that runs locally in Docker. A `Jenkinsfile` in the
repository defines the pipeline. Jenkins itself is a machine-level service shared by all
projects, kept outside this repository (today in `Documents\containers\jenkins`), so this repository
ships only the pipeline and `docs/CI.md`, which states exactly what that Jenkins must provide.

## Why

- Many production print shops run Jenkins on their own machines rather than hosted CI. Building
  and maintaining a pipeline is directly relevant experience for the role.
- Running locally keeps builds, test databases and images on our own hardware.
- It's the first half of the CI/CD path. PRD-20 adds deployment to a k3s server on ARM.
- Testcontainers needs Docker, which a self-hosted Jenkins provides by design.

## Users and scenarios

- **Developer** pushes a branch. Jenkins builds it, runs the Java, dashboard and PostgreSQL
  tests, and archives the coverage report.
- **Developer** opens a PR on GitHub and sees whether the Jenkins build passed: Jenkins posts
  the result back as a commit status. Jenkins is the only CI; GitHub Actions is removed because
  its minutes cost money
  ([Q-10](../OPEN_QUESTIONS.md#q-10--github-actions-alongside-jenkins)).
- **Developer** on a new machine with that shared Jenkins running follows `docs/CI.md`: checks
  the required plugins, adds the credentials by ID, and creates the multibranch job.

## Goals

- Use the existing shared Jenkins, which runs in Docker with a `docker:dind` sidecar reached at
  `tcp://docker:2376` over TLS
  ([Q-17](../OPEN_QUESTIONS.md#q-17--how-jenkins-reaches-the-local-docker)). Its Compose file,
  image and plugins stay outside this repository.
- Two places to run build containers
  ([Q-11](../OPEN_QUESTIONS.md#q-11--docker-access-for-jenkins)): Docker, and the k3s cluster
  through the Kubernetes plugin.
- A declarative `Jenkinsfile` at the repository root.
- A multibranch pipeline job for this repository in that Jenkins. `docs/CI.md` gives its exact
  settings (repository, credential IDs, polling interval), so it can be recreated in minutes.
- Required stages: build and Java tests, dashboard tests, PostgreSQL integration tests (from
  PRD-13 on), coverage report.
- `docs/CI.md`: what the shared Jenkins must provide (Jenkins and plugin versions it was tested
  with, the dind sidecar, `TESTCONTAINERS_HOST_OVERRIDE`), the credential IDs to add, how to
  create the job, and how to read a build.
- Remove `.github/workflows/ci.yml` once the Jenkins pipeline is green on `main`.

## Non-goals

- Deployment, image publishing or k3s; those are PRD-20.
- Jenkins high availability, distributed agents, or exposing Jenkins to the internet.
- Defining or versioning Jenkins itself (its image, plugins, Compose file or JCasC) in this
  repository; it's a shared service outside it (Q-17).
- Building the Docker image or the Compose smoke test; PRD-18 adds those stages.

## Requirements

**Functional**
- **FR-1** Following `docs/CI.md` on the shared Jenkins gives a working multibranch job for this
  repository. The `Jenkinsfile` fails early with a clear message if a required plugin or the
  Docker connection is missing.
- **FR-2** Each stage runs in a pinned container image, either as a Docker agent or as a pod
  on k3s (Kubernetes plugin). Stages that need a Docker daemon (Testcontainers, image builds)
  run on Docker. The images:
  - a Maven image with Temurin 21 for `./mvnw -B verify`
  - a Node image matching CI's Node version for `npm ci --ignore-scripts && npm test`
- **FR-3** Stages, in order:
  1. Checkout
  2. Java build and tests
  3. Dashboard tests
  4. PostgreSQL integration (added by PRD-13)
  5. Coverage report
  Any failure fails the build.
- **FR-4** JUnit results (Maven Surefire and Failsafe, plus Mocha's JUnit reporter) are published
  so failures show per test. The JaCoCo report (PRD-00) is archived on every build.
- **FR-5** The PostgreSQL stage runs the repository tests through Testcontainers against the same
  PostgreSQL major version used in Compose (PRD-18) and on k3s (PRD-20). It is required, not
  optional ([Q-05](../OPEN_QUESTIONS.md#q-05--require-the-postgresql-ci-job)).
- **FR-6** Builds are triggered for every branch and PR by polling GitHub (Jenkins isn't
  exposed to the internet). Each build's result is posted back to GitHub as a commit status.
- **FR-7** Credentials (a GitHub token, the k3s kubeconfig, and the `registry.yjimmy.dev` login
  for PRD-20) live in the
  Jenkins credential store. Config files reference them by ID and never contain values.

**Non-functional**
- **NFR-1** Every container image the `Jenkinsfile` uses is pinned; upgrades are their own PR.
  `docs/CI.md` records the Jenkins and plugin versions the pipeline was last tested with.
- **NFR-2** Maven and npm caches persist between builds (a named volume), so a no-change build
  takes under ~3 minutes on the developer machine.
- **NFR-3** Jenkins is reachable only from the local machine or LAN; nothing is exposed
  publicly.
- **NFR-4** Builds don't leave containers running; Testcontainers' cleanup (Ryuk) works under
  the chosen Docker access method.

## Design notes

- Declarative pipeline with `agent none` at the top and a `docker` agent per stage. The workspace
  is shared, so the WAR from the Java stage is available to later stages.
- Mocha needs a JUnit reporter for FR-4, such as `mocha-junit-reporter` as a dev dependency.
  Keep the default spec reporter for local runs.
- Testcontainers inside a containerized agent needs the Docker host to be reachable from the
  agent. With the dind sidecar (Q-17), set `TESTCONTAINERS_HOST_OVERRIDE=docker`; Q-11 decides
  the method.
- GitHub Actions keeps running until the Jenkins pipeline is green on `main`; this PR then
  deletes `.github/workflows/ci.yml`, so there's no gap without CI.
- The k3s cluster is ARM, so pod agents need images with `arm64` variants (the official Maven
  and Node images have them).

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | Following `docs/CI.md` on the shared Jenkins creates a working job | Manual: delete and recreate the job from the doc, noted in the PR |
| AC-2 | A green branch runs every stage and publishes test results and coverage | Jenkins build page (screenshot in the PR) |
| AC-3 | A failing Java test, Mocha test or PostgreSQL test each fail the build | Manual: three throwaway commits on a test branch |
| AC-4 | No credential value appears in the repository | `git grep` for token patterns; review |
| AC-5 | No containers are left running after a build | `docker ps` after a build, noted in the PR |

## Test plan

- Manual, recorded in the PR:
  1. Delete the job and recreate it by following `docs/CI.md` exactly.
  2. Run a green build and confirm test results, coverage and timing.
  3. Break a Java test, a Mocha test and (once PRD-13 lands) a PostgreSQL test, one at a time,
     and confirm each fails the right stage.
  4. Run `docker ps` after the build.
- Optional: validate the `Jenkinsfile` with Jenkins' declarative linter in the pipeline itself.

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-05,
Q-10, Q-11, Q-17). Record the answers there and here.

- Docker Desktop on Windows and Docker on Linux differ in socket paths and networking, which
  affects Testcontainers inside Jenkins. Test on the machine that will run Jenkins.
- Jenkins plugins update often and sometimes break each other. Plugin versions are managed by
  the shared Jenkins, not this repository; `docs/CI.md` records the tested versions, so a break
  can be traced to an upgrade.

## Definition of done

- Shared DoD, plus: `docs/CI.md` written; `.github/workflows/ci.yml` removed; the README's
  CI section points to
  Jenkins; `PR_PLAN.md` and the shared DoD say "Jenkins" where they say "CI".
