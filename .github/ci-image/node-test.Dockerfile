# node-test — the small CI image: Node, npm, and the warm npm cache. Nothing else.
#
# WHO USES IT
# `Frontend tests` and `Dependency audit` in ci.yml. Both jobs are `npm ci` followed by one npm command,
# and both used to run on the full image: an 8-second vitest run spent 1m05s pulling a JDK, a Gradle
# distribution and 3.4 GB of extracted IntelliJ Platform it never opened.
#
# WHY IT IS A SEPARATE FILE RATHER THAN A STAGE
# Deliberate: each image is built and published on its own, so neither can grow because the other needed
# something. The cost is that the package list below is duplicated in jvm-test.Dockerfile — that duplication
# is the trade, and it is the thing to check when either file changes.
#
# WHY THE PULL CANNOT SIMPLY BE CACHED — the question this split exists to answer.
# A `container:` job pulls its image in `Initialize containers`, which runs BEFORE the first step of the
# job. There is no point at which an `actions/cache` step could run first, and every job starts on a fresh
# runner with no shared layer cache. So the image download is not cacheable at all; the only lever is how
# much each job has to download. Hence this file.
#
# BUILDING — from the repository ROOT, so /.dockerignore applies:
#
#   docker build -f .github/ci-image/node-test.Dockerfile \
#     -t ghcr.io/OWNER/node-test:v1.0.0 .
#   docker push ghcr.io/OWNER/node-test:v1.0.0
#
# The tag is `vMAJOR.MINOR.PATCH`, never `latest`: a floating tag makes "which image was that job green on?"
# unanswerable, and this repository's standard is to pin. Bumping it is a commit — change the tag here and
# in every workflow that references it, so the two move together in one reviewable diff.
FROM fedora:44

# Parallel downloads: dnf defaults to 3, and the link is not the bottleneck.
RUN echo "max_parallel_downloads=20" >> /etc/dnf/dnf.conf \
    && echo "fastestmirror=True" >> /etc/dnf/dnf.conf

# `git-core` rather than `git`: actions/checkout clones, fetches and checks out, and git-core provides
# /usr/bin/git for all of that. The `git` metapackage adds Perl tooling, git-core-doc and perl-libs —
# ~32 MB nothing in this pipeline invokes.
#
# `which`/`findutils`/`procps-ng` are assumed present by various actions; Fedora's base image is minimal
# enough not to ship them. `install_weak_deps=False` drops recommended-but-unused packages, `tsflags=nodocs`
# drops the documentation inside the ones we do want.
# `upgrade --refresh` before the install, in the SAME layer: the `fedora:44` tag is a moving snapshot that
# can be weeks behind, and a CI image is exactly where you do not want to be running last month's openssl.
# Refreshing first also means the install below resolves against current metadata rather than whatever was
# cached in the base layer.
#
# The cost, stated rather than discovered: this makes the build non-reproducible — the same Dockerfile
# yields different bytes on different days. That is acceptable HERE and only here, because the image is
# pinned by an explicit `vX.Y.Z` tag that CI references. What CI runs is frozen; what a rebuild produces is
# not, and bumping the tag is the deliberate act that moves it.
RUN dnf -y upgrade --refresh \
    && dnf -y --setopt=install_weak_deps=False --setopt=tsflags=nodocs install \
        nodejs npm \
        git-core unzip tar which findutils procps-ng ca-certificates \
    && dnf clean all \
    && rm -rf /var/cache/dnf \
    # gettext catalogues: translated CLI messages for tools this image only runs non-interactively under a
    # C locale. NOT /usr/lib/locale, which is the locale DEFINITIONS glibc resolves against.
    && rm -rf /usr/share/locale

WORKDIR /warmup

COPY package.json package-lock.json ./

# What is baked is the npm CACHE, not `node_modules`, and the distinction is the whole point: `node_modules`
# MUST match the package-lock.json of whatever commit CI checks out, not the one current when the image was
# cut. The cache is version-addressed and therefore safe to reuse — `npm ci` in CI rebuilds node_modules
# from it without touching the network.
#
# `node_modules` is removed in the SAME layer that creates it: a `rm` in a later layer would not reclaim the
# space, only hide it. Measured at 478 MB.
ENV npm_config_cache=/opt/npm-cache
RUN npm ci --no-audit --no-fund \
    && rm -rf /warmup/node_modules

# The lockfile was scaffolding for the cache; keeping it would ship a stale copy inside the image that
# someone would eventually mistake for the real one.
RUN rm -rf /warmup/* /warmup/.[!.]* 2>/dev/null || true

WORKDIR /workspace
