# CI image for claude-code-native — Fedora 44.
#
# WHY THIS EXISTS
# The expensive part of this pipeline is not compute, it is downloads: `verifyPlugin` pulls ~1.25 GB of
# IntelliJ IDEs on every cold run, and a GitHub runner starts cold every time a branch cannot write its own
# cache. Baking those into an image turns a 10-minute job into a pull plus a couple of minutes.
#
# WHERE TO PUBLISH IT
# ghcr.io, NOT Docker Hub. It sits on the same network as the runners (much faster pulls) and has no
# anonymous pull-rate limit — that limit is a classic cause of a pipeline failing for reasons nobody
# changed.
#
# HOW IT GOES STALE, WHICH IS THE REAL CAVEAT
# `verifyPlugin` resolves IDEs from the EAP/RC channels, so the set it wants MOVES. The day JetBrains
# publishes a new build, the baked copies stop matching and Gradle downloads the new one anyway — the image
# degrades to "no worse than before" rather than breaking. Rebuild it weekly (a scheduled workflow) or
# accept that the saving decays between builds.
#
#   docker build -f .github/ci-image/Dockerfile -t ghcr.io/OWNER/cc-ci:latest .
#   docker push ghcr.io/OWNER/cc-ci:latest
#
# Used from a workflow as:
#   jobs:
#     test:
#       runs-on: ubuntu-latest
#       container: ghcr.io/OWNER/cc-ci:latest
FROM fedora:44

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
RUN dnf -y --setopt=install_weak_deps=False install dnf-plugins-core \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
         -o /etc/pki/rpm-gpg/RPM-GPG-KEY-Adoptium \
    && rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-Adoptium \
    && printf '%s\n' \
        '[Adoptium]' \
        'name=Adoptium' \
        'baseurl=https://packages.adoptium.net/artifactory/rpm/fedora/$releasever/$basearch' \
        'enabled=1' \
        'gpgcheck=1' \
        'gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Adoptium' \
        > /etc/yum.repos.d/adoptium.repo

# `git` is required by actions/checkout; `which`/`findutils`/`procps-ng` are assumed present by various
# actions and by Gradle's own probing, and Fedora's base image is minimal enough not to ship them.
# `--setopt=install_weak_deps=False` keeps the image from pulling in recommended-but-unused packages.
RUN dnf -y --setopt=install_weak_deps=False install \
        temurin-21-jdk \
        nodejs npm \
        git unzip zip tar which findutils procps-ng ca-certificates \
    && dnf clean all \
    && rm -rf /var/cache/dnf

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

# Only the build definition, on purpose: this layer is invalidated by a dependency change, not by every edit
# to the Kotlin sources. The whole source tree is copied later, in a layer that costs nothing to rebuild.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY package.json package-lock.json ./

# Downloads the Gradle distribution itself and resolves the plugin/dependency graph.
RUN ./gradlew --no-daemon --version \
    && ./gradlew --no-daemon dependencies --configuration compileClasspath > /dev/null 2>&1 || true

# npm dependencies for the frontend tests. `npm ci` needs package-lock.json, which is why it is copied above.
RUN npm ci --no-audit --no-fund

# The big one. `verifyPlugin` is what pulls the IDEs, and there is no way to fetch them without running it,
# so the full source is needed here. This step is SLOW (~10 minutes) by design — it is paying once, at image
# build time, for what every CI run was paying.
#
# `|| true`: a verification FAILURE must not fail the image build. We are here for the side effect (the
# downloaded IDEs now sitting in GRADLE_USER_HOME), not for the verdict — the verdict is CI's job, on the
# real commit, not on whatever happened to be checked out when the image was cut.
COPY . .
RUN ./gradlew --no-daemon verifyPlugin > /dev/null 2>&1 || true

# The sources were only ever scaffolding for the warm-up; keeping them would ship a stale copy of the
# repository inside the image, which someone would eventually mistake for the real one.
RUN rm -rf /warmup/* /warmup/.git /warmup/.[!.]* 2>/dev/null || true

WORKDIR /workspace
