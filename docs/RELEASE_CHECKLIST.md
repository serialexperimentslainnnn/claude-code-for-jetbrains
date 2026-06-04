# Release checklist

Copy this checklist into the release PR description and tick each box. The
full procedure is in [`RELEASE_PROCEDURE.md`](RELEASE_PROCEDURE.md); this
file is the verifiable per-release gate.

## Pre-flight

- [ ] On a clean working tree on `develop` (or `hotfix/*` for a hotfix).
- [ ] `git pull --ff-only` shows no surprises.
- [ ] Target version selected per SemVer rules (see procedure §Versioning).

## Build & verification

- [ ] `./gradlew test` — all unit tests pass (currently 132+).
- [ ] `./gradlew verifyPlugin` — **Compatible** with IU-261 **and**
      IU-262/RC.
- [ ] Verifier report has **no new internal-API usage**
      (`@ApiStatus.Internal`).
- [ ] No new deprecated or scheduled-for-removal IntelliJ Platform APIs in
      the diff since the last tag.
- [ ] `./gradlew buildPlugin` produces a zip under
      `build/distributions/claude-code-for-jetbrains-X.Y.Z.zip`.

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
- [ ] GitLab pipeline (test/verify/build) green on `develop` before
      promoting to `main`.
- [ ] PR `release/X.Y.Z` → `main` opened and the GitLab pipeline is green.
- [ ] Signed tag `vX.Y.Z` pushed to `main` (the GitHub ruleset enforces
      this via GPG / YubiKey).
- [ ] GitLab tag pipeline ran green; the manual `publish` job (stage
      `release`) was triggered and published to Marketplace.

## Post-release

- [ ] Marketplace listing shows the new version within ~20 minutes.
- [ ] GitHub Release created with the signed zip attached.
- [ ] Milestone for `vX.Y.Z` closed and linked issues closed.
- [ ] `develop` back in sync with `main` (fast-forward or merge as needed).
- [ ] Auto-memory / project notes updated if release status changed.
