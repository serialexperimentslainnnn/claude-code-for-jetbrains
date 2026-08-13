# AGENTS.md — working on this repository with a coding agent

This repository is **prepared for agentic development** and is maintained that way on purpose. If you are an
AI coding agent (Claude Code, or any tool that reads `AGENTS.md`), this file is your runbook: how to build,
how to verify, and what you must not do.

Division of labour, so neither file rots:

- **[`CLAUDE.md`](CLAUDE.md)** — the *architecture*: what the plugin is, the protocol contract, the
  collaborator layout, and the history of why things are the way they are. Read it before changing code.
- **`AGENTS.md`** (this file) — the *operations*: commands, gates, conventions, boundaries.
- **[`docs/adr/`](docs/adr/README.md)** — the decisions that are hard to reverse or easy to reverse by
  accident. If a change contradicts an ADR, the ADR is updated in the same change or the change is wrong.

## Environment

| Requirement | Value | Note |
|---|---|---|
| JDK | **21**, the JetBrains Runtime | `export JAVA_HOME=~/.jdks/jbr-21.0.11` (or your JBR 21). The IDE runs on JBR 21 — that is the ceiling, not a preference. |
| Gradle | wrapper, **9.5.1** | Always `./gradlew`, never a system Gradle. |
| Node | any current LTS | Frontend tests only. Nothing from npm ships in the plugin. |
| `claude` binary | preinstalled, on `PATH` or `~/.local/bin` | Required at *runtime* by the plugin and by `checkDrift`. The plugin never downloads one. |

Every Gradle command below assumes `JAVA_HOME` is set. A wrong or missing `JAVA_HOME` fails with
`ERROR: JAVA_HOME is set to an invalid directory` — note the **uppercase** ERROR, which a lowercase-only grep
will miss and report as a successful build.

## Commands

```sh
./gradlew test                 # unit + headless component + integration. The gate.
npm test                       # frontend tests (vitest + jsdom) over the real resources/jcef/*.js
./gradlew detekt spotlessCheck # static analysis + formatting, both gated in CI
npm run lint && npm run format:check   # the same two, for the shipped JCEF JavaScript
./gradlew buildPlugin          # → build/distributions/*.zip
./gradlew verifyPlugin         # compatibility across the declared range (253 → 263.*)
./gradlew runIde               # sandbox IDE with the plugin loaded
./gradlew checkDrift           # protocol drift vs the live binary + SDK (updates both, then reports)
./gradlew koverHtmlReport      # coverage (koverVerify is the gate, and runs with `test` in CI)
```

Test counts are deliberately not written here: they change every release and a number in a runbook is a
claim nobody re-checks. `./gradlew test` and `npm test` report their own.

**The floor is 253 (2025.3), not 251.** `sinceBuild` moved in 5.5.0 because the whole UI is JCEF and
`com.intellij.modules.jcef` — declared **hard** in `plugin.xml` — does not exist as a module id before 253.
Do not "fix" a verifier complaint by widening it back or by making that dependency optional: an optional
dependency that cannot be satisfied is skipped, which is exactly the silent breakage on 262 this replaced.
`JcefDependencyContractTest` is the gate and it is mutation-checked.

`verifyPlugin`'s CDN download is unreliable here. Use locally-extracted IDEs:
`./gradlew verifyPlugin -PlocalIdePath=<dir>[,<dir>…]` (comma-separated).

`checkDrift` is **not** in `test` by design: it hits the network and mutates the local toolchain
(`npm update`, `claude --update`). Run it deliberately, and when it reports advancement, bump
`scripts/drift-baseline.properties` to what you actually verified.

## What CI runs, and where the gate is

CI/CD is GitHub Actions (`.github/workflows/`). Everything below also runs locally with the commands
above — if a gate only fails in CI, that is a workstation-provisioning problem, not a pipeline problem.

| Workflow | When | What |
|---|---|---|
| `ci.yml` | **pull requests only** (into `develop` or `main`), plus manual dispatch | JVM tests + coverage, static analysis, frontend tests, dependency audit, plugin verifier, build + artifact assertions |
| `codeql.yml` | push/PR to `develop` and `main`, weekly | SAST over `java-kotlin` and `javascript-typescript`, `security-extended` |
| `release.yml` | push to `main`, or a `vX.Y.Z` tag | lineage guard → full gate on the tagged tree → sign + attest → publish, with the credentials scoped to the `marketplace` environment (which is a scope, not an approval) |
| `drift.yml` | weekly, plus manual dispatch | `checkDrift`; **files an issue**, never commits — reconciling drift is a judgement call |

**A merge into `main` publishes to the Marketplace, unattended.** `release.yml` reads the version from
`build.gradle.kts`, and if that version has never been tagged it cuts the tag, builds, signs and publishes —
no approval step. The `marketplace` environment *scopes* the credentials; it has no required reviewer
(deliberate, reasoned out in `scripts/bootstrap-ci.sh` §1, and verifiable with
`gh api repos/OWNER/REPO/environments`). So a version bump merged to `main` **is** the release decision.
Treat any change to `build.gradle.kts`'s `version` as irreversible from the moment the PR into `main` merges.

**`ci.yml` has no `push` trigger, on purpose.** A branch with an open PR fires `pull_request` on every push
to it, so the loop is covered once instead of twice. The consequence, stated because it is easy to trip on:
**a branch with no open pull request gets no checks at all**, and there is no CI run on the merge commit that
lands on `develop`. Open the PR early.

Not every job runs on every PR. `JVM tests` and `Frontend tests` run on all of them; `Static analysis`,
`Dependency audit`, `Plugin verifier`, `Build plugin` and `No bot PRs pending on develop` are gated to pull
requests targeting **`main`** — the release door — because the verifier alone is ~10 minutes and 1.25 GB of
IDE downloads. So a formatting or audit failure can land on `develop` and is caught before it can be
promoted, not before it is merged.

The release door has one gate that is not a test: **`No bot PRs pending on develop`** fails a PR into `main`
while Claude or Dependabot still has a pull request open against `develop`. A release claims `develop` is a
finished state; an open bot PR says otherwise. Drain the queue, do not widen the filter.

`main` and `develop` are protected by versioned rulesets (`.github/rulesets/`, applied with
`./scripts/apply-rulesets.sh`). **There is no bypass, including for admins.** If a check blocks you, fix
the check or fix the code — do not ask for it to be turned off "just this once", which is the request
that makes a gate decorative.

**What that protection is and is not.** Both rulesets set `required_approving_review_count: 0`, so a pull
request is required but a second person's approval is not — GitHub will not let an author approve their own
PR, so on a single-maintainer repository any higher value locks the branch rather than guarding it (the
reasoning is written out in `main.json`). The gate is therefore entirely mechanical: a PR is required, it
must be up to date, every required check must pass, and commits must be signed. Do not describe a merge to
`main` as "reviewed" in docs or release notes — say it passed the pull-request gate, which is what is
actually enforced. Raise the count to 1 the moment a second maintainer has write access.

**A ruleset names a required check by the job's DISPLAY name, not by its id.** Renaming a job in a workflow
does not fail the gate — it silently stops applying it, and the branch keeps merging with one fewer control
than the file says it has. If you rename a job, change `.github/rulesets/*.json` in the same commit and
re-run `./scripts/apply-rulesets.sh`. The names in force today are `JVM tests`, `Static analysis`,
`Frontend tests`, `Dependency audit`, `CodeQL (java-kotlin)`, `CodeQL (javascript-typescript)`,
`Plugin verifier`, `Build plugin` and `No bot PRs pending on develop`.

Two artifact assertions in `ci.yml` are worth knowing about because they will fail your PR if you change
packaging: the distributed zip must contain **zero** `node_modules` entries, and the jar must carry
`META-INF/LICENSE`, `META-INF/THIRD-PARTY-NOTICES.md` **and a `META-INF/licenses/` text for every file in
this repository's `LICENSES/`** — that last set is derived from the checkout, so adding a licence text
extends the gate by itself (and a text that is not committed is a `THIRD-PARTY-NOTICES.md` pointer that
dangles in the artifact). Both are claims made to users in `SECURITY.md` and in the licence attribution,
enforced rather than trusted.

## Before you commit

1. **Enable the hook, once per clone:** `git config core.hooksPath .githooks`
   It lints the commit message against Conventional Commits. It is deliberately *advisory* if the toolchain
   itself fails, so it can never become a reason to reach for `--no-verify`.
2. **Conventional Commits.** `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `chore`, `revert`;
   `!` or a `BREAKING CHANGE:` footer for a break. The body explains **why** — the diff already says what.
3. **Refactor and behaviour change go in separate commits.** Mixing them makes review impossible and
   `git bisect` useless.
4. **Both suites green** (`./gradlew test` and `npm test`). A red suite is not "unrelated".

## Boundaries — do not cross these without being asked

- **Never `git commit`, `git push`, tag, or publish unless explicitly told to.** Show the diff and stop.
- **Never cut a release tag by hand.** `release.yml` derives the tag from `build.gradle.kts` and cuts it
  itself, signed with the project's CI key. Pushing a tag yourself either collides with that or publishes
  from a tree the guard was written to refuse. Bump the version, open the PR, let the pipeline tag.
- **Never move a published tag.** ADR 0001 §3 exists because this was violated repeatedly. A mistake found
  after tagging is fixed by the next patch version. `release.yml` treats an existing tag as "already
  released" and stops, deliberately without failing.
- **Never touch the release gates.** Not the `marketplace` environment, not the lineage check in `guard`,
  not the ruleset bypass list, not what publishes or when. That is the maintainer's call, in the open, not
  a step in somebody's task.
- **Never weaken `permission/SensitiveGuard.kt`** to make a task easier. If it blocks you, that is the control
  working; say so and ask. Its adversary is written down in [ADR 0002](docs/adr/0002-threat-model.md).
- **Never ship a deprecated or scheduled-for-removal IntelliJ Platform API.** `verifyPlugin` flagging one is a
  blocker, not a warning.
- **Never write a real absolute path from a developer's machine** into code, docs, or a commit message.
- **Never add a runtime dependency** without checking its licence against GPL-3.0-only and recording it in
  `THIRD-PARTY-NOTICES.md` if it ships in the artifact.

## Conventions that are load-bearing

The architectural rules — `ClaudeSession` stays a delegating orchestrator, never mirror raw CLI output,
diffs stay native while everything else is the JCEF web app, no Swing in the UI — live in
**[`CLAUDE.md`](CLAUDE.md)** and are **not repeated here**. Two documents saying the same thing is how both
end up stale, and CLAUDE.md is the one that carries the reasoning. Read it; the rules below are the ones
that are about *working*, not about the design.

- **Frontend changes need frontend tests.** The JS↔CSS class contract test exists because a missing CSS rule
  once shipped silently. `src/main/resources/jcef/*.js` is loaded for real by `src/test/frontend/`, so a
  module you add is a module the harness must be told to load.
- **UI changes need a keyboard pass.** Automated checks catch roughly half of accessibility barriers and none
  of the judgement calls. Drive what you changed with the keyboard alone and confirm the focus ring is visible.
- **Refactors are verified byte-for-byte where that is possible.** The stylesheet split in 5.5.0 was checked
  identical to the original before it landed, and `JcefHost.CSS_PARTS` is read by the tests so a part cannot
  go untested. A "pure move" nobody diffed is not a pure move.
- **Do not regenerate `config/detekt/baseline.xml` to make a build pass.** It holds exactly two accepted
  findings, both explained in the file. Growing it is how a quality gate becomes a record of what was
  ignored.

## Manual verification is not optional

Unit tests and CLI checks have **twice** passed a release that was broken in the IDE. First the 4.4.1
`/login` regression, where every platform API the code reflected on was absent at runtime and every lookup
failed *silently*. Then 5.1.1, which could not open a single chat on 2026.2 — `verifyPlugin` said
**Compatible** throughout and was not wrong: it resolves against the whole IDE distribution, while the
failure lived in the plugin's classloader.

The lesson each time is the same: **a green pipeline is evidence about the pipeline's model of the IDE, not
about the IDE.** Before anything is released, the built zip is installed in a real IDE — and, when the change
touches the platform boundary, in more than one major version — and the actual change is exercised by hand.
