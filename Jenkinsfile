// Jenkins pipeline for print-workflow-lab: build, test, then deploy to the Docker daemon behind Jenkins.
//
// Loaded from this file: Configure > Pipeline > Definition "Pipeline script from SCM", Script Path
// "Jenkinsfile". docs/CI.md has the full job setup.
//
// The script checks the repository out itself. That's why skipDefaultCheckout is set.
//
// Jenkins runs in a container and reaches Docker through the dind sidecar (DOCKER_HOST is
// tcp://docker:2376, set in the Jenkins compose file). Every stage runs in a pinned container
// with its own JDK or Node, and the app is deployed as a container on that same dind daemon. It
// is reachable from Jenkins at http://docker:<HOST_PORT>/; dind publishes no ports to the host.
//
// This file holds no credentials. Anything secret belongs in the Jenkins credential store.
//
// Needs plugins: Pipeline, Git, Docker Pipeline, JUnit, Timestamper.
//
// Pinned versions (update them together, and keep the repo's CI and pom.xml in step):
//   JDK     eclipse-temurin 21.0.13_9 (Ubuntu noble)   builds and tests the WAR
//   Node    26.13.1 LTS "Lithium" (Alpine)              runs the dashboard's Mocha tests
//   Tomcat  10.1.62 on Temurin 21 (Ubuntu noble)        runs the WAR in the deployed container

pipeline {
    agent any

    parameters {
        string(name: 'BRANCH', defaultValue: 'main', description: 'Branch to build')
        booleanParam(name: 'DEPLOY', defaultValue: true,
                description: 'Deploy the build to Docker (dind) after the tests pass')
        string(name: 'HOST_PORT', defaultValue: '8080',
                description: 'Port the app is published on, on the dind daemon')
    }

    // Checks GitHub for new commits every ~5 minutes. Polling starts after the first build.
    triggers {
        pollSCM('H/5 * * * *')
    }

    environment {
        SCM_URL = 'https://github.com/jiyo4476/print-workflow-lab.git'
        APP_NAME = 'print-workflow-lab'
        // The image's repository on the dind daemon; each build is tagged with its number.
        IMAGE_REPO = 'print-workflow-lab'
        // The dind sidecar's name on the Jenkins network; deployed ports are published there.
        DEPLOY_HOST = 'docker'
        // Jenkins' console doesn't render ANSI colours.
        NO_COLOR = '1'
        // Maven and npm caches live in .cache/ in the workspace. Clean-up skips that folder, so
        // later builds don't download everything again. The build containers run as Jenkins'
        // user (1000:1000), which owns the workspace but can't write to the image's home folder.
        MAVEN_USER_HOME = "${WORKSPACE}/.cache/maven"
        npm_config_cache = "${WORKSPACE}/.cache/npm"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds(abortPrevious: true)
        skipDefaultCheckout(true)
        timestamps()
    }

    stages {
        stage('Checkout') {
            steps {
                sh 'find . -mindepth 1 -maxdepth 1 ! -name .cache -exec rm -rf {} +'
                git url: env.SCM_URL, branch: params.BRANCH
                sh 'test -x mvnw && test -f package-lock.json'
            }
        }

        stage('Java build and tests') {
            agent {
                docker {
                    image 'eclipse-temurin:21.0.13_9-jdk-noble@sha256:bc12ebd88ffa6dda0092940e179365b05aef8d813b867f9df5a1f168c9a44feb'
                    reuseNode true
                }
            }
            steps {
                // verify = compile, unit tests, the WAR, the JaCoCo report and its coverage gate.
                sh './mvnw -B -Dmaven.repo.local="$MAVEN_USER_HOME/repository" verify'
            }
        }

        stage('Dashboard tests') {
            agent {
                docker {
                    image 'node:26.13.1-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5'
                    reuseNode true
                }
            }
            steps {
                sh 'node --version && npm --version'
                sh 'npm ci --ignore-scripts && npm test'
            }
        }

        stage('Build image') {
            when { expression { params.DEPLOY } }
            steps {
                // Only the WAR goes into the build context, not the workspace and its caches.
                // When the repo gets its own Dockerfile (plan PR 18), build from that instead.
                sh '''
                    rm -rf .image && mkdir .image
                    cp target/print-workflow-lab.war .image/ROOT.war
                    printf '%s\\n' \
                        'FROM tomcat:10.1.62-jdk21-temurin-noble@sha256:1c44b4cf0c530ac7e2e03a1fa0a00289fe90690d4583be3ddeabe13cc5e05196' \
                        'RUN rm -rf /usr/local/tomcat/webapps/*' \
                        'COPY ROOT.war /usr/local/tomcat/webapps/ROOT.war' \
                        'EXPOSE 8080' \
                        > .image/Dockerfile
                    docker build \
                        --label org.opencontainers.image.source="$SCM_URL" \
                        --label org.opencontainers.image.revision="$(git rev-parse HEAD)" \
                        -t "$IMAGE_REPO:$BUILD_NUMBER" -t "$IMAGE_REPO:latest" .image
                '''
            }
        }

        stage('Deploy') {
            when { expression { params.DEPLOY } }
            steps {
                // Remember what was running, so a failed smoke test can put it back.
                sh '''
                    docker inspect -f '{{.Config.Image}}' "$APP_NAME" > .previous-image 2>/dev/null \
                        || : > .previous-image
                    echo "Replacing: $(cat .previous-image || true)"
                    docker rm -f "$APP_NAME" >/dev/null 2>&1 || true
                    docker run -d --name "$APP_NAME" --restart unless-stopped \
                        -p "$HOST_PORT:8080" "$IMAGE_REPO:$BUILD_NUMBER"
                '''
            }
        }

        stage('Smoke test') {
            when { expression { params.DEPLOY } }
            steps {
                // Tomcat needs a few seconds to unpack and start the WAR.
                sh '''
                    url="http://$DEPLOY_HOST:$HOST_PORT/api/health"
                    for attempt in $(seq 1 30); do
                        if curl -fs "$url" | tee .health.json | grep -q '"status":"UP"'; then
                            echo "Healthy after $attempt attempt(s): $(cat .health.json)"
                            exit 0
                        fi
                        sleep 2
                    done
                    echo "No healthy response from $url after 60 s"
                    exit 1
                '''
            }
            post {
                failure {
                    // Show why it failed, then put the previous version back.
                    sh '''
                        docker logs --tail 100 "$APP_NAME" || true
                        docker rm -f "$APP_NAME" >/dev/null 2>&1 || true
                        previous=$(cat .previous-image 2>/dev/null || true)
                        if [ -n "$previous" ]; then
                            echo "Rolling back to $previous"
                            docker run -d --name "$APP_NAME" --restart unless-stopped \
                                -p "$HOST_PORT:8080" "$previous"
                        else
                            echo "Nothing was running before, so nothing to roll back to"
                        fi
                    '''
                }
                success {
                    // Keep the five newest numbered images, and never the one that's running.
                    sh '''
                        running=$(docker inspect -f '{{.Config.Image}}' "$APP_NAME")
                        docker images "$IMAGE_REPO" --format '{{.Tag}}' | grep -E '^[0-9]+$' \
                            | sort -n | head -n -5 \
                            | while read -r tag; do
                                if [ "$IMAGE_REPO:$tag" != "$running" ]; then
                                    docker rmi "$IMAGE_REPO:$tag" >/dev/null || true
                                fi
                            done
                        docker image prune -f >/dev/null
                        echo "Images kept: $(docker images "$IMAGE_REPO" --format '{{.Tag}}' | sort -n | tr '\\n' ' ')"
                    '''
                }
            }
        }
    }

    post {
        always {
            junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
            archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
        }
        success {
            // The project builds a WAR, not a JAR.
            archiveArtifacts artifacts: 'target/*.war', fingerprint: true
            echo(params.DEPLOY
                    ? "Deployed ${env.IMAGE_REPO}:${env.BUILD_NUMBER}: http://${env.DEPLOY_HOST}:${params.HOST_PORT}/"
                    : 'Tests passed; not deployed (DEPLOY is off)')
        }
        cleanup {
            sh 'find . -mindepth 1 -maxdepth 1 ! -name .cache -exec rm -rf {} +'
        }
    }
}
