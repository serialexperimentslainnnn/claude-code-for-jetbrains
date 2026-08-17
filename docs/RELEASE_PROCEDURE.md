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
| `ci.yml` | **pull requests only** into `develop` or `main`, plus a nightly schedule and manual dispatch | JVM tests + coverage, static analysis (detekt, Spotless, ESLint, Prettier, the project map), frontend tests, dependency audit, plugin verifier, artifact assertions, the release-door bot-PR check — and, on the schedule and the dispatch only, the UI end-to-end suite |
| `codeql.yml` | push/PR to `develop`/`main`; weekly | SAST over `java-kotlin` and `javascript-typescript`, `security-extended` queries |
| `release.yml` | **push to `main`** (primary); a `vX.Y.Z` tag (escape hatch) | Guard → verify → tag, build, sign, publish, GitHub Release — all in one gated job |
| `drift.yml` | weekly; manual | `checkDrift` against the current CLI and SDK; files an issue on real drift |

**There is deliberately no `push` trigger on `ci.yml`.** A branch with an open PR fires `pull_request` on
every push to it, so the loop is covered once instead of twice; a branch with no PR gets no checks at all,
which is the intent. The consequence worth knowing: **the gate is not uniform**. A PR into `develop` runs
only *JVM tests* and *Frontend tests*; static analysis, the dependency audit, the plugin verifier and the
artifact assertions run at the `develop → main` door, because each is expensive and that is the merge that
publishes. So a formatting or verifier failure can land *on* `develop` and be caught one merge later.

### Secrets

All six live in the **`marketplace` GitHub Environment**, never in repository
secrets. Environment scoping means they do not exist for any other job, and the
environment's deployment-branch policy restricts it to `main` and `v*.*.*` tags.

> **There is no required reviewer, deliberately** (verified against the API,
> 2026-08-11; `scripts/bootstrap-ci.sh` sets `reviewers: []` and logs that
> publish runs without a manual approval). So a merge into `main` that bumps the
> version **publishes to the Marketplace unattended** — the pull request into
> `main` is the human act, and there is no second one. The environment *scopes*
> the credentials; it does not gate on a person. See
> [`CI_SETUP.md`](CI_SETUP.md) §1 for how to add a reviewer if a second
> maintainer ever exists.

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

The RemoteRobot `uiTest` suite is not in the default pipeline and is not a
required check. It is a **client**: it drives an IDE that must already be
running, so it is two steps, and the second one is `uiTest`, not `test`.

```bash
xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runIdeForUiTests &   # keep it up
./gradlew uiTest -PuiTest.enabled=true                                 # then connect
```

The full harness, its two preconditions and what the suite can and cannot cover
are in [`UI_TESTING.md`](UI_TESTING.md).

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
gh pr list --base develop --state open   # must be empty of bot PRs
```

**Drain the bot queue first.** The `No bot PRs pending on develop` check is a
required status check on `main`, and it fails while Claude or Dependabot has a
pull request open against `develop`. That is deliberate — a release claims
`develop` is a finished state, and an un-merged dependency bump contradicts it —
but it means merging or closing those PRs is a release step, not an afterthought.

### 2. Run the full local verification

```bash
JAVA_HOME=~/.jdks/jbr-21.0.11 \
  ./gradlew test koverVerify detekt spotlessCheck verifyPlugin buildPlugin
npm ci && npm test && npm run lint && npm run format:check
npm audit --omit=dev --audit-level=low
```

Everything CI runs, run locally first — including the audit of the *distributed*
scope, which is the one that blocks. All tests must pass, and `verifyPlugin`
must report **Compatible** across the declared range — the floor,
`253.29346.138`, through the newest IDEA **and PyCharm** EAP/RC — with no
internal-API, override-only or **deprecated** API usage, all four of which are
failure levels in the build.
`buildPlugin` produces `build/distributions/claude-code-native-X.Y.Z.zip`.

Offline, or on a network that cannot pull 1.6 GB of IDE:
`./gradlew verifyPlugin -PlocalIdePath=<dir>[,<dir>…]` verifies against
locally-extracted installs and downloads nothing.

### 3. Bump the version

Edit `build.gradle.kts`:

```kotlin
version = "X.Y.Z"
```

Pick MAJOR / MINOR / PATCH per the rules above.

### 4. Update the changelog and release notes

Update **both** files with the new version and today's date:

- [`../CHANGELOG.md`](../CHANGELOG.md) — Keep a Changelog format with the
  sections `Added`, `Changed`, `Fixed`, `Security` as applicable. **There is no
  `Unreleased` section, on purpose**: `release.yml` publishes the newest
  `## [x.y.z]` block verbatim as the GitHub Release body, so a non-version
  heading at the top would ship as the release notes. Entries are written
  under the version being prepared, from the moment work starts on it.
- [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md) — narrative copy that
  Marketplace renders in the "What's New" panel. Keep it short and
  user-facing; `build.gradle.kts` extracts the latest section via
  `latestReleaseNotesHtml()` for `patchPluginXml.changeNotes`.

### 5. Commit

```bash
git add build.gradle.kts CHANGELOG.md RELEASE_NOTES.md
git commit -m "build: bump the version to X.Y.Z"
```

**Conventional Commits, including this one.** `commitlint` runs as a versioned
local hook (`.githooks/commit-msg`, enabled with
`git config core.hooksPath .githooks`) and the old `Release vX.Y.Z` subject does
not parse — it is the exact shortfall [ADR 0001 §4](adr/0001-release-process.md)
records as what still blocks generating the changelog.

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

### 7. Do NOT tag anything

**The workflow cuts the tag. You do not.** Running
`git tag -s vX.Y.Z && git push origin vX.Y.Z` yourself is actively harmful: a
hand-cut tag reaches the `marketplace` environment on the escape-hatch trigger,
skipping the merge into `main` that the whole review model rests on, and a tag
that beats the workflow to the name makes the automated release path stop
with *"already released"* on a version nobody published. Published tags are
immutable ([ADR 0001 §3](adr/0001-release-process.md)), so that mistake is not
undoable — the only exit is burning the version number and cutting the next
patch.

The version in `build.gradle.kts` is the single source of truth; the tag is
**derived** from it, which is precisely why nothing has to be kept in sync by
hand.

### 8. The merge publishes

Merging the PR pushes to `main`, which triggers `.github/workflows/release.yml`.
Three jobs, in order:

1. **`guard`** — reads the version out of `build.gradle.kts`, derives `vX.Y.Z`,
   and asserts the commit is **reachable from `main`** (`git merge-base
   --is-ancestor`). It runs before any secret is in scope, so a tag pushed from
   the wrong branch fails in seconds and reaches nothing. If the tag already
   exists on the remote it sets `release=false` and the run **stops without
   failing** — `main` legitimately takes merges that are not releases, and a red
   run on each of those is an alarm people learn to ignore.
2. **`verify`** — `npm ci && npm test` plus `./gradlew test verifyPlugin` on the
   exact tree being shipped, in the same container image the branch was green
   in. CI already ran on the PR; this re-runs it against what is actually going
   out.
3. **`publish`** — everything irreversible, in one job behind the `marketplace`
   environment. In order: import the CI signing key, **cut and sign the tag**,
   check that tag out, create the GitHub Release as a **draft**, then a single
   `buildPlugin signPlugin publishPlugin` invocation, attest provenance,
   checksum and GPG-sign the exact published bytes, attach them, and only then
   take the release out of draft.

Three properties of that job are load-bearing and easy to break:

- **The tag comes first and the build runs from it.** The tag is the identity of
  the release, so the artifact is produced from the ref that names it rather
  than stamped afterwards.
- **One Gradle invocation.** `publishPlugin` uploads the signed archive only if
  `signPlugin.didWork` and silently falls back to the *unsigned* one otherwise,
  so splitting the tasks is how a plugin ships unsigned. Building twice is also
  how users get two different zips under one version number: a Gradle zip is not
  byte-reproducible.
- **Recovery from a failed publish is a JOB re-run, not a workflow re-run.** The
  tag step detects an existing tag and verifies it instead of re-cutting, and the
  upload uses `--clobber`. A whole-workflow re-run would stop at `guard`, which
  now correctly sees the version as already released.

On the tag-push escape hatch the same three jobs run, and `guard` additionally
requires the tag name to match the declared version. That path exists for
re-cutting after a failed publish without pushing an empty commit to `main` — not
for releasing by hand.

### 9. Verify on Marketplace and on the Release

Within ~20 minutes the new version should appear at
<https://plugins.jetbrains.com/plugin/31965-claude-code-native>. Check:

- Version number and date.
- "What's New" panel matches the latest section of `RELEASE_NOTES.md` (that is
  what `latestReleaseNotesHtml()` feeds into `changeNotes`).
- Compatibility range (`since-build` / `until-build`) is correct.

The GitHub Release carries four files, and its notes come from **`CHANGELOG.md`**
— not `RELEASE_NOTES.md`, which is the storefront copy for a different reader:

```sh
gpg --import docs/ci-signing-key.asc
gpg --verify claude-code-native-X.Y.Z.zip.asc
sha256sum -c claude-code-native-X.Y.Z.zip.sha256
git verify-tag vX.Y.Z
```

Install the published zip into a real IDE and run the smoke test from
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).

### 10. Back-merge and close

`develop` is protected and accepts nothing but pull requests, so the back-merge
is a PR like any other — and it cannot be a fast-forward: GitHub creates the
merge commit on `main`, so `main` and `develop` end up with the same *tree* and
different SHAs.

```bash
git checkout -b chore/back-merge-X.Y.Z main
git push -u origin chore/back-merge-X.Y.Z
gh pr create --base develop --head chore/back-merge-X.Y.Z \
  --title "chore: back-merge vX.Y.Z into develop" --body "Post-release sync."
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
   pressure this goes through `main` — the release workflow refuses a commit
   that is not reachable from it, and a hotfix is exactly when you least want to
   discover you skipped the review.
6. The merge publishes, exactly as in §8. **Do not tag by hand**, least of all
   under pressure.
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
