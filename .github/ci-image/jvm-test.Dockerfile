# jvm-test — the CI image for every job that runs Gradle. Built ON TOP of node-test.
#
# WHO USES IT
# `JVM tests`, `Static analysis` and `Plugin verifier` in ci.yml, `CodeQL (java-kotlin)`, the weekly drift
# check, and the release gate in release.yml.
#
# WHY IT IS BUILT FROM node-test RATHER THAN FROM fedora
# Two reasons, and the second is the one that matters.
#
# 1. No duplication. The base package list and the npm cache warm-up are written once, in
#    node-test.Dockerfile. Two standalone files would have to keep them in step by hand, and the failure
#    mode of that is not a build error — it is two images that quietly disagree about the Node version.
# 2. It needs Node anyway. Three of the jobs above run Gradle AND npm in a single job: `Static analysis`
#    (detekt and spotless, then eslint and prettier), `drift` (`npm install` then `checkDrift`), and the
#    release gate (`npm test` then `test verifyPlugin`). Splitting Node out would mean splitting those jobs
#    in two, and a new job is a whole extra image pull — the exact cost this segmentation exists to remove.
#
# The registry stores the shared layers ONCE, so this is not a second copy of the small image. A job that
# needs neither image runs on a bare runner and is not served from here at all — `Build plugin` is the
# example: it downloads an artifact and runs `unzip`, and used to pull GB to do it.
#
# WHAT IS DELIBERATELY NOT IN HERE: THE VERIFIER'S IDEs
# Baking what `verifyPlugin` downloads once made the single image 38.1 GB, 29.1 GB of it extracted IDEs.
# The verifier is their only consumer and runs ONLY on a pull request from develop into main — a handful of
# times a month. Paying 29 GB on every job's pull, permanently, to save ten minutes on the rarest job is the
# wrong side of that trade by two orders of magnitude. There is a second reason that would have bitten
# silently: the verifier resolves IDEs from the EAP/RC channels, so the set MOVES, and the day JetBrains
# publishes a new build the baked copies stop matching and Gradle downloads the new one anyway.
#
# BUILDING — node-test FIRST, since this image starts from it. From the repository ROOT, so /.dockerignore
# applies:
#
#   V=v1.0.0
#   docker build -f .github/ci-image/node-test.Dockerfile -t ghcr.io/OWNER/node-test:$V .
#   docker build -f .github/ci-image/jvm-test.Dockerfile  -t ghcr.io/OWNER/jvm-test:$V  \
#     --build-arg NODE_IMAGE=ghcr.io/OWNER/node-test:$V .
#   docker push ghcr.io/OWNER/node-test:$V
#   docker push ghcr.io/OWNER/jvm-test:$V
#
# The tag is `vMAJOR.MINOR.PATCH`, never `latest`. Bumping it is a commit: change the tag here and in every
# workflow that references it, so the two move together in one reviewable diff. Both images share a version
# because this one is derived from that one — they are not independently versionable.

# Declared before FROM so it can be used there. The default names this repository's own package; a fork
# overrides it with --build-arg rather than editing the file.
ARG NODE_IMAGE=ghcr.io/serialexperimentslainnnn/node-test:v1.0.0
FROM ${NODE_IMAGE}

# NB there is no dnf tuning here and that is not an omission: `max_parallel_downloads=20` and
# `fastestmirror=True` were written into /etc/dnf/dnf.conf by node-test, and this image starts from its
# filesystem — so the JDK transaction below already runs with them. Adding the lines again would append a
# SECOND copy of each key to dnf.conf rather than overriding anything.
#
# Temurin, not Fedora's OpenJDK.
#
# Fedora 44 no longer packages java-21-openjdk — it has moved on to a newer LTS — and the JDK version is not
# ours to float: build.gradle.kts pins the toolchain to 21 because the IDE runs on JBR 21, which is the
# ceiling. Building on 25 would produce class files no target IDE can load. Adoptium's repository is the
# same source the `setup-java` action uses on the hosted runners, so the image and the pipeline compile
# against the same JDK rather than two different builds of "21".
#
# There is deliberately no `dnf-plugins-core`: nothing here calls `dnf config-manager` — the repo file is
# written with `printf` — and `curl` is already in the base image, so installing it dragged in a ~150 MB
# Python stack to run a command nobody ran.
#
# `python3` is EXPLICIT, and that is a correctness requirement rather than a convenience: `bin/fake-claude`,
# the deterministic stand-in the integration tests drive a real ClaudeSession against, is a
# `#!/usr/bin/env python3` script. It used to arrive only as a transitive dependency of that unused package
# — a load-bearing dependency held up by an accident.
#
# `zip` is added here rather than in node-test because only the Gradle side packages archives.
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
         -o /etc/pki/rpm-gpg/RPM-GPG-KEY-Adoptium \
    && rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-Adoptium \
    && printf '%s\n' \
        '[Adoptium]' \
        'name=Adoptium' \
        'baseurl=https://packages.adoptium.net/artifactory/rpm/fedora/$releasever/$basearch' \
        'enabled=1' \
        'gpgcheck=1' \
        'gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Adoptium' \
        > /etc/yum.repos.d/adoptium.repo \
    && dnf -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs install \
        temurin-21-jdk \
        python3 \
        zip \
    && dnf clean all \
    && rm -rf /var/cache/dnf \
    && rm -rf /usr/share/locale

# JAVA_HOME is resolved rather than hardcoded: the exact path carries the package's build number and would
# silently break on the next base-image bump. The symlink keeps the ENV below stable across rebuilds.
RUN JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")" \
    && echo "JAVA_HOME=$JH" >> /etc/environment \
    && ln -sfn "$JH" /opt/java-21 \
    && "$JH/bin/java" -version
ENV JAVA_HOME=/opt/java-21
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Gradle writes here, and the path MUST match GRADLE_USER_HOME in the workflow. If they diverge, the warm
# cache below is invisible and every run silently re-resolves what this image already has.
ENV GRADLE_USER_HOME=/opt/gradle-home

# The npm cache is inherited from node-test — `npm_config_cache=/opt/npm-cache` and the warmed store are
# already in the layers below this one, so `Static analysis`, `drift` and the release gate get it for free.
WORKDIR /warmup

# The build definition first, on purpose: this layer is invalidated by a dependency change, not by every
# edit to the Kotlin sources.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties* ./

# Downloads the Gradle distribution itself. Kept separate from the warm-up below so a network problem here
# is distinguishable from a build problem there.
RUN ./gradlew --no-daemon --version

# The sources, needed because the warm-up below compiles. Filtered by /.dockerignore, so this is ~3 MB of
# Kotlin and resources rather than the 2 GB it was with node_modules, build/ and .git swept in.
COPY . .

# THE WARM-UP, and the reason it is `testClasses` rather than `dependencies`.
#
# It used to be `./gradlew dependencies --configuration compileClasspath > /dev/null 2>&1 || true`, and that
# command does NOT warm this cache. It resolves dependency METADATA; it never triggers the artifact
# transform that EXTRACTS the IntelliJ Platform, which is where the several GB actually are. Measured: that
# command leaves caches/*/transforms at 179 MB with no extracted IDE in it. The image looked warm and every
# job re-downloaded and re-extracted the platform — invisibly, because of the redirect and the `|| true`.
#
# `testClasses` compiles main and test sources, so it resolves AND extracts everything the Gradle jobs need.
#
# No `> /dev/null`, and no `|| true`. A warm-up that fails must fail the image build: the whole point of
# this image is the cache, so "the cache step failed but the image is fine" is not a state worth being able
# to reach. Verified with the network disabled — `testClasses` compiles offline in 33s from this cache.
RUN ./gradlew --no-daemon testClasses

# The sources were only ever scaffolding; keeping them would ship a stale copy of the repository inside the
# image, which someone would eventually mistake for the real one. This does not reclaim the space (layers
# are additive) — it prevents the confusion.
RUN rm -rf /warmup/* /warmup/.[!.]* 2>/dev/null || true

WORKDIR /workspace
