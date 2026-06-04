# Branching & release model

This repo follows a lightweight **GitFlow**. Two long-lived branches, short-lived topic branches, and tags
drive releases.

## Long-lived branches

| Branch     | Purpose                                                                                  |
|------------|------------------------------------------------------------------------------------------|
| `main`     | **Release branch.** Only ever holds released, tagged commits. Every commit is a release. |
| `develop`  | **Integration branch.** Default target for PRs; the next release accumulates here.        |

`main` is updated by merging `develop` (or a `release/*`/`hotfix/*` branch) when cutting a release, then
tagging. Day-to-day work never targets `main` directly.

## Short-lived branches

Branch off `develop` (except `hotfix/*`, which branches off `main`), open a PR back into the same base, and
delete the branch once merged.

| Prefix       | Branches off | Merges into        | For                                            |
|--------------|--------------|--------------------|------------------------------------------------|
| `feature/*`  | `develop`    | `develop`          | New functionality.                             |
| `bugfix/*`   | `develop`    | `develop`          | Fixes for not-yet-released bugs.               |
| `hotfix/*`   | `main`       | `main` + `develop` | Urgent fixes to a released version.            |
| `release/*`  | `develop`    | `main` + `develop` | Stabilising a version before tagging (optional). |

Naming: `feature/<short-kebab-summary>`, e.g. `feature/hunk-selection`, `bugfix/jump-to-code-navigation`.

## Releasing

1. Land everything for the version on `develop`; bump `version` in `build.gradle.kts` and add the section to
   `RELEASE_NOTES.md` / `CHANGELOG.md`.
2. Merge `develop` → `main` via PR (the GitLab pipeline — test/verify/build — must be green).
3. Tag the merge commit `vX.Y.Z` and push the tag. The **GitLab tag pipeline** then runs
   `test` → `verify` → `build` automatically; the maintainer presses **play** on the manual `publish` job
   (stage `release`), which runs `signPlugin publishPlugin` to sign and publish to the Marketplace.

> **Note:** The real CI runs on **GitLab self-hosted**; GitHub Actions is inert (billing). See
> `.gitlab-ci.yml`.

## Cleaning up obsolete branches

The following branches are stale and should be deleted once their work has landed on `develop`/`main`.
**Verify each is fully merged before deleting** (`git branch --merged develop` / check the PR), then run the
commands. They are commented so nothing is deleted by accident — the maintainer runs them deliberately.

```sh
# Inspect first: confirm there is nothing unmerged on these branches.
# git log --oneline develop..origin/feature/compatibility
# git log --oneline develop..origin/feature/use-recognized-libraries
# git log --oneline develop..origin/fix/security-issues
# git log --oneline develop..origin/test/MCPSkills

# Delete the remote branches once confirmed merged:
# git push origin --delete feature/compatibility
# git push origin --delete feature/use-recognized-libraries
# git push origin --delete fix/security-issues
# git push origin --delete test/MCPSkills

# Prune local tracking refs afterwards:
# git fetch --prune
```

Note the naming drift: `fix/security-issues` and `test/MCPSkills` predate this convention (they would be
`bugfix/*` and a `feature/*` today). New branches should follow the prefixes in the table above.

## Branch protection (configure in GitHub UI)

Set these rules for both `main` and `develop` under **Settings → Branches → Branch protection rules** (or a
repository ruleset):

- **Require a pull request before merging** — no direct pushes.
- **Require status checks to pass before merging** — select the checks reported by the **GitLab pipeline**
  (test/verify/build), surfaced either via the GitLab↔GitHub integration or the GitLab MR pipeline,
  depending on the team's flow. The UI test suite is **advisory only** and must NOT be a required check.
- **Require branches to be up to date before merging.**
- **Require signed commits** — GPG/SSH-signed (matches the repo's existing `required_signatures` setup).
- **Allow administrators to bypass** — keep the documented admin bypass so a maintainer can land an urgent
  hotfix when a structural check (e.g. capped Actions) would otherwise block the merge.
- **Restrict who can push to matching branches** — maintainers only.
- For `main` additionally: **do not allow force pushes or deletions.**
