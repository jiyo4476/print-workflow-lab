# Continuous integration

The pipeline is defined in [`Jenkinsfile`](../Jenkinsfile) and runs on a Jenkins controller
that is **not** part of this repository. Jenkins runs in Docker, and builds run in pinned
containers on a `docker:dind` sidecar that Jenkins reaches over TLS at `tcp://docker:2376`.

## What a build does

1. **Checkout:** clones `main` (or the `BRANCH` parameter).
2. **Java build and tests:** `./mvnw verify` (compile, unit tests, WAR, JaCoCo gate) in
   `eclipse-temurin:21.0.13_9-jdk-noble`.
3. **Start PostgreSQL:** starts a throwaway `postgres:17-alpine` on its own network on the dind
   daemon, with a random password per build, and waits until it accepts connections.
4. **PostgreSQL repository tests:** runs the persistence tests (PRD-13) against that database
   in the JDK image, joined to the network. Each test gets its own schema, dropped when the tests
   finish. The container and network are removed after every build.
5. **Dashboard tests:** `npm ci --ignore-scripts && npm test` in `node:26.13.1-alpine`.
6. **Build image, Deploy, Smoke test** (when `DEPLOY` is on): packages the WAR into a Tomcat
   image, runs it on the dind daemon at `HOST_PORT`, and waits for `/api/health` to report
   `UP`. A failed smoke test restarts the previous version.

Test results are published with JUnit. The JaCoCo report and the WAR are archived.

## Required Jenkins setup

Jenkins LTS (JDK 21) with these plugins:

- Pipeline (`workflow-aggregator`)
- Git
- Docker Pipeline (`docker-workflow`)
- JUnit
- Timestamper

Tested with the plugin set above on Jenkins LTS with JDK 21. Other versions may work but haven't been tried.

The Jenkins service needs `DOCKER_HOST=tcp://docker:2376`, `DOCKER_TLS_VERIFY=1` and
`DOCKER_CERT_PATH` pointing at the client certificates. The dind container must be able to
run privileged.

## Creating the job

1. New Item > **Pipeline** > name it `print-workflow-lab`.
2. Under **Pipeline**, choose **Pipeline script from SCM**.
3. SCM: **Git**. Repository URL: `https://github.com/jiyo4476/print-workflow-lab.git`.
   Branch: `*/main`.
4. Script Path: `Jenkinsfile`.
5. Save, then **Build with Parameters** to run it once.

The job polls GitHub every five minutes (`pollSCM`). Polling starts after the first build.

## Credentials

The pipeline uses **no credentials**, and the repository contains none. Anything that needs a
secret (for example a database password once PR 13 lands) goes in the Jenkins credential
store and is read with `withCredentials`. Never put one in `Jenkinsfile` or any file in this
repository.

## Parameters

| Name | Default | Meaning |
|---|---|---|
| `BRANCH` | `main` | Branch to build |
| `DEPLOY` | `true` | Build the image, deploy it, and smoke-test it |
| `HOST_PORT` | `8080` | Port on the dind daemon the app is published on |

The app is reachable from Jenkins at `http://docker:<HOST_PORT>/`. dind publishes no ports
to the host.

## Keeping versions in step

The JDK, Node and Tomcat versions are pinned by tag and digest in `Jenkinsfile`. Update them
together with the repo's CI and `pom.xml`, in one PR.
