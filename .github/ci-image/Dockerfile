# CI image for claude-code-native — Fedora 44.
#
# WHY THIS EXISTS
# The expensive part of this pipeline is not compute, it is downloads: a cold Gradle resolves and EXTRACTS
# the whole IntelliJ Platform before it compiles a line, and a GitHub runner starts cold every time. Baking
# that into an image turns minutes of download into a pull.
#
# WHAT IS DELIBERATELY *NOT* IN HERE: THE VERIFIER'S IDEs
# This image used to also bake what `verifyPlugin` downloads, and that made it 38.1 GB — 29.1 GB of it
# extracted IDEs. Every job in ci.yml pulls its own copy on its own runner, so a job whose actual work is an
# 8-second vitest run spent 5m37s in `Initialize containers` (measured, not estimated), and 38 GB on a
# runner with ~25-30 GB free on the root volume was flirting with `No space left on device`.
#
# The verifier is the only consumer of those IDEs, and it runs ONLY on a pull request from develop into
# main — a handful of times a month. Baking 29 GB into every job's pull, permanently, to save ten minutes on
# the rarest job in the pipeline is the wrong side of that trade by two orders of magnitude. `verifyPlugin`
# downloads what it needs, when it runs.
#
# There is a second reason, and it is the one that would have bitten silently: the IDE set MOVES. The
# verifier resolves from the EAP/RC channels, so the day JetBrains publishes a new build, the baked copies
# stop matching and Gradle downloads the new one anyway. The saving decayed on JetBrains' release schedule,
# not ours.
#
# WHERE TO PUBLISH IT
# ghcr.io, NOT Docker Hub. It sits on the same network as the runners (much faster pulls) and has no
# anonymous pull-rate limit — that limit is a classic cause of a pipeline failing for reasons nobody
# changed.
#
# BUILDING IT — from the repository ROOT, so /.dockerignore applies:
#
#   docker build -f .github/ci-image/Dockerfile -t ghcr.io/OWNER/cc-ci:base .
#   docker push ghcr.io/OWNER/cc-ci:base
#
# Tagged `:base`, not `:latest`, because this repository's own standard is to pin rather than float, and
# because a floating tag makes "which image was that job green on?" unanswerable.
#
# Used from a workflow as:
#   jobs:
#     test:
#       runs-on: ubuntu-latest
#       container: ghcr.io/OWNER/cc-ci:base
FROM fedora:44 AS base

# Parallel downloads: dnf defaults to 3, and this image installs a JDK plus a Node toolchain over a link
# that is not the bottleneck. Set before the first transaction so every one of them benefits.
RUN echo "max_parallel_downloads=20" >> /etc/dnf/dnf.conf \
    && echo "fastestmirror=True" >> /etc/dnf/dnf.conf

# Temurin, not Fedora's OpenJDK.
#
# Fedora 44 no longer packages java-21-openjdk — it has moved on to a newer LTS — and the JDK version is not
# ours to float: build.gradle.kts pins the toolchain to 21 because the IDE runs on JBR 21, which is the
# ceiling. Building on 25 would produce class files no target IDE can load.
#
# Adoptium's repository is the same source the `setup-java` action uses on the GitHub runners, so the image
# and the hosted pipeline compile against the same JDK rather than two different builds of "21".
# ONE transaction, not two. The previous first transaction existed only to install `dnf-plugins-core`, which
# was never used: the repository file below is written with `printf`, not with `dnf config-manager`, and
# `curl` is already in the fedora:44 base image (verified: curl-8.18.0). It was ~150 MB of Python stack
# pulled in to run a command nobody ran.
#
# `python3` is now EXPLICIT, and that is a correctness fix rather than a size one. `bin/fake-claude` — the
# deterministic stand-in the integration tests drive a real ClaudeSession against — is a `#!/usr/bin/env
# python3` script. It worked only because `dnf-plugins-core` happened to drag the interpreter in as a
# transitive dependency. Removing the unused package without naming python3 here would have made the
# integration suite fail on a missing interpreter, which is the kind of break that reads as a test bug.
#
# `git-core` rather than `git`: actions/checkout clones, fetches and checks out, and git-core provides
# /usr/bin/git for all of that. The `git` metapackage adds the Perl tooling (git-send-email and friends),
# git-core-doc and perl-libs — ~32 MB nothing in this pipeline invokes.
#
# `which`/`findutils`/`procps-ng` are assumed present by various actions and by Gradle's own probing, and
# Fedora's base image is minimal enough not to ship them.
#
# `install_weak_deps=False` drops recommended-but-unused packages; `tsflags=nodocs` drops the documentation
# that ships inside the ones we do want.
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
        nodejs npm \
        python3 \
        git-core unzip zip tar which findutils procps-ng ca-certificates \
    && dnf clean all \
    && rm -rf /var/cache/dnf \
    # gettext catalogues: translated CLI messages for tools this image only ever runs non-interactively
    # under a C locale. NOT /usr/lib/locale, which is the locale DEFINITIONS the JVM and glibc resolve
    # against — deleting that would change how the build behaves, not just how it reads.
    && rm -rf /usr/share/locale

# JAVA_HOME is resolved rather than hardcoded: the exact path carries the package's build number and would
# silently break on the next base-image bump.
RUN JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")" \
    && echo "JAVA_HOME=$JH" >> /etc/environment \
    && ln -sfn "$JH" /opt/java-21 \
    && "$JH/bin/java" -version
# A stable symlink, so JAVA_HOME does not carry Temurin's build number and break on the next image rebuild.
ENV JAVA_HOME=/opt/java-21
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Gradle writes here, and the path must match what the job will use, or the warm caches below are invisible
# to it. Set GRADLE_USER_HOME to the same value in the workflow.
ENV GRADLE_USER_HOME=/opt/gradle-home

WORKDIR /warmup

# Only the build definition first, on purpose: this layer is invalidated by a dependency change, not by
# every edit to the Kotlin sources.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY package.json package-lock.json ./

# Downloads the Gradle distribution itself. Kept separate from the warm-up below so a network problem here
# is distinguishable from a build problem there.
RUN ./gradlew --no-daemon --version

# The sources, needed because the warm-up below compiles. Filtered by /.dockerignore, so this is a few MB
# of Kotlin and resources rather than the 2 GB it used to be with node_modules and build/ swept in.
COPY . .

# THE WARM-UP, and the reason it is `testClasses` rather than `dependencies`.
#
# It used to be `./gradlew dependencies --configuration compileClasspath > /dev/null 2>&1 || true`, and that
# command does NOT warm this cache. It resolves dependency METADATA; it never triggers the artifact
# transform that EXTRACTS the IntelliJ Platform, which is where the several GB actually are. Measured: that
# command leaves caches/*/transforms at 179 MB with no extracted IDE in it. The image looked warm and every
# job re-downloaded and re-extracted the platform — invisibly, because of the redirect and the `|| true`.
#
# `testClasses` compiles main and test sources, so it resolves AND extracts everything `test`, `detekt`,
# `spotlessCheck`, `buildPlugin` and the CodeQL Kotlin build need.
#
# No `> /dev/null`, and no `|| true`. A warm-up that fails must fail the image build. The old form could not
# report anything: the whole point of this image is the cache, so "the cache step failed but the image is
# fine" is not a state worth being able to reach.
RUN ./gradlew --no-daemon testClasses

# npm dependencies for the frontend tests. `npm ci` needs package-lock.json, which is why it is copied above.
#
# What is baked is the npm CACHE, not `node_modules`, and the distinction is the whole point: the cleanup
# step below wipes /warmup, so a baked node_modules would be deleted moments after being built — the warm-up
# would look like it worked and buy nothing. `node_modules` also MUST match the package-lock.json of whatever
# commit CI checks out, not the one that happened to be current when the image was cut, so keeping it would
# be wrong even if it survived. The cache is version-addressed and therefore safe to reuse: `npm ci` in CI
# rebuilds node_modules from it without touching the network.
ENV npm_config_cache=/opt/npm-cache
# `node_modules` is removed in the SAME layer that creates it. It is scaffolding — the cache above is what
# survives — and a `rm` in a later layer would not reclaim the space, only hide it.
RUN npm ci --no-audit --no-fund \
    && rm -rf /warmup/node_modules

RUN rm -rf /warmup/* /warmup/.[!.]* 2>/dev/null || true
WORKDIR /workspace
