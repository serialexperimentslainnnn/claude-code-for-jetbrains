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

The real CI lives in **GitLab** on a self-hosted runner; the GitHub Actions
workflows are inert reference (Actions is capped by billing).

| Where | File | Status |
|-------|------|--------|
| GitLab self-hosted | `.gitlab-ci.yml` | **Real pipeline.** Stages: `test` (`./gradlew test` — unit + headless + integration; installs `python3` for the fake-claude harness), `verify` (`./gradlew verifyPlugin`), `build` (`./gradlew buildPlugin`), and `publish` (stage `release`, tag-only `vX.Y.Z`, `when: manual`, runs `./gradlew signPlugin publishPlugin`). |
| GitHub Actions | `.github/workflows/*` | **Inert reference.** Automatic `push`/`pull_request`/`schedule` triggers are commented out; only `workflow_dispatch` remains. Nothing runs in CI here. |

Publish credentials live in **GitLab → Settings → CI/CD → Variables**
(`PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD`), masked + protected — not in GitHub Secrets.

### UI test suite

The UI (Swing/`uiTest`) suite is not in the default pipeline. Run it under a
virtual display:

```bash
xvfb-run -a ./gradlew test -PuiTest.enabled=true
```

### Drift detection

The sdk/binary drift-detection job is currently inert in GitHub (it was a
`schedule`-triggered workflow). It should be **ported to a GitLab scheduled
pipeline** so the check runs for real.

## Standard release

### 1. Sync `develop`

```bash
git checkout develop
git pull --ff-only
```

### 2. Run the full local verification

```bash
JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 \
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

Merge once the GitLab pipeline (test/verify/build) is green. **Do not**
rebase onto `main` — use a merge commit so the tag points to a commit that
exists on both branches.

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

### 8. GitLab pipeline publishes

The maintainer pushes the `vX.Y.Z` tag. On GitLab this triggers the tag
pipeline, which runs `test` → `verify` → `build` automatically. The
`publish` job (stage `release`) is **manual**: once the previous stages are
green, the maintainer presses **play** on `publish`, which runs
`./gradlew signPlugin publishPlugin` to sign the zip with the Marketplace
certificate and publish to JetBrains Marketplace.

The publish credentials (`PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`,
`PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) are configured in
**GitLab → Settings → CI/CD → Variables** as *masked + protected* — **not**
in GitHub Secrets.

> **Note:** GitHub Actions is capped (billing); `.github/workflows/release.yml`
> is only inert reference. The real publication runs through
> `.gitlab-ci.yml` on the self-hosted GitLab runner.

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
5. Open a PR `hotfix/X.Y.Z` → `main`. Merge once the GitLab pipeline
   (test/verify/build) is green.
6. Tag `vX.Y.Z` and push — the GitLab tag pipeline runs, then press **play**
   on the manual `publish` job to release.
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
