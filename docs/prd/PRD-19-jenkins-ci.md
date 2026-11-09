# PRD-19 — Jenkins CI, running locally in Docker

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Foundation | M | 00 | `feature/19-jenkins-ci` |

> **Order:** the number is new, not the order. Land this right after PRD-00, and before PRD-13,
> because PRD-13's PostgreSQL tests run as a required Jenkins stage
> ([Q-05](../OPEN_QUESTIONS.md#q-05--require-the-postgresql-ci-job)).

## Summary

Run the project's CI in a Jenkins controller that runs locally in Docker. A `Jenkinsfile` in the
repository defines the pipeline. Jenkins itself is defined as code under `ci/jenkins/`: its
image, plugins and configuration. Anyone can start the same Jenkins with one command, and
nothing is set up by clicking through the UI.

## Why

- Many production print shops run Jenkins on their own machines rather than hosted CI. Building
  and maintaining a pipeline is directly relevant experience for the role.
- Running locally keeps builds, test databases and images on our own hardware.
- It's the first half of the CI/CD path. PRD-20 adds deployment to a k3s server on ARM.
- Testcontainers needs Docker, which a self-hosted Jenkins provides by design.

## Users and scenarios

- **Developer** pushes a branch. Jenkins builds it, runs the Java, dashboard and PostgreSQL
  tests, and archives the coverage report.
- **Developer** opens a PR on GitHub and sees whether the Jenkins build passed. How that status
  gets back to GitHub is
  [Q-10](../OPEN_QUESTIONS.md#q-10--github-actions-alongside-jenkins).
- **Developer** on a new machine runs `docker compose -f ci/jenkins/compose.yml up -d` and gets the
  same Jenkins, plugins and job.

## Goals

- `ci/jenkins/`:
  - A `Dockerfile` based on a pinned `jenkins/jenkins` LTS JDK 21 image.
  - `plugins.txt` with pinned plugin versions.
  - A Configuration as Code (JCasC) file.
  - A Compose file that starts Jenkins and gives it Docker access
    ([Q-11](../OPEN_QUESTIONS.md#q-11--docker-access-for-jenkins)).
- A declarative `Jenkinsfile` at the repository root.
- A multibranch pipeline job for this repository, created by JCasC (or Job DSL), not by hand.
- Required stages: build and Java tests, dashboard tests, PostgreSQL integration tests (from
  PRD-13 on), coverage report.
- `docs/CI.md`: how to start Jenkins, add the GitHub credential, and read a build.

## Non-goals

- Deployment, image publishing or k3s; those are PRD-20.
- Jenkins high availability, distributed agents, or exposing Jenkins to the internet.
- Building the Docker image or the Compose smoke test; PRD-18 adds those stages.

## Requirements

**Functional**
- **FR-1** `docker compose -f ci/jenkins/compose.yml up -d` starts a configured Jenkins: plugins
  installed, the multibranch job present, and no setup wizard.
- **FR-2** Each stage runs in a pinned container image (Docker Pipeline agents):
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
- **FR-6** Builds are triggered for every branch and PR. The trigger method (polling or a
  webhook) is part of Q-10.
- **FR-7** Credentials (a GitHub token, later a registry and kubeconfig for PRD-20) live in the
  Jenkins credential store. Config files reference them by ID and never contain values.

**Non-functional**
- **NFR-1** Every image tag and plugin version is pinned; upgrades are their own PR.
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
  agent. Set `TESTCONTAINERS_HOST_OVERRIDE` if the Docker access method requires it; Q-11 decides
  the method.
- The existing GitHub Actions workflow stays until
  [Q-10](../OPEN_QUESTIONS.md#q-10--github-actions-alongside-jenkins) is answered.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | A fresh `compose up` produces a working Jenkins with the job, no manual steps | Manual: from an empty volume, noted in the PR |
| AC-2 | A green branch runs every stage and publishes test results and coverage | Jenkins build page (screenshot in the PR) |
| AC-3 | A failing Java test, Mocha test or PostgreSQL test each fail the build | Manual: three throwaway commits on a test branch |
| AC-4 | No credential value appears in the repository | `git grep` for token patterns; review |
| AC-5 | No containers are left running after a build | `docker ps` after a build, noted in the PR |

## Test plan

- Manual, recorded in the PR:
  1. Start Jenkins from an empty volume and confirm the job exists.
  2. Run a green build and confirm test results, coverage and timing.
  3. Break a Java test, a Mocha test and (once PRD-13 lands) a PostgreSQL test, one at a time,
     and confirm each fails the right stage.
  4. Run `docker ps` after the build.
- Optional: validate the `Jenkinsfile` with Jenkins' declarative linter in the pipeline itself.

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-05,
Q-10, Q-11). Record the answers there and here.

- Docker Desktop on Windows and Docker on Linux differ in socket paths and networking, which
  affects Testcontainers inside Jenkins. Test on the machine that will run Jenkins.
- Jenkins plugins update often and sometimes break each other; pinning (NFR-1) is the
  mitigation.

## Definition of done

- Shared DoD, plus: `docs/CI.md` written; the README's build badge or CI section points to
  Jenkins; `PR_PLAN.md` and the shared DoD say "Jenkins" where they say "CI".
