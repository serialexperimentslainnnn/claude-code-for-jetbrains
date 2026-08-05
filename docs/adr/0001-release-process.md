# ADR 0001 — Release process: branching, signing and tag immutability

- **Status:** accepted
- **Date:** 2026-08-05
- **Context skill:** `git-workflow-standards`

## Context

The plugin is installable software distributed through the JetBrains Marketplace, with real users on
previously published versions. Releases are cut manually from a workstation; there is no CI gate yet (a local
lab is planned). This ADR records the three places where the repository deliberately departs from the default
standards, and the one place where it was simply **wrong**.

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

## Consequences

- `commitlint` runs as a versioned local hook (`.githooks/commit-msg`), enabled with
  `git config core.hooksPath .githooks`. It is advisory-on-toolchain-failure by design (see the hook's header)
  so it cannot become a reason to reach for `--no-verify`.
- The `CHANGELOG.md` is still written by hand. The standard requires it to be generated from the commit
  history, and that remains **open**: generation is only meaningful once enough history is Conventional for the
  tooling not to silently drop most of it. The commit gate landing now is the prerequisite, not the fix.
- No release automation (`release-please`) is wired, because it is a GitHub Action and this project's GitHub
  Actions are disabled for billing. Revisit when the local lab exists.
