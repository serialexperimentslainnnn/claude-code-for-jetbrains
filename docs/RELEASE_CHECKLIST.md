# Release checklist

Copy this checklist into the release PR description and tick each box. The
full procedure is in [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md); this
file is the verifiable per-release gate.

## Pre-flight

- [ ] On a clean working tree on `develop` (or `hotfix/*` for a hotfix).
- [ ] `git pull --ff-only` shows no surprises.
- [ ] Target version selected per SemVer rules (see procedure §Versioning).

## Build & verification

- [ ] `./gradlew test` — all unit tests pass (currently 682, 2 Windows-only skips).
- [ ] `./gradlew detekt spotlessCheck` — static analysis and formatting clean.
- [ ] `npm run lint && npm test` — the shipped JCEF frontend lints clean, 54 tests pass.
- [ ] `./gradlew koverVerify` — coverage gates hold (see **Coverage policy** below).
- [ ] `./gradlew verifyPlugin` — **Compatible** with IU-261 **and**
      IU-262/RC.
- [ ] Verifier report has **no new internal-API usage**
      (`@ApiStatus.Internal`).
- [ ] No new deprecated or scheduled-for-removal IntelliJ Platform APIs in
      the diff since the last tag.
- [ ] `./gradlew buildPlugin` produces a zip under
      `build/distributions/claude-code-for-jetbrains-X.Y.Z.zip`.

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
Chromium; it is covered by a different layer — 54 vitest tests that drive the *real shipped JS*, plus the
mandatory manual pass in §Smoke test below. `context/` and `process/` wrap the OS (system clipboard, process
spawn, shell environment) and most of what is uncovered there cannot execute on a CI box. That is a **known
gap**, listed so nobody mistakes it for coverage; the pure parts of both (`AttachmentEncoder`,
`EnvScriptLoader.parse`) *are* tested. Gating any of these at 20% would dress the same fact up as a passing
check.

**Known limitation.** Kover 0.9.2's `KoverVerifyRule` has no per-rule filter, so the exact per-package numbers
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
- [ ] [`BINARY_COMPAT.md`](BINARY_COMPAT.md) updated **only if** the
      supported `claude` binary range changed, with a new row and any
      newly handled / pending events.

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

- [ ] Settings → Plugins → ⚙ → Install Plugin from Disk → pick the new zip.
- [ ] Restart IDE.
- [ ] Tool window "Claude Code" appears on the right.
- [ ] New chat: model chip, mode chip, effort chip, thinking chip all show
      the expected defaults.
- [ ] Send a prompt that triggers an Edit tool call — permission card
      appears inline, "View diff" opens a diff in the editor area (not a
      modal window).
- [ ] Approve a hunk — the binary writes, VFS refreshes, the file shows
      the change.
- [ ] Restart IDE with `restoreOpenChatsOnStartup` enabled — chats are
      reopened via `--resume`.

## Git hygiene

- [ ] Commit message: `Release vX.Y.Z`.
- [ ] CI green on `develop` before promoting to `main`.
- [ ] PR `release/X.Y.Z` → `main` opened, CI green, approved.
- [ ] Signed tag `vX.Y.Z` pushed to `main` (the ruleset enforces this via
      GPG / YubiKey).
- [ ] `release.yml` reached the `publish` job and the `marketplace`
      environment approval was granted; the version is live on Marketplace.

## Post-release

- [ ] Marketplace listing shows the new version within ~20 minutes.
- [ ] GitHub Release created with the signed zip attached.
- [ ] Milestone for `vX.Y.Z` closed and linked issues closed.
- [ ] `develop` back in sync with `main` (fast-forward or merge as needed).
- [ ] Auto-memory / project notes updated if release status changed.
