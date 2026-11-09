# PRD-20 — Deploy to k3s on ARM

| Phase | Size | Depends on | Branch |
|---|---|---|---|
| Delivery | M | 18, 19 | `feature/20-k3s-deploy` |

## Summary

Extend the Jenkins pipeline (PRD-19) from CI to CD. Build the PRD-18 image for `linux/arm64`,
publish it to a registry, and deploy the app with PostgreSQL to a k3s server running on ARM.
Then check that the deployed app is healthy.

## Why

- A real deployment target turns the demo from "runs on my laptop" into a running service.
- It shows the full path from commit to running service, plus Kubernetes basics: Deployments,
  persistent volumes, Secrets and readiness probes.
- Building for ARM is a real-world concern now that ARM servers are common.

## Users and scenarios

- **Developer** merges to `main`. Jenkins tests the change, builds an arm64 image, pushes it,
  rolls it out to k3s, and checks `/api/health` on the server.
- **Operator / reviewer** opens the app on the k3s server's address and runs the demo from
  `docs/DEMO.md` there.
- **Developer** sees a failed rollout fail the pipeline, and the previous version keeps running.

## Goals

- A multi-arch (or arm64) image build in the pipeline.
- Kubernetes manifests in `deploy/k8s/` for the app, PostgreSQL, storage, configuration and
  access.
- Pipeline stages, on `main` only: push the image, deploy, verify the rollout, then smoke test.
- `docs/DEPLOY.md`: how to set up the k3s server, the registry and the Jenkins credentials.

## Non-goals

- Multi-node clusters, high availability, autoscaling, or more than one app replica.
- Public internet exposure, TLS certificates, or a DNS name.
- Helm charts or GitOps tooling (Argo CD, Flux), unless
  [Q-15](../OPEN_QUESTIONS.md#q-15--manifest-format-and-deploy-trigger) decides otherwise.
- Provisioning the ARM server or installing k3s itself, beyond what `docs/DEPLOY.md` describes.

## Requirements

**Functional**
- **FR-1** The pipeline builds an image that runs on `linux/arm64`, from the same Dockerfile as
  PRD-18 ([Q-12](../OPEN_QUESTIONS.md#q-12--building-arm-images)).
- **FR-2** Images are tagged with the commit SHA (plus `latest` on `main`) and pushed to the
  chosen registry ([Q-13](../OPEN_QUESTIONS.md#q-13--image-registry-for-k3s)).
- **FR-3** `deploy/k8s/` defines:
  - a `printlab` namespace
  - the app `Deployment` with **one replica**, using the `Recreate` strategy (see design notes)
  - a `Service` and an `Ingress` through k3s's built-in Traefik
  - PostgreSQL ([Q-14](../OPEN_QUESTIONS.md#q-14--postgresql-on-k3s))
  - persistent volumes for `PRINTLAB_HOME` (hot folder, work, output, error)
  - a `ConfigMap` for settings and a `Secret` for database credentials
- **FR-4** The app has a readiness probe and a liveness probe on `/api/health`. Resource requests
  and limits fit a small ARM board.
- **FR-5** The deploy stage runs only on `main` and sets the image to the commit SHA tag. It waits
  for `kubectl rollout status`, then runs a smoke test against the Ingress: wait for
  `/api/health`, drop a generated file in the hot folder, and poll `/api/jobs` until it's
  `COMPLETE`.
- **FR-6** If the rollout or smoke test fails, the pipeline fails, runs
  `kubectl rollout undo`, and reports the failure.
- **FR-7** Database credentials and the kubeconfig are Jenkins credentials and Kubernetes
  Secrets, never committed. The manifests ship a `Secret` template, not values.
- **FR-8** Operators can get files into the hot folder on the server
  ([Q-16](../OPEN_QUESTIONS.md#q-16--getting-files-into-the-hot-folder-on-k3s)).

**Non-functional**
- **NFR-1** Base images used by the Dockerfile and manifests (Temurin, Tomcat, PostgreSQL) have
  `arm64` variants. Pin their tags, and their digests where practical.
- **NFR-2** A deploy, from image push to a healthy rollout, takes under ~5 minutes.
- **NFR-3** Redeploying the same commit is a no-op, and data in the volumes survives redeploys
  and pod restarts.

## Design notes

- **One replica, `Recreate` strategy.** The hot-folder watcher (PRD-06) must run in exactly one
  pod. Two pods, even briefly during a rolling update, could pick up the same file twice. This
  is a deliberate limit, worth explaining in an interview, along with how you'd lift it (leader
  election, or claiming files with an atomic move).
- The WAR doesn't depend on CPU architecture, so only the runtime image layer needs to be arm64.
  The Maven stage can run on the build machine's own platform (`FROM --platform=$BUILDPLATFORM`),
  which keeps cross-building fast (see Q-12).
- k3s ships Traefik for Ingress and the `local-path` provisioner for volumes, so the manifests
  need no extra cluster components.
- The pipeline uses the kubeconfig credential with `kubectl` in a pinned container image;
  nothing is installed on the Jenkins controller.

## Acceptance criteria

| # | Criterion | Verified by |
|---|---|---|
| AC-1 | The pushed image runs on arm64 | `docker manifest inspect` shows `linux/arm64`; the pod runs on the ARM node |
| AC-2 | A merge to `main` deploys and passes the smoke test with no manual steps | Jenkins build page (screenshot in the PR) |
| AC-3 | A broken image fails the pipeline and the previous version keeps serving | Manual: deploy a deliberately broken health endpoint from a test branch |
| AC-4 | Jobs and files survive a pod restart | Manual: `kubectl delete pod`, then the earlier job is still in `/api/jobs` |
| AC-5 | No secret values in the repository | `git grep` for token patterns; review |

## Test plan

- Validate the manifests in the pipeline: `kubectl apply --dry-run=server` against the cluster,
  or `kubeconform` offline.
- Smoke test in the pipeline after every deploy (FR-5).
- Manual, recorded in the PR:
  1. First deploy to an empty cluster, following `docs/DEPLOY.md` exactly.
  2. A failed rollout and the automatic undo.
  3. A pod restart, checking that data is kept.
  4. The demo from `docs/DEMO.md` against the server.

## Risks and open questions

Decisions for this PR are tracked in [`docs/OPEN_QUESTIONS.md`](../OPEN_QUESTIONS.md) (Q-12 to
Q-16). Record the answers there and here.

- Small ARM boards have little memory. Tomcat, the JVM and PostgreSQL together need sensible
  heap and resource limits; measure them on the real server.
- The `local-path` volumes live on one node's disk. A disk failure loses data, and there's no
  backup in scope. Document this in `docs/DEPLOY.md`.

## Definition of done

- Shared DoD, plus: `docs/DEPLOY.md` written and followed once from scratch; the README shows the
  CI/CD path (commit → Jenkins → registry → k3s).
