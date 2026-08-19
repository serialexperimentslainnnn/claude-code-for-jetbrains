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
the last 5.x tag is clean, which the commit-msg hook plus the PR-only merge policy make the default outcome.
It is checkable in one command — and the ref it names has to be a tag that **exists**, or the check passes
by printing `0` for an empty stream (see the amendment below, where it did exactly that):

```sh
git log v5.0.1..HEAD --no-merges --format=%s \
  | grep -vcE '^(feat|fix|docs|refactor|perf|test|build|ci|chore|revert)(\([^)]+\))?!?: '   # → 0
```

At `0`, wire `release-please` as a workflow on `main` and delete this section. Until then the hand-written
changelog is the accurate one, and saying so here is the point: a recorded deviation with a test for when it
ends, not an oversight.

> **Amendment, 2026-08-11 — the anchor was wrong and the test was vacuous.** It read `v5.0.0..HEAD`, and
> **there is no `v5.0.0` tag**, in this clone or on `origin` (the 5.x tags are `v5.0.1`, `v5.1.0`, `v5.1.1`).
> `git log` failed to stderr, `grep -vc` counted an empty stream, and the command printed `0` — the answer the
> exit condition is waiting for — while measuring nothing. A check that cannot fail is worse than no check,
> which is this ADR's own argument about the changelog, turned on its own test.
>
> Re-anchored to `v5.0.1` above. **It now genuinely reports `0` across all 34 non-merge commits since that
> tag**, so the exit condition has in fact fired: the hook plus the PR-only merge policy did what §4 predicted.
> Adopting `release-please` is now a scheduling decision rather than a data problem — and the one remaining
> objection is not commit hygiene but the release trigger, since a release-PR bot and "a merge to `main`
> publishes what `build.gradle.kts` declares" are two different sources of truth for the version.

**A defect that comes with hand-writing it, recorded rather than fixed here: the release date is stamped by
hand, so it can lie.** *Keep a Changelog* puts a date on every `## [x.y.z]` heading, and that heading is
published **verbatim** as the GitHub Release body (§5) — so the date a user reads is whatever was typed while
the section was being written, which is days or weeks before the merge that actually releases it. Nothing
re-checks it and nothing can: the date sits in the same commit as the notes, while the release is triggered
by a merge whose timing is not known when they are written. `RELEASE_NOTES.md` carries the same date and the
same defect, in the same commit.

**The exit is to stop storing it and derive it.** `release.yml` already computes the version from
`build.gradle.kts` when it cuts the tag, and that job is the only actor that knows the real release date — so
the heading keeps the version and the workflow stamps the date into the Release body it assembles. That is a
change to the release workflow and is deliberately not made from a feature branch; it lands with whichever
comes first, adopting `release-please` (which dates the heading itself, making this moot) or the next change
to `release.yml` for any other reason.

**Until then the rule is: a date in a `## [x.y.z]` heading is the date the section was written, not the date
it shipped.** Re-stamp it in the release PR if the two have drifted, and do not treat it as evidence of when
anything was published — the tag is.

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
3. a **human approval** on the `marketplace` GitHub Environment, where the credentials live scoped —
   so they do not exist for any other job in this repository.

Without (2), anyone able to push a tag could publish from any code, and the review that (3) assumes has
happened becomes optional. It is the cheapest of the three checks and the one that makes the other two mean
something.

> **Amendment, 2026-08-11 — three of these statements no longer describe the repository.** The decisions
> above stand; the descriptions have been overtaken by later changes, and are corrected here rather than
> rewritten, per this directory's own rule.
>
> - **The gate no longer runs everywhere work happens.** `ci.yml` has no `push` trigger at all: it runs on
>   pull requests into `develop` or `main`. A branch with no PR gets no checks, deliberately ("no PR, no
>   promotion"), and the heavy checks run only at the `develop → main` door. The paragraph's own argument —
>   that discovering the bar late is expensive — is now a cost the pipeline accepts in exchange for not
>   running every pipeline twice.
> - **The tag is no longer an input to publication; it is an output of it.** The primary trigger is a push to
>   `main`, the version is read from `build.gradle.kts`, and `release.yml` cuts and signs the tag itself
>   inside the gated job, before building from it. Item (1) is therefore a property of the release rather
>   than a precondition a human supplies — and §3's immutability rule is what makes cutting it early safe to
>   reason about. A hand-pushed tag remains a supported escape hatch, and on that path item (1) reads as
>   originally written.
> - **"Reachable from `main`" is not "was reviewed".** Item (2)'s wording above predates the rulesets being
>   written down: both set `required_approving_review_count: 0` (§5, and `.github/rulesets/*.json`), so what
>   "reachable from `main`" actually certifies is *"went through the pull-request gate"* — a PR was opened, the
>   branch was up to date, and every required check was green. That is mechanical and cannot be talked out of;
>   it is simply not a second person's judgement. No document here may describe a merge to `main` as reviewed.
> - **Item (3) no longer exists, and its removal was deliberate.** The `marketplace` environment's only
>   protection rule is its deployment-branch policy (`main`, `v*.*.*`); it carries no required reviewer, and
>   `scripts/bootstrap-ci.sh` sets `reviewers: []` while logging that publish runs without a manual approval.
>   So publication rests on **two** gates, and the human judgement is entirely in the pull request into
>   `main`. The reasoning is the same one §5 gives for zero required approvals on the branch rulesets: with a
>   single maintainer, an approval prompt is the same person confirming their own merge, which is ceremony
>   rather than control. It is re-added with a second maintainer (`docs/CI_SETUP.md` §1) — and until then no
>   document may describe releases as approval-gated.
>
> Also corrected: the environment holds **six** secrets, not four — the Marketplace token, the three parts
> of the JetBrains upload key, and the CI GPG key with its passphrase.

**Every action is pinned by full commit SHA.** A tag is mutable and the action runs with this repository's
token. The counterweight to pin rot is Dependabot proposing the bumps — **monthly**, per
`.github/dependabot.yml`, with security updates arriving on their own schedule regardless — so the pinning
is free.
Provenance attestation is emitted and deliberately **not** overtrusted: a compromised runner can sign a
build that genuinely happened on it. The controls that actually cut that class are the SHA pins, the
read-only default token, and no secrets outside the one environment-scoped job (see the amendment above:
that job is scoped, not approval-gated).

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
