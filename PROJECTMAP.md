# Map of Claude Code Native (JetBrains plugin)

> Generated 2026-08-11 against `0daffe7` (branch `feature/release_5.5.0`, clean tree). If anything here does
> not match the repo, **the repo wins**: fix the line and move on. Maintained per the `project-map` skill.
>
> This file says **where** things are. How work is done here — protocol invariants, release rules, the
> "never mirror raw CLI output" principle — lives in [`CLAUDE.md`](CLAUDE.md) and is not repeated.

## I want to change… → go to…

| To… | Go to | Note |
|---|---|---|
| Handle a new binary→host event | `protocol/ClaudeEvent.kt` (model + `typed(...)` registry) then `session/ClaudeSession.kt` `onEvent` | Triage the subtype into `ProtocolSurface.KNOWN_SUBTYPES` or `checkDrift` goes red |
| Send a host→binary control request | `protocol/ControlProtocol.kt` (builder) + `session/SessionControlClient.kt` (correlation, watchdog) | Every request is correlated by `request_id`; never block on the reply |
| Change how a turn is orchestrated | `session/ClaudeSession.kt` | **Thin orchestrator**: new behaviour goes to a collaborator, not here |
| Change transcript rows / streaming | `session/TranscriptReconciler.kt` + `session/TranscriptModel.kt` | Assumes EDT. `parentOf`/`isDescendantOf` model subagent nesting |
| Change what the chat renders | `resources/jcef/app-transcript.js` (rows), `app-composer.js` (input + readout), `app-permissions.js` (cards), `app-session.js` (dashboard), `app-tabs.js` (tab bar) | Inlined ES2019, no bundler. CSS class names are a tested contract |
| Change a style | `resources/jcef/css/<part>.css` | Concatenated **in cascade order** by `JcefHost.CSS_PARTS`; the tests read that same list |
| Add a field to the web payload | `ui/jcef/JcefState.kt` (composer state), `ui/jcef/JcefSessionData.kt` (dashboard) or `ui/jcef/JcefTabsData.kt` (tab bar) | kotlinx `buildJsonObject`; null-safe so a card omits cleanly. A running/finished state uses `JcefStatus` |
| Add a web→host message | `ui/jcef/JcefBridge.kt` (pure parse) + `ui/JcefChatPanel.kt` (dispatch) | `JcefBridge` has no IDE deps so it unit-tests on plain JVM |
| Change tabs / tool window | `ui/ClaudeToolWindowFactory.kt` + `ui/ChatTabsPanel.kt` (host) and `resources/jcef/app-tabs.js` (the bar itself) | The bar is drawn by the WEB app, not by Swing |
| Change permission behaviour | `permission/PermissionBroker.kt`; hard rules in `permission/SensitiveGuard.kt` | `SensitiveGuard` runs **before** any auto-approval |
| Change what a diff shows / writes | `diff/DiffPresenter.kt`, `diff/HunkSelection.kt`, `session/DiffLifecycleManager.kt` | The **binary** writes the file; the IDE only reviews and refreshes VFS |
| Add a setting | `settings/ClaudeSettings.kt` (a field on `State`) + `ui/ClaudeSettingsConfigurable.kt` | Persisted as the `@Serializable State` document in the **PasswordSafe** (`settings/SettingsStore.kt`); mutate through `update {}` or it does not survive a restart. `applyTo(session)` seeds launch options |
| Change launch flags | `session/SessionLauncher.kt` (`buildArgs`) | Immutable `LaunchOptions` snapshot; `--print` is mandatory |
| Touch auth / credentials | `process/CredentialsVault.kt`, `process/AuthCli.kt`, `session/LoginCoordinator.kt`, `settings/SecretStore.kt` | Credentials reach the binary **by env only**, never argv, never logs |
| Read a past session | `session/SessionStore.kt` (paths, traversal guard) + `session/SessionTranscriptReader.kt` (JSONL → entries) | The binary's files are the source of truth; the plugin persists no transcripts |
| Change CI or the release | `.github/workflows/ci.yml`, `release.yml`; policy in `docs/RELEASE_PROCEDURE.md` | Merging to `main` publishes to Marketplace |

## Structure

- `src/main/kotlin/dev/lain/claudejb/`
  - `process/` — locating, launching and authenticating the `claude` binary.
  - `protocol/` — kotlinx models + NDJSON parser + control-frame builders. **No IDE dependencies.**
  - `session/` — one `ClaudeSession` per chat tab plus its single-responsibility collaborators
    (`TokenAccountant`, `TaskTracker`, `TranscriptReconciler`, `DiffLifecycleManager`,
    `SessionControlClient`, `PermissionCardManager`, `HookBroker`, `HookActivityNarrator`,
    `LoginCoordinator`), the agent tree (`AgentRegistry`, `AgentMeta`, `PluginAgentIndex`), the background
    tasks (`BackgroundTaskRegistry`, `BackgroundTaskReplay`, `TaskOutputFile`, `LiveOutputTail`), and the
    session-history readers.
  - `permission/` — the `can_use_tool` broker and the deterministic sensitive-data lock.
  - `diff/` — native diff presentation, hunk selection, edit snapshots, rollback.
  - `ui/` — tool window, tab strip, settings, dialogs, and the panel's collaborators (`LinkNavigator`,
    `SessionFeed`, `AttachmentTray`, `BackgroundTaskView`); `ui/jcef/` is the host↔web bridge.
  - `context/`, `actions/`, `settings/` (`SettingsStore` = the PasswordSafe document, `AlwaysAllowTools`),
    `util/`.
- `src/main/resources/jcef/` — the inlined web app: `shell.html`, `app-*.js`, `css/*.css`, vendored
  `marked`/`purify`/`highlight`. Served under a hash-pinned CSP, as ONE document assembled by `JcefHost`.
- `src/test/kotlin/` — unit + `headless/` (`BasePlatformTestCase`) + `integration/` (drives `bin/fake-claude`).
- `src/test/frontend/` — vitest + jsdom over the **real** `resources/jcef/*.js`.
- `src/uiTest/` — RemoteRobot, off by default (`-PuiTest.enabled=true`).
- `docs/` — release, branching, compat, threat model and ADRs. `docs/BACKLOG.md` is probed, not guessed.

## Entry points

- **Plugin**: `src/main/resources/META-INF/plugin.xml` → tool window `Claude Code` →
  `ui/ClaudeToolWindowFactory.kt` → `ui/ChatTabsPanel.kt` → one `ui/JcefChatPanel.kt` per chat.
- **Session**: `session/ClaudeSession.start()` → `session/SessionLauncher.buildArgs` →
  `process/ClaudeProcess.kt` (stdio) → `ProtocolParser` (an object inside `protocol/ClaudeEvent.kt`) →
  `ClaudeSession.onEvent`.
- **Web app**: `ui/jcef/JcefHost.kt` serves `resources/jcef/shell.html` and injects the CSP; the page boots
  `app-core.js` and registers `window.cc.*`.
- **Test stand-in for the binary**: `bin/fake-claude` (Python) with fixtures in `src/test/resources/fixtures/`.

## Commands

| What | Command | Verified |
|---|---|---|
| Build the plugin zip | `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew buildPlugin` → `build/distributions/` | 2026-08-11 |
| Full JVM gate | `./gradlew clean test koverVerify detekt spotlessCheck verifyPlugin buildPlugin --rerun-tasks` | 2026-08-11 |
| Fix formatting | `./gradlew spotlessApply` | 2026-08-11 |
| Frontend tests | `npm test` (vitest + jsdom) | 2026-08-11 |
| Frontend lint/format | `npm run lint` · `npm run format:check` · `npm run format` | 2026-08-11 |
| Production dependency audit | `npm audit --omit=dev` | 2026-08-11 |
| Protocol drift | `./gradlew checkDrift -PclaudeBinary=/usr/bin/claude` | 2026-08-11 |
| Unreachable CSS rules | `python3 scripts/css-usage.py` | 2026-08-11 |
| Verify against local IDEs | `./gradlew verifyPlugin -PlocalIdePath=<dir>[,<dir>…]` | unverified here |
| Run a sandbox IDE | `./gradlew runIde` | unverified here |

On this machine only: `node` needs `OPENSSL_CONF=/dev/null`, and `claude` is a system install at
`/usr/bin/claude` (the drift task defaults to `~/.local/bin/claude`).

## Conventions and invariants

- **Where new behaviour goes**: a `ClaudeSession` collaborator, a JS module or a JSON builder — never back
  into `ClaudeSession` or `JcefChatPanel`, which are an orchestrator and an assembler.
- Threading: I/O and parsing off-EDT; every UI mutation on the EDT via the session's `edt {}` dispatcher.
- `protocol/` stays free of IDE classes so it unit-tests on a plain JVM. Same for `ui/jcef/JcefBridge.kt`.
- Frontend: no bundler, no CDN; a new CSS class used from JS needs a real rule or `css-contract.test.js`
  fails. The stylesheet is several files concatenated **in cascade order** (`JcefHost.CSS_PARTS`) — order is
  semantics, so a part goes where its rules belong, not at the end.
- **One state vocabulary** for everything the page colours: `running` · `completed` · `failed` · `stopped`,
  decided in `ui/jcef/JcefStatus.kt`. The page paints the word the host sends; it never derives one.
- Tests live under the mirrored package path; headless and integration tests must run inside the `test`
  task (the platform runtime is only wired there).

## Minefields

- `session/ClaudeSession.kt` (~3.1k lines, most-churned source file) — property/`init` **declaration order
  matters**: `InitOrderContractTest` scans the sources because the compiler only catches the direct case.
  The same applies to `ui/JcefChatPanel.kt`, whose `init` reads fields declared above it on purpose.
- `permission/SensitiveGuard.kt` — walks every string leaf of a tool input as a path candidate. Two live
  false positives came from that (`// comment` read as UNC; `$` in a shell value read as a regex
  replacement). Change `pathCandidates`/`foreignHome` with tests first.
- `ui/jcef/JcefHost.kt` — the CSP is hash-pinned over the exact bytes of each inline script AND of the
  concatenated stylesheet. Editing `shell.html`'s inline blocks changes the hash; anything that would need
  `unsafe-inline` is a no, and so is loading an asset by URL (the scheme handler serves ONE document).
- `resources/jcef/shell.html` — `#boot` and `#auth-card` live inside `#work`, which is what keeps them off
  the tab bar. Moving them back under `#app` re-covers the chat tabs while a chat is starting.
- `.github/workflows/release.yml` — merging to `main` publishes. The `guard` job refuses to re-release a
  version whose tag already exists; the tagging step is deliberately idempotent.
- `session/SessionTranscriptReader.kt` — restoring a transcript from the binary's JSONL. Synthetic `user`
  lines (`<task-notification>`, caveats) are not the user speaking; `isMeta`/`isSidechain` are on the wire.
- `bin/fake-claude` + `src/test/resources/fixtures/` — the integration tests' contract. A fixture edited
  without its test is a green suite that proves nothing.

## Out of the map

`node_modules/` (the SDK there is **protocol reference only**, never shipped), `build/`, `.gradle/`,
`.idea/`, `build/distributions/*.zip`.
