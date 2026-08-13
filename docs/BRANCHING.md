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
2. Merge `develop` → `main` via PR. `main` is protected: a pull request is required, it must be up to date,
   and every required check must be green. It does **not** require an approval — see *Branch protection*
   below for why zero is the only value that is not a deadlock here.
3. **That is the whole procedure.** The merge triggers `release.yml`, which reads the version from
   `build.gradle.kts`, re-runs the full gate on the merged tree, and then — inside the single
   `marketplace`-scoped job — cuts and signs the `vX.Y.Z` tag, builds *from that tag*, signs, publishes,
   attests, and attaches the artifacts to a GitHub Release.

   The tag is cut **before** the build, not after: the tag is the identity of the release, so the artifact is
   produced from the ref that names it rather than being labelled once it has already gone out.

**`build.gradle.kts` is the single source of truth for the version.** The tag is derived from it rather than
supplied alongside it, so the two can no longer disagree — the failure mode the old flow guarded against with
a comparison simply cannot occur now.

**A merge to `main` that does not bump the version publishes nothing.** The workflow finds the tag already
present, logs a notice and stops. It does not fail: `main` legitimately receives merges that are not releases,
and a red run on each of those is an alarm people learn to ignore. Published tags remain immutable.

> Pushing a `vX.Y.Z` tag by hand still works and is kept as the escape hatch — re-cutting after a failed
> publish, without pushing an empty commit to `main`. On that path the tag must match the declared version,
> and its commit must be reachable from `main`, both checked before any credential is in scope.

### What the signatures claim, now that the tag is automatic

The tag is cut by the workflow and signed with the **CI key**, not the maintainer's YubiKey — which cannot
sign inside a runner, and whose non-exportability is precisely what makes it worth trusting. The chain still
terminates in hardware, because the CI key is certified by the YubiKey.

The cost is stated rather than glossed: **no signature on a release asserts that a person authorised it.**
Anyone verifying a release should read `git verify-tag` as *"this workflow cut this from main"*, not as
*"a human signed off"*.

> **What that claim rests on, checked rather than assumed (2026-08-11).** It was written as two gates: the
> reviewed pull request into `main`, and a required reviewer on the `marketplace` environment. **There is one
> gate.** The environment's only protection rule is its deployment-branch policy (`main` and `v*.*.*`);
> `gh api repos/…/environments/marketplace` lists no `required_reviewers`, and `scripts/bootstrap-ci.sh` sets
> `reviewers: []` on purpose. Publication is therefore **unattended** — the merge is the last human act.
> That is defensible on a single-maintainer repository, where an approval is the same person clicking twice;
> what is not defensible is leaving a gate everyone believes exists. Adding one back is
> [`CI_SETUP.md`](CI_SETUP.md) §1, and belongs with a second maintainer.

## Cleaning up obsolete branches

The four stale branches this section used to list (`feature/compatibility`, `feature/use-recognized-libraries`,
`fix/security-issues`, `test/MCPSkills`) are **gone from `origin`** — checked, not assumed. Nothing is pending.

The standing rule is the general one: a topic branch is deleted when its PR merges, and the check is one
command.

```sh
git fetch --prune
git branch -r --merged origin/develop | grep -vE 'origin/(HEAD|develop|main)$'
```

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
| Status checks | JVM tests, Static analysis, Frontend tests, Dependency audit, Plugin verifier, Build plugin, both CodeQL analyses, **No bot PRs pending on develop** | JVM tests, Frontend tests, both CodeQL analyses |
| Branch must be up to date | **yes** (strict) | no |
| Signed commits | required | required |
| Merge method | **merge commit — the only one enabled** | **merge commit — the only one enabled** |
| Force push / deletion | blocked | blocked |
| Admin bypass | **none** | **none** |

`develop` requires a deliberately smaller set: the expensive checks (static analysis, the dependency audit,
the plugin verifier, the artifact assertions) run at the `develop → main` door, and `ci.yml` does not even
start them on a pull request into `develop`. The cost is real and named in the workflow: a detekt or verifier
failure can land *on* `develop` and is fixed by a follow-up commit rather than being caught in the PR.

Four deliberate choices worth stating:

- **Merge commit is the ONLY method enabled on this repository**, and squash and rebase are switched off at
  the repository level rather than merely discouraged here. This is a *signing* decision, not a taste in
  history shape. Every commit in this project is signed by a hardware-backed key, and both of the other
  methods **rewrite commits**: GitHub creates new SHAs and new committer information, which invalidates those
  signatures and replaces them with GitHub's own `web-flow` key. The rule "signed commits required" would
  still pass — the commits are signed, just no longer *by the author*, which is the entire property the rule
  exists to give. Leaving the buttons enabled meant one wrong click could quietly destroy that provenance, so
  the buttons are gone.

  The cost, stated plainly: the merge node itself is created and signed by GitHub, because the alternative is
  merging locally and pushing, which the pull-request requirement blocks — and relaxing *that* to save one
  commit's provenance would be a far worse trade. `main` therefore ends up with the same *tree* as `develop`
  but not the same SHA. Release provenance rests on the **tag** rather than on the merge node — and, since
  the tag is cut inside the workflow, it is signed by the **CI key** (certified by the YubiKey), not by the
  YubiKey directly. See the section above for exactly what that signature claims.

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
