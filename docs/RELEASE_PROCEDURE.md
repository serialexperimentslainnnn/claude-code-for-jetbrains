# Release procedure

This document is the **single source of truth** for cutting a release of
Claude Code Native. Use it together with
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md), which is the verifiable
checklist applied per release.

For security-driven emergency releases, see the
[hotfix sub-procedure](#hotfix-sub-procedure) below and
[`../SECURITY.md`](../SECURITY.md).

## Versioning

We follow [Semantic Versioning 2.0.0](https://semver.org/):

- **MAJOR** — incompatible plugin behaviour, breaking settings migration,
  drop of an IDE build range.
- **MINOR** — new user-visible features, additive settings, new commands.
- **PATCH** — bug fixes, dependency bumps, internal refactors.

The current version is read from `build.gradle.kts` (the `version` property)
and surfaces in `plugin.xml` and the Marketplace listing.

## Continuous integration

CI/CD runs on **GitHub Actions** (since 5.0.0). The repository is public, so
standard hosted runners are free and unmetered — the earlier belief that
Actions was capped for billing was simply wrong, and `.gitlab-ci.yml` has been
removed rather than kept as a second pipeline that could also publish.

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | push to `develop`, `main`, `feature/**`, `bugfix/**`, `hotfix/**`; PRs | JVM tests, frontend tests, dependency audit, plugin verifier, build (asserting no npm code and that attribution is packaged) |
| `codeql.yml` | push/PR to `develop`/`main`; weekly | SAST over `java-kotlin` and `javascript-typescript`, `security-extended` queries |
| `release.yml` | `vX.Y.Z` tag | Guard → verify → build+attest → **publish** (approval-gated) → GitHub Release |
| `drift.yml` | weekly; manual | `checkDrift` against the current CLI and SDK; files an issue on real drift |

### Secrets

All six live in the **`marketplace` GitHub Environment**, never in repository
secrets. Environment scoping means they do not exist for any other job, and the
environment's required reviewer is the human gate on publication.

| Secret | What it is |
|---|---|
| `PUBLISH_TOKEN` | Marketplace API token (plugins.jetbrains.com → profile → **Tokens**) |
| `PRIVATE_KEY` | RSA private key (`private.pem`) for the **JetBrains plugin signature** — this is X.509/RSA, *not* GPG |
| `PRIVATE_KEY_PASSWORD` | passphrase for that key |
| `CERTIFICATE_CHAIN` | the matching `chain.crt` |
| `GPG_SIGNING_KEY` | armoured private key that signs the **release artifacts** (`.asc`) |
| `GPG_SIGNING_PASSPHRASE` | its passphrase |

`PRIVATE_KEY` / `CERTIFICATE_CHAIN` are an **upload key**, not a user-facing
signature: the Marketplace re-signs every plugin with JetBrains' own key before
serving it, so what an IDE verifies is JetBrains' signature. Rotating yours is
invisible to users; the only caution is that a Marketplace profile can pin a
public key, so a rotation may need the profile updated.

`GPG_SIGNING_KEY` is a different thing entirely — it signs the `.asc` files on
the GitHub Release and is certified by the maintainer's hardware key. See
[`../SECURITY.md`](../SECURITY.md) for what each signature claims.

All six are set by `./scripts/bootstrap-ci.sh`; see
[`CI_SETUP.md`](CI_SETUP.md) for doing any of it by hand.

Branch protection is versioned in `.github/rulesets/*.json` and applied with
`./scripts/apply-rulesets.sh` — see [ADR 0001 §5](adr/0001-release-process.md).

### UI test suite

The UI (Swing/`uiTest`) suite is not in the default pipeline. Run it under a
virtual display:

```bash
xvfb-run -a ./gradlew test -PuiTest.enabled=true
```

### Drift detection

`drift.yml` runs `checkDrift` weekly against the current published SDK and a
freshly installed `claude` CLI, and **files an issue** when the protocol surface
moves. It deliberately never commits: deciding whether a new message kind should
be modelled or ignored is a judgement call, and a bot that bumps the baseline on
its own would silently bless a gap. See `docs/DRIFT_DETECTION.md`.

## Standard release

### 1. Sync `develop`

```bash
git checkout develop
git pull --ff-only
```

### 2. Run the full local verification

```bash
JAVA_HOME=~/.jdks/jbr-21.0.11 \
  ./gradlew test verifyPlugin buildPlugin
```

All tests must pass, `verifyPlugin` must report **Compatible** for both
IU-261 and IU-262 with no new internal-API usage, and `buildPlugin` must
produce a zip in `build/distributions/`.

### 3. Bump the version

Edit `build.gradle.kts`:

```kotlin
version = "X.Y.Z"
```

Pick MAJOR / MINOR / PATCH per the rules above.

### 4. Update the changelog and release notes

Update **both** files with the new version and today's date:

- [`../CHANGELOG.md`](../CHANGELOG.md) — Keep a Changelog format with the
  sections `Added`, `Changed`, `Fixed`, `Security` as applicable. Move
  entries out of `Unreleased`.
- [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md) — narrative copy that
  Marketplace renders in the "What's New" panel. Keep it short and
  user-facing; `build.gradle.kts` extracts the latest section via
  `latestReleaseNotesHtml()` for `patchPluginXml.changeNotes`.

### 5. Commit

```bash
git add build.gradle.kts CHANGELOG.md RELEASE_NOTES.md
git commit -m "Release vX.Y.Z"
```

### 6. Open a release PR

```bash
git checkout -b release/X.Y.Z
git push -u origin release/X.Y.Z
gh pr create --base main --head release/X.Y.Z \
  --title "Release vX.Y.Z" --body "See CHANGELOG.md for details."
```

Merge once the GitHub Actions CI workflow is green. **Do not**
rebase onto `main` — use a merge commit so the tag points to a commit that
exists on both branches.

**The release PR is closed to late commits, cosmetic ones above all.** Everything the release
contains — code, tests, changelog, release notes, the version bump, and any wording or formatting
touch-up — goes in *before* the PR is opened. Once it is open, the branch is frozen except for a
fix to something the review or CI actually found.

This is not tidiness. A commit pushed onto an open release PR invalidates every gate that already
passed on it: the full local battery was run against a tree that is no longer the tree being
merged, and `main` is a publishing branch, so what lands there reaches users without a further
approval step. A "cosmetic" commit is the worst version of this, because it is the one nobody
re-verifies — the diff looks harmless, so the checks get read as still valid when they belong to a
different commit. If something cosmetic turns up mid-PR, it waits for the next release; if it truly
cannot wait, close the PR, land the change, re-run the full battery, and open a new one.

### 7. Tag and push

After the PR is merged:

```bash
git checkout main
git pull --ff-only
git tag -s vX.Y.Z -m "Claude Code Native vX.Y.Z"
git push origin vX.Y.Z
```

The tag must be **signed** (the repo enforces signed tags via the GitHub
ruleset on `main`).

### 8. The release workflow publishes

Pushing the `vX.Y.Z` tag triggers `.github/workflows/release.yml`, which runs
five jobs in order:

1. **`guard`** — asserts the tagged commit is reachable from `main` and that the
   tag matches `version` in `build.gradle.kts`. Runs before any secret is in
   scope, so a tag pushed from the wrong branch fails in seconds and reaches
   nothing.
2. **`verify`** — the full suite plus `verifyPlugin`, against the exact tagged
   tree rather than against whatever passed on `develop` last week.
3. **`build`** — `buildPlugin` once, records the SHA-256, and emits SLSA build
   provenance.
4. **`publish`** — `signPlugin publishPlugin`. Gated on the **`marketplace`
   environment**, so it waits for a human approval; the four credentials are
   scoped to that environment and exist nowhere else.
5. **`github-release`** — creates the GitHub Release with the zip and its
   checksum attached.

Nothing publishes without all three gates lining up: the tag, its lineage from
`main`, and the approval. See [ADR 0001 §5](adr/0001-release-process.md) for why
the middle one is not decoration.

### 9. Verify on Marketplace

Within ~20 minutes the new version should appear at
<https://plugins.jetbrains.com/plugin/dev.lain.claude-code-for-jetbrains>.
Check:

- Version number and date.
- "What's New" panel matches `RELEASE_NOTES.md`.
- Compatibility range (`since-build` / `until-build`) is correct.
- The download is the signed zip from `build/distributions/`.

Install the published zip into a real IDE and run the smoke test from
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).

### 10. Back-merge and close

```bash
git checkout develop
git merge --ff-only main   # if main is ahead; otherwise nothing to do
git push
```

Close the milestone in GitHub and any issues tagged with it.

## Hotfix sub-procedure

Use for P0/P1 security fixes (see [`../SECURITY.md`](../SECURITY.md)) or
critical regressions.

1. Branch from `main`, not `develop`:
   ```bash
   git checkout main && git pull --ff-only
   git checkout -b hotfix/X.Y.Z
   ```
2. Apply the minimum fix and a regression test.
3. Bump the **PATCH** segment in `build.gradle.kts`.
4. Add a `Security` entry to `CHANGELOG.md` and a one-paragraph note to
   `RELEASE_NOTES.md`.
5. Open a PR `hotfix/X.Y.Z` → `main`. Merge once CI is green. Even under
   pressure this goes through `main` — the release workflow refuses a tag whose
   commit is not reachable from it, and a hotfix is exactly when you least want
   to discover you skipped the review.
6. Tag `vX.Y.Z` and push — `release.yml` runs, then approve the `marketplace`
   environment to publish.
7. **Back-merge** into `develop`:
   ```bash
   git checkout develop
   git merge --no-ff main
   git push
   ```
8. If the fix is for a disclosed CVE, publish the GitHub Security Advisory
   and notify the original reporter.

## Rollback

If a release is broken, do **not** delete the Marketplace version — it stays
for users who installed it. Instead:

1. Publish a new PATCH that reverts or fixes the regression, following the
   hotfix procedure.
2. In an extreme case (RCE, credential leak), email JetBrains Marketplace
   support to request hiding the bad version. Do this **after** publishing
   the fix.
