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
2. Merge `develop` → `main` via PR. `main` is protected: the CI checks must be green and the PR approved.
3. Tag the merge commit `vX.Y.Z` and push the tag. `release.yml` then verifies the tag came from `main`,
   re-runs the full gate on the tagged tree, builds and attests, and waits on the `marketplace` environment
   approval before `signPlugin publishPlugin`.

> A tag pushed from anywhere other than `main` is rejected by the workflow's first job, before any
> credential is in scope. That is the mechanism that makes "release only via PR into main" true rather
> than merely intended.

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

## Branch protection (versioned, not clicked)

Since 5.0.0 the protections live in **`.github/rulesets/*.json`** and are applied with:

```sh
./scripts/apply-rulesets.sh --dry-run   # show what would change
./scripts/apply-rulesets.sh             # apply (idempotent — updates by name, never duplicates)
```

They are in the repository rather than in a settings page for the same reason the pipeline is: a control
whose job is to be non-bypassable should be reviewable in a diff, not silently editable by whoever holds
admin. What they enforce:

| | `main` | `develop` |
|---|---|---|
| Pull request required | yes | yes |
| Required approvals | **0** — see below | **0** — see below |
| Status checks | tests, frontend, audit, verifier, build, **both CodeQL analyses** | tests, frontend, audit, verifier, build |
| Branch must be up to date | yes | yes |
| Signed commits | required | required |
| Merge method | merge commit only (keeps the release commit identifiable) | squash or merge |
| Force push / deletion | blocked | blocked |
| Admin bypass | **none** | **none** |

Three deliberate choices worth stating:

- **Zero required approvals, and this is not a weakened gate — it is the only value that is not a
  deadlock.** GitHub does not let an author approve their own pull request. With one maintainer and no
  bypass actors, "require 1 approval" means *nothing can ever be merged*: not by push, not by PR, not by
  admin. We found this the direct way, by locking the repository and having to unlock it. What is actually
  enforced here is mechanical and cannot be talked out of: a pull request, an up-to-date branch, and every
  status check green. A human approval is a real control when a second human exists; requiring one that
  cannot exist is theatre that bolts the door from the inside. **Raise it to 1 — and re-enable
  `require_code_owner_review` and `require_last_push_approval` — the day someone else has write access.**
- **No bypass actors, including admins.** The previous version of this document kept an admin bypass "so a
  maintainer can land an urgent hotfix when a structural check would otherwise block the merge". The
  structural check it referred to — capped GitHub Actions — never existed. A bypass exists to be used at the
  worst possible moment, under time pressure, on the change least likely to have been reviewed. The hotfix
  path goes through `main` like everything else.
- **The UI test suite (`uiTest`) is advisory and must NOT become a required check.** It needs a display, it
  is slower, and a flaky required check teaches people to re-run until green.

> A ruleset references a status check by the job's **display name**. Renaming a job does not fail the
> gate — it silently stops applying. After renaming anything in `.github/workflows/`, re-check the names in
> `.github/rulesets/*.json`.
