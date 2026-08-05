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
./gradlew test                 # 677 tests: unit + headless component + integration. The gate.
npm test                       # 54 frontend tests (vitest + jsdom) over the real resources/jcef/*.js
./gradlew buildPlugin          # → build/distributions/*.zip
./gradlew verifyPlugin         # compatibility across the declared range (251 → 263.*)
./gradlew runIde               # sandbox IDE with the plugin loaded
./gradlew checkDrift           # protocol drift vs the live binary + SDK (updates both, then reports)
./gradlew koverHtmlReport      # coverage
```

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
| `ci.yml` | every push to `develop`, `main`, `feature/**`, `bugfix/**`, `hotfix/**`, and every PR | JVM tests, frontend tests, dependency audit, plugin verifier, build + artifact assertions |
| `codeql.yml` | push/PR to the protected branches, weekly | SAST over Kotlin and JavaScript |
| `release.yml` | `vX.Y.Z` tag only | lineage guard → full gate → build + attest → approval-gated publish |
| `drift.yml` | weekly | `checkDrift`; files an issue on real protocol drift |

`main` and `develop` are protected by versioned rulesets (`.github/rulesets/`, applied with
`./scripts/apply-rulesets.sh`). **There is no bypass, including for admins.** If a check blocks you, fix
the check or fix the code — do not ask for it to be turned off "just this once", which is the request
that makes a gate decorative.

Two artifact assertions in `ci.yml` are worth knowing about because they will fail your PR if you change
packaging: the distributed zip must contain **zero** `node_modules` entries, and the jar must carry
`META-INF/LICENSE` and `META-INF/THIRD-PARTY-NOTICES.md`. Both are claims made to users in `SECURITY.md`
and in the licence attribution, enforced rather than trusted.

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
  Releases are signed from a workstation with a hardware key; there is no automation to fall back on.
- **Never move a published tag.** ADR 0001 §3 exists because this was violated repeatedly. A mistake found
  after tagging is fixed by the next patch version.
- **Never weaken `permission/SensitiveGuard.kt`** to make a task easier. If it blocks you, that is the control
  working; say so and ask. Its adversary is written down in [ADR 0002](docs/adr/0002-threat-model.md).
- **Never ship a deprecated or scheduled-for-removal IntelliJ Platform API.** `verifyPlugin` flagging one is a
  blocker, not a warning.
- **Never write a real absolute path from a developer's machine** into code, docs, or a commit message.
- **Never add a runtime dependency** without checking its licence against GPL-3.0-only and recording it in
  `THIRD-PARTY-NOTICES.md` if it ships in the artifact.

## Conventions that are load-bearing

- **`ClaudeSession` is an orchestrator, not a god object.** New behaviour goes in a collaborator under
  `session/` (or a new one), never inline. The class was decomposed once; it does not get to re-grow.
- **Never mirror raw CLI output.** Every state is reconstructed natively from the structured event's fields.
  `system/local_command_output` is the antipattern.
- **Diffs stay native** (the IDE's `DiffManager`); the chat UI is the JCEF web app under `resources/jcef/`.
- **Frontend changes need frontend tests.** The JS↔CSS class contract test exists because a missing CSS rule
  once shipped silently.
- **UI changes need a keyboard pass.** Automated checks catch roughly half of accessibility barriers and none
  of the judgement calls. Drive what you changed with the keyboard alone and confirm the focus ring is visible.

## Manual verification is not optional

Unit tests and CLI checks have **twice** passed a release that was broken in the IDE — most recently the
`/login` regression, where every platform API the code reflected on was absent at runtime and every lookup
failed *silently*. Before anything is released, the built zip is installed in a real IDE and the actual change
is exercised by hand.
