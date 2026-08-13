# Release checklist

Copy this checklist into the release PR description and tick each box. The
full procedure is in [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md); this
file is the verifiable per-release gate.

## Pre-flight

- [ ] On a clean working tree on `develop` (or `hotfix/*` for a hotfix).
- [ ] `git pull --ff-only` shows no surprises.
- [ ] Target version selected per SemVer rules (see procedure §Versioning).
- [ ] **No open bot pull requests against `develop`** (`gh pr list --base develop
      --state open`). `No bot PRs pending on develop` is a required check on
      `main` and will block the release PR until the queue is drained.

## Build & verification

- [ ] `./gradlew test` — green, with **no failures**; the only expected skips are
      the two Windows-only tests.
- [ ] `./gradlew detekt spotlessCheck` — static analysis and formatting clean,
      with `config/detekt/baseline.xml` **untouched** (it holds exactly two
      accepted `ClaudeSession` findings; regenerating it to make a build pass is
      how the gate stops meaning anything).
- [ ] `npm test && npm run lint && npm run format:check` — the shipped JCEF
      frontend passes vitest, ESLint and Prettier.
- [ ] `./gradlew koverVerify` — coverage gates hold (see **Coverage policy** below).
- [ ] `./gradlew verifyPlugin` — **Compatible** across the declared range: the
      floor (253) through the newest IDEA **and PyCharm** EAP/RC.
- [ ] Verifier report clean at all four failure levels the build declares —
      compatibility problems, internal API, override-only API, and **deprecated
      API**. A deprecation is a blocker here, not a warning.
- [ ] `./gradlew buildPlugin` produces
      `build/distributions/claude-code-native-X.Y.Z.zip`.

## Coverage policy

Coverage is gated **per package**, not globally, because the risk in this codebase is not evenly spread:
`permission/` decides whether the agent may read your SSH key, and `ui/` paints a browser. One global number
would either set the bar low enough that the guard could rot unnoticed, or high enough that the only way to
meet it is writing tests against Swing and JCEF that assert nothing anyone cares about.

Thresholds are set slightly **below** what each package measures today — a gate that catches regression, not a
target that invites test-padding. Line coverage measured 2026-08-05:

| package | line % | gated |
|---|---|---|
| `permission/` | 98.1 | ✅ |
| `protocol/` | 87.3 | ✅ |
| `settings/` | 86.1 | ✅ |
| `diff/` | 72.8 | ✅ |
| `session/` | 67.3 | ✅ |
| `context/`, `process/` | 42.1 / 37.9 | ❌ excluded — known gap |
| `ui/`, `ui/jcef/` | 24.6 / 31.2 | ❌ excluded — covered elsewhere |
| `actions/` | 0.0 | ❌ excluded — one delegate call each |

**Excluded, and why it is stated rather than gated at a token value.** `ui/` needs a live IDE and a live
Chromium; it is covered by a different layer — the vitest suite, which drives the *real shipped JS* out of
`src/main/resources/jcef/`, plus the mandatory manual pass in §Smoke test below. `context/` and `process/` wrap the OS (system clipboard, process
spawn, shell environment) and most of what is uncovered there cannot execute on a CI box. That is a **known
gap**, listed so nobody mistakes it for coverage; the pure parts of both — `ClipboardCli` and
`ImageAttachments` in `context/` (`ClipboardCliTest`, `ImageAttachmentsTest`), and `EnvScriptLoader.parse` in
`process/` — *are* tested. Gating any of these at 20% would dress the same fact up as a passing check.

(This paragraph used to name `AttachmentEncoder`, which was deleted in 5.5.0 with the last of the Swing
composer it served. `build.gradle.kts`'s kover exclusion carries the same list; the two must agree.)

**Known limitation.** `KoverVerifyRule` has no per-rule filter — re-checked at **0.9.9**, the version the
build uses — so the exact per-package numbers
above are not individually expressible in the build. What `koverVerify` enforces is a **floor applied to every
gated package** plus an **aggregate** — both real gates (the floor catches one package collapsing, the
aggregate catches death by a thousand cuts), but looser than the table. If a future Kover adds per-rule
filters, tighten `build.gradle.kts` to match this table.

> Historical note: until 5.0.0 a comment in `build.gradle.kts` claimed a "≥90% target … documented in
> `docs/RELEASE_CHECKLIST.md`". This file had never said that, and the real figure was 53%. The number was
> never measured and the requirement it cited did not exist.

## Documentation

- [ ] [`../CHANGELOG.md`](../CHANGELOG.md) updated with the new version,
      today's date, and entries under the right Keep-a-Changelog sections
      (`Added`, `Changed`, `Fixed`, `Security`).
- [ ] [`../RELEASE_NOTES.md`](../RELEASE_NOTES.md) updated with a
      user-facing narrative for the new version.
- [ ] `change-notes` renders cleanly in the Marketplace HTML — verify by
      running `./gradlew patchPluginXml` and inspecting
      `build/patchedPluginXmlFiles/plugin.xml` (the `<change-notes>` tag
      should contain the latest section, extracted by
      `latestReleaseNotesHtml()` in `build.gradle.kts`).
- [ ] [`../README.md`](../README.md) install instructions still match
      reality (Marketplace name, link, settings paths).
- [ ] [`BINARY_COMPAT.md`](BINARY_COMPAT.md) updated **only if** the protocol
      baseline or the IDE range moved, with a new row in the matching table.
- [ ] [`../CLAUDE.md`](../CLAUDE.md) and [`../PROJECTMAP.md`](../PROJECTMAP.md)
      reflect the release — the version, and anything that moved or was added.

## Version metadata

- [ ] `build.gradle.kts` `version` bumped to `X.Y.Z`.
- [ ] `since-build` / `until-build` in `build.gradle.kts` still cover the
      currently shipped EAP/RC.
- [ ] Plugin id (`dev.lain.claude-code-for-jetbrains`) and name
      ("Claude Code Native") unchanged unless this is a deliberate
      breaking release.

## Smoke test on a real IDE

Install the freshly built zip into a real IDE — not the Gradle sandbox —
and walk through a short end-to-end scenario.

Find the IDE config directory under the Toolbox install, e.g. on Linux:

```
~/.local/share/JetBrains/Toolbox/apps/intellij-idea/
```

Steps:

This step is **not automatable and not optional**: twice now a release passed
every automated check and was broken in the IDE (ADR 0001 §5).

- [ ] Settings → Plugins → ⚙ → Install Plugin from Disk → pick the new zip.
- [ ] Restart IDE.
- [ ] Tool window "Claude Code" appears on the right, and the chat **renders** —
      i.e. JCEF loaded. Do this on the newest IDE available, not only on the one
      you develop in: the 5.1.1 breakage was a classloader difference that only
      appeared from build 262.
- [ ] New chat: model chip, mode chip, effort chip, thinking chip all show
      the expected defaults.
- [ ] Send a prompt that triggers an Edit tool call — permission card
      appears inline, "View diff" opens an **editable** diff in the editor area
      (not a modal window).
- [ ] Accept it — the edit is written whole (per-hunk acceptance was removed in
      4.0.5), VFS refreshes, and the file shows the change.
- [ ] Run something that spawns a subagent — it gets its **own tab** with its own
      transcript, and the main transcript links to it instead of interleaving.
- [ ] Restart the IDE — the previously open chats are reopened via `--resume`,
      and you are **still signed in** (the vaulted credential renews itself from
      the refresh token rather than falling back to the sign-in card).

## Git hygiene

- [ ] Version-bump commit is a Conventional Commit (`build: bump the version to
      X.Y.Z`) — `commitlint` rejects the old `Release vX.Y.Z` subject.
- [ ] Every commit signed with the YubiKey (the ruleset requires signatures, and
      merge commit is the only merge method enabled, because squash and rebase
      would rewrite the commits and strip those signatures).
- [ ] PR `release/X.Y.Z` → `main` opened, full CI green.
- [ ] **No tag pushed by hand.** `release.yml` cuts and signs `vX.Y.Z` itself,
      with the CI key, inside the `marketplace`-scoped job. A hand-cut tag
      bypasses the merge and cannot be undone — published tags are immutable
      (ADR 0001 §3).
- [ ] `release.yml` reached `publish` and it completed. **There is no approval
      prompt** — the `marketplace` environment carries no required reviewer, by
      design, so the merge you just made was the last human act. See
      [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md) §Secrets.

## Post-release

- [ ] Marketplace listing shows the new version within ~20 minutes.
- [ ] GitHub Release out of draft, with four assets: the signed zip, its
      `.sha256`, and a `.asc` for each.
- [ ] `git verify-tag vX.Y.Z` and `gpg --verify` on the artifact both pass
      against `docs/ci-signing-key.asc`.
- [ ] Milestone for `vX.Y.Z` closed and linked issues closed.
- [ ] `develop` back in sync with `main` — via a **pull request**; `develop` is
      protected and a fast-forward is impossible anyway, since GitHub creates the
      merge commit on `main`.
- [ ] Auto-memory / project notes updated if release status changed.
