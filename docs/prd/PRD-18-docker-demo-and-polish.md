# PRD-18 — Docker Compose, demo script, README polish

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Presentation | S | 17 | `feature/18-docker-demo` |

## Summary

Make the project start with one command (`docker compose up`: Tomcat plus PostgreSQL), write the
5-minute demo walkthrough, and polish the README, so a hiring manager can see it working from a
clean clone.

## Why

A project that takes an afternoon to set up doesn't get looked at. One command, a scripted demo,
and a README that starts with the ProcessDirector concept map turn the work into something easy
to present and easy to check.

## Users and scenarios

- **Hiring manager / reviewer** clones the repo, runs `docker compose up`, opens
  `http://localhost:8080/`, and follows `docs/DEMO.md`.
- **You, in an interview** run the 5-minute demo from the script without improvising setup.

## Goals

- A multi-stage `Dockerfile`: Maven build stage (`eclipse-temurin:21-jdk`) → runtime
  `tomcat:10.1-jdk21-temurin` with the WAR as `webapps/ROOT.war`.
- `docker-compose.yml` with Tomcat and PostgreSQL, mounted `hotfolder/` and `output/`, and the
  PRD-14 configuration.
- `docs/DEMO.md`, plus README polish: architecture diagram, demo GIF, updated concept map and
  roadmap.
- An MIT license before making the repository public.
- A CI image build and a Compose smoke test.

## Non-goals

- Production hardening: TLS, secrets management, resource limits, Kubernetes manifests.
- Publishing the image to a registry.

## Requirements

**Functional**
- **FR-1** `docker compose up` from a clean clone starts Tomcat and PostgreSQL; the app is
  healthy at `/api/health`.
- **FR-2** No Node stage: the dashboard libraries are already inside the WAR as WebJars.
- **FR-3** `hotfolder/` and `output/` are bind-mounted so files can be dropped in and inspected
  from the host.
- **FR-4** `docs/DEMO.md` is step-by-step:
  1. Generate a run with bad addresses.
  2. Drop it in the hot folder.
  3. Watch the job in the dashboard.
  4. Open Exceptions.
  5. Search an account.
  6. Reprint it.
  7. Show the structure view.
- **FR-5** The README opens with the concept map and a 30-second GIF, then run instructions, an
  architecture diagram and the roadmap.
- **FR-6** A `LICENSE` file (MIT) is added.

**Non-functional**
- **NFR-1** The image builds reproducibly in CI. Pin base image tags (and digests if practical).
- **NFR-2** The smoke test finishes in under ~3 minutes on CI.

## Design notes

- The Dockerfile's build stage runs `./mvnw -B -DskipTests package`. Tests run in the normal CI
  job, not in the image build.
- The smoke test script:
  1. `docker compose up -d`
  2. Poll `/api/health` with a timeout.
  3. Copy a generated file into the mounted hot folder.
  4. Poll `/api/jobs` until the job is `COMPLETE`.
  5. `docker compose down -v`

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | The image builds in CI | CI image-build job |
| AC-2 | Compose starts healthy and processes a dropped file to `COMPLETE` | CI smoke test |
| AC-3 | A clean clone plus `docs/DEMO.md` works exactly as written | Manual walkthrough, noted in the PR |

## Test plan

- CI job: build the Docker image.
- CI smoke test: `up -d` → wait for `/api/health` → drop a generated file → poll `/api/jobs` until
  `COMPLETE` → `down`.
- Manual: from a clean clone, follow `docs/DEMO.md` exactly.

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-09, Q-10). Record the answers there and here.

- Port 8080 is often taken on developer machines (it was during the scaffold's manual check).
  Make the host port configurable in Compose (`${PRINTLAB_PORT:-8080}`) and mention it in the
  demo doc.
- Check that the fake bank name in the statement generator isn't a real institution before going
  public; rename it if it is.

## Definition of done

- Shared DoD, plus: the repository can be made public (license, no secrets, no real names); the
  README roadmap is fully up to date.
