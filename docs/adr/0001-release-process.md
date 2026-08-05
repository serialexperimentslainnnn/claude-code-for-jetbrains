# ADR 0001 — Release process: branching, signing and tag immutability

- **Status:** accepted
- **Date:** 2026-08-05
- **Context skill:** `git-workflow-standards`

## Context

The plugin is installable software distributed through the JetBrains Marketplace, with real users on
previously published versions. This ADR records the places where the repository deliberately departs from the
default standards, and the places where it was simply **wrong**.

**Superseded within 5.0.0:** an earlier draft of this ADR said "there is no CI gate yet", on the belief that
GitHub Actions was capped for billing. That belief was false. The repository is **public**, and GitHub
Actions on standard hosted runners is free and unmetered for public repositories; the account's Actions
permissions were verified enabled. The workflows had simply been deleted at some point, and a comment in
`.gitlab-ci.yml` had been asserting the billing story ever since. 5.0.0 therefore lands a real GitHub
Actions pipeline (§5), and the deleted-and-forgotten history is the reason §5 exists as a written decision
rather than as four YAML files nobody can date.

## Decisions

### 1. GitFlow instead of trunk-based — accepted deviation

The standard default is trunk-based with short-lived branches and squash merges. This repository uses
`develop` → `main` with `--no-ff` merges and signed tags on `main`.

**Why the deviation is justified:** the standard itself carves out GitFlow for "releases versionadas y varias
versiones mayores soportadas en paralelo (software instalable)". This is exactly that case: a published
artifact where a user can be running 4.2.0 while 4.4.1 is current, and where a hotfix must be reproducible
from the exact tag that shipped. `--no-ff` keeps each release an identifiable merge commit, so
`git log --first-parent main` reads as the release history.

**Cost accepted:** merge commits are not Conventional Commits. They are excluded from the commitlint gate
(`commitlint.config.mjs` `ignores`) rather than being reworded, because rewriting a merge subject loses the
branch reference that makes it traceable.

### 2. GPG on a YubiKey instead of SSH `ed25519-sk` — accepted deviation

The standard default is SSH signing with a hardware-backed `ed25519-sk` key. This repository signs commits and
tags with GPG (ECDSA, key `6CD3…435A`) on a YubiKey, touch-required.

**Why:** the standard lists GPG-with-OpenPGP-applet as explicitly justifiable "si necesitas revocación real".
Published, signed release artifacts need a revocation story that outlives any one forge: an offline revocation
certificate works everywhere, whereas GitHub does not revoke SSH signing keys at all. The same key signs the
distributed `.zip` and its `.sha256`, which SSH signing does not cover.

**Obligation this creates:** a second enrolled backup key, held separately. Losing the only signing key means
losing the ability to sign anything users can verify against previous releases.

### 3. Published tags are immutable — CORRECTING A REAL VIOLATION

The standard is unambiguous: *"Prohibido mover un tag publicado: si la release está mal, se publica X.Y.Z+1"*.

**This repository violated that, repeatedly.** `v4.3.2` was re-cut and force-pushed three times, and `v4.4.1`
three times, each time moving a tag that had already been pushed to `origin` and attached to a published
GitHub release with signed artifacts.

**Why it is a real problem, not a formality.** A tag is the identity of a shipped artifact. Anyone who fetched
`v4.4.1` before a re-cut holds a different tree, a different `.zip` and a different SHA-256 than someone who
fetched it after — while both believe they have "v4.4.1". That breaks the one thing a signature is for:
proving *which* bytes were blessed. It also silently invalidates any checksum a user recorded, and makes
`git bisect` across the release boundary meaningless.

**Decision, effective immediately:** a tag that has been pushed is final. A mistake found after tagging is
fixed by cutting the next patch version, never by moving the tag. If a released version must be withdrawn, the
GitHub release is marked accordingly and a superseding version is published — the tag itself stays put.

**Not retroactively rewritten:** the already-moved tags are left alone. Re-cutting them again to "fix" the
history would repeat the exact mistake this decision exists to stop.

### 4. The CHANGELOG stays hand-written for now — deferral with an exit condition

The standard requires the changelog to be **generated** from the commit history, never maintained by hand in
parallel. This repository writes it by hand, and will keep doing so through 5.0.0.

**Measured, not assumed.** Of the last 100 commits, 67 are non-merge and **36** of those parse as Conventional
Commits. The shortfall is almost entirely `Release vX.Y.Z` commits — which ADR §1 already excludes from the
gate deliberately — but a generator does not know that: it would silently drop every commit it cannot parse
and produce a changelog that looks complete and is not. **A silently incomplete changelog is worse than an
honest hand-written one**, because it is trusted.

**`release-please` now *has* somewhere to run** (§5), so the remaining objection is only about the input.
It opens a release PR from the commit history; fed a history it can only half-parse, it produces a changelog
that looks complete and is not.

**Exit condition, so this does not quietly become permanent.** Generation is adopted when the history since
`v5.0.0` is clean, which the commit-msg hook plus the PR-only merge policy make the default outcome. It is
checkable in one command:

```sh
git log v5.0.0..HEAD --no-merges --format=%s \
  | grep -vcE '^(feat|fix|docs|refactor|perf|test|build|ci|chore|revert)(\([^)]+\))?!?: '   # → 0
```

At `0`, wire `release-please` as a workflow on `main` and delete this section. Until then the hand-written
changelog is the accurate one, and saying so here is the point: a recorded deviation with a test for when it
ends, not an oversight.

### 5. CI/CD on GitHub Actions, with publication gated three ways

The pipeline lives in `.github/workflows/` and the branch protections in `.github/rulesets/` (applied with
`scripts/apply-rulesets.sh`, so the gate is reviewable in a diff rather than editable in a settings page).

**The quality gate runs everywhere work happens** — `develop`, `main`, and every `feature/**`, `bugfix/**`
and `hotfix/**` branch — not only on the PR. A bar you only meet at PR time is a bar you discover late,
when the change is already large.

**Publication requires three independent things to hold**, and the middle one is the load-bearing part:

1. a `vX.Y.Z` **tag** — the artifact's identity, per §3;
2. the tagged commit is **reachable from `main`**, asserted in a job that runs before any secret is in
   scope. Since `main` accepts nothing but reviewed PRs, "reachable from main" *is* "was reviewed";
3. a **human approval** on the `marketplace` GitHub Environment, where the four credentials live scoped —
   so they do not exist for any other job in this repository.

Without (2), anyone able to push a tag could publish from any code, and the review that (3) assumes has
happened becomes optional. It is the cheapest of the three checks and the one that makes the other two mean
something.

**Every action is pinned by full commit SHA.** A tag is mutable and the action runs with this repository's
token. The counterweight to pin rot is Dependabot proposing the bumps weekly, so the pinning is free.
Provenance attestation is emitted and deliberately **not** overtrusted: a compromised runner can sign a
build that genuinely happened on it. The controls that actually cut that class are the SHA pins, the
read-only default token, and no secrets outside the approval-gated job.

**Build once.** The whole distributable is produced by a single `buildPlugin signPlugin publishPlugin`
invocation inside the approved job, and those exact bytes are what gets attested, checksummed,
GPG-signed, uploaded to the Marketplace and attached to the GitHub Release. An earlier draft split build
and publish across two jobs, which built the zip twice — and a Gradle zip is not byte-reproducible, so
users would have been offered two different artifacts under one version number, with a published checksum
matching only one of them. The order inside that single invocation is also load-bearing: verified against
`PublishPluginTask.kt`, `publishPlugin` uploads the signed archive **only if `signPlugin.didWork`**, and
silently falls back to the *unsigned* one otherwise — so splitting the tasks across invocations, or
touching the signed file before publishing, is how a plugin ships unsigned without anyone noticing.

**Not automated, on purpose:** the manual in-IDE test before release. Twice now a release passed every
automated check and was broken in the IDE — most recently `/login`, where every reflected platform API was
absent at runtime and every lookup failed silently. A pipeline cannot close that, and pretending otherwise
is how it shipped.

## Consequences

- `commitlint` runs as a versioned local hook (`.githooks/commit-msg`), enabled with
  `git config core.hooksPath .githooks`. It is advisory-on-toolchain-failure by design (see the hook's header)
  so it cannot become a reason to reach for `--no-verify`.
- Release notes are written by hand from the commits, and the PR template asks for the changelog entry at the
  point the change is made rather than reconstructing it at release time.
