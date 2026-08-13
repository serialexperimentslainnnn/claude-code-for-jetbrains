# Map of Claude Code Native (JetBrains plugin)

> Generated 2026-08-11, refreshed **2026-08-13**, against the WORKING TREE of branch `feature/release_5.5.0`
> — `HEAD` is still `cf73e32` and the tree is **not clean**: the whole 5.5.0 release is uncommitted (~237
> changed paths, ~102 of them untracked, 15 deleted). Every path, line count and file list below was read
> **off disk**, not off `HEAD`; `git ls-files` alone misses most of this release. The change counts move while
> work is in flight and are shape, not contract — the structural counts below are the ones worth trusting.
> If anything here does not match the repo, **the repo wins**: fix the line and move on. Maintained per the
> `project-map` skill.
>
> **Gate state at the 08-13 refresh: all green**, every one of them run on this machine — `test` (1 000 tests,
> 0 failures, 2 Windows skips), `detekt`, `spotlessCheck`, `koverVerify`, `npm test` (161), `lint`,
> `format:check`, `npm audit --omit=dev` (0), `checkDrift` (no drift), `verifyPlugin` (Compatible on IU-253,
> IU-261, IU-262 ×2, PY-262) and `buildPlugin`.
>
> This file says **where** things are. How work is done here — protocol invariants, release rules, the
> "never mirror raw CLI output" principle — lives in [`CLAUDE.md`](CLAUDE.md) and is not repeated.

## I want to change… → go to…

| To… | Go to | Note |
|---|---|---|
| Handle a new binary→host event | `protocol/ClaudeEvent.kt` (the sealed case) + the model file of its family (`TaskModels`/`NoticeModels`/`UsageModels`/`SessionSignalModels`/`HookModels`/`PermissionModels`/`ConversationModels`/`InitializeModels`) + `protocol/ProtocolParser.kt` (the `TOP_LEVEL_DECODERS`/`SYSTEM_DECODERS` tables), then `session/ClaudeSession.kt` `onEvent` | Decoders are **tables, not branches** — register, don't add an `if`. Triage the subtype into `src/test/kotlin/dev/lain/claudejb/drift/ProtocolSurface.kt` `KNOWN_SUBTYPES` or `checkDrift` goes red |
| Send a host→binary control request | `protocol/ControlProtocol.kt` (builder) + `session/ControlAsks.kt` (one declared ask: what to send, how to read the answer) + `session/SessionQueries.kt` (the UI-facing surface) + `session/SessionControlClient.kt` (correlation, watchdog) | Every request is correlated by `request_id`; never block on the reply |
| Change how a turn is orchestrated | `session/ClaudeSession.kt` | **Thin orchestrator** (2 507 lines, most-churned source file). New behaviour goes to a collaborator, not here |
| Change transcript rows / streaming | `session/TranscriptReconciler.kt` + `session/TranscriptModel.kt` | Assumes EDT. `parentOf`/`isDescendantOf` model subagent nesting |
| Change what the chat renders | `resources/jcef/app-transcript*.js` (rows, tool cards, links, find bar), `app-composer-*.js` (input, pills, palette, attachments, readout, boot + auth cards), `app-permissions.js` (cards), `app-session-*.js` (dashboard), `app-tabs-*.js` (tab bar) | 30 inlined ES2019 files, no bundler. **Load order is a contract** — see `JcefHost.appNames`: `app-core.js` first, each family's namespace (`-base.js`, or `app-transcript.js` for its own) before its members, and the composer/session/tabs spine LAST. CSS class names are a tested contract |
| Change a style | `resources/jcef/css/<part>.css` (7 parts) | Concatenated **in cascade order** by `JcefHost.CSS_PARTS`; `src/test/frontend/helpers/load.js` parses that same Kotlin list rather than keeping its own |
| Add a field to the web payload | Composer/meta → `ui/jcef/JcefState.kt`; a transcript row → `JcefTranscriptPayload.kt`; a permission card → `JcefCardPayload.kt`; a dashboard card → `JcefSessionData.kt` with `JcefCostData`/`JcefUsageData`/`JcefAccountData`/`JcefWorkloadData`; the tab bar → `JcefTabsData.kt`; a composer pill → `JcefComposerOptions.kt`; a model's on-screen name → `JcefModelLabels.kt` | kotlinx `buildJsonObject`; null-safe so a card omits cleanly. A running/finished state uses `JcefStatus` |
| Add a web→host message | `ui/jcef/JcefBridge.kt` (pure parse) + `ui/ChatBridgeRouter.kt` (dispatch) | `JcefBridge` has no IDE deps so it unit-tests on plain JVM |
| Change tabs / tool window | `ui/ClaudeToolWindowFactory.kt` + `ui/ChatTabsPanel.kt` (holds chats, **draws nothing**) + `ui/ChatAgentTabs.kt` (host side of the bar) + `ui/TabSessionCommands.kt` (restore/rename/fork/reopen); the bar itself is `resources/jcef/app-tabs*.js` | The bar is drawn by the WEB app, not by Swing |
| Change permission behaviour | `permission/PermissionBroker.kt`; hard rules in `permission/SensitiveGuard.kt` (policy + verdict) delegating to `ToolInputScanner` (input surface), `GuardPaths` (canonicalisation), `CredentialPaths`, `ForeignTerritory`, `CommandRules` | `SensitiveGuard` runs **before** any auto-approval. A new rule is a file, not a branch |
| Change what a diff shows / writes | `diff/DiffPresenter.kt`, `diff/HunkSelection.kt`, `session/DiffLifecycleManager.kt`, `ui/ChatEditReview.kt` (card diff + restore), `ui/DiffHistoryPanel.kt` (the tab listing past edits) | The **binary** writes the file; the IDE only reviews and refreshes VFS |
| Add a setting | `settings/ClaudeSettings.kt` (a field on `State`) + the owning `ui/Settings<X>Section.kt` (7 sections behind the 97-line `ui/ClaudeSettingsConfigurable.kt`, all implementing `ui/SettingsSection.kt`) | Persisted as the `@Serializable State` document in the **PasswordSafe** (`settings/SettingsStore.kt`); mutate through `update {}` or it does not survive a restart. `applyTo(session)` seeds launch options |
| Change launch flags / the system prompt | `session/SessionLauncher.kt` (`buildArgs`), `process/PluginContextPrompt.kt` (`--append-system-prompt`), `settings/SettingsLaunchEnv.kt` (process env) | Immutable `LaunchOptions` snapshot; `--print` is mandatory |
| Touch auth / credentials | `session/AuthGate.kt` (who this session runs as), `process/CredentialsVault.kt`, `process/AuthCli.kt`, `process/AccountProfile.kt`, `process/ConsoleApiKey.kt`, `session/LoginCoordinator.kt`, `settings/SecretStore.kt` | Credentials reach the binary **by env only**, never argv, never logs |
| Read a past session | `session/SessionStore.kt` (paths, traversal guard) + `session/SessionTranscriptReader.kt` (JSONL → entries) | The binary's files are the source of truth; the plugin persists no transcripts |
| Touch the agent tree / background tasks | `session/AgentRegistry.kt` + `AgentMeta`/`AgentScanner`/`AgentEnding`/`PluginAgentIndex`; tasks in `BackgroundTaskRegistry`/`BackgroundTaskReplay`/`TaskOutputFile`/`LiveOutputTail` | The **bare** agent id is the identity — `agent-<id>.jsonl` is a filename, not an id |
| Touch Git | `git/` (`GitGateway` is the only file naming a `git4idea` type **in code** — the others discuss it in comments, which the test skips) + `ui/GitContextActions.kt` | **Read-only by construction**, pinned by `GitReadOnlyContractTest` (import allowlist + forbidden-symbol scan). Optional dependency: `META-INF/claude-git.xml` + the optional `<depends>Git4Idea</depends>` |
| Change CI or the release | `.github/workflows/ci.yml`, `release.yml`; policy in `docs/RELEASE_PROCEDURE.md` | Merging to `main` publishes to Marketplace. Rulesets reference jobs by **display name** — a rename silently stops the gate |

## Structure

Structural counts, measured from disk this pass: **160** main Kotlin files, **10** uiTest sources, **30**
`app-*.js`, **7** `css/*.css`, **13** frontend test files. The test-source count is in motion this release
(~116 and rising), so treat it as shape.

- `src/main/kotlin/dev/lain/claudejb/`
  - `process/` — locating, launching and authenticating the `claude` binary. Landmarks:
    `ClaudeBinaryLocator`, `ClaudeProcess`, `BinaryInstall`, `AuthCli`, `CredentialsVault`.
  - `protocol/` — **no IDE dependencies.** `ClaudeEvent.kt` (the sealed event surface), `ProtocolParser.kt`
    (NDJSON → events, table-driven), `ProtocolJson.kt` (the one lenient `Json`), `ControlProtocol.kt`
    (outbound builders), plus one `*Models.kt` per event family (Conversation, Hook, Initialize, Notice,
    Permission, SessionSignal, Task, Usage). *(`Protocol.kt` was dissolved into these this release — it no
    longer exists.)*
  - `session/` — one `ClaudeSession` per chat tab plus ~30 single-responsibility collaborators grouped by
    job: turn plumbing, transcript narration, identity, the agent tree, background tasks and the
    session-history readers. The routing table above names the one you want; `ClaudeSession.onEvent` is
    the dispatch that reaches them.
  - `permission/` — the `can_use_tool` broker plus the deterministic sensitive-data lock, split by seam:
    `SensitiveGuard` (policy + verdict) over `ToolInputScanner`, `GuardPaths`, `CredentialPaths`,
    `ForeignTerritory`, `CommandRules`.
  - `diff/` — native diff presentation, hunk selection, edit snapshots, rollback, tab cleanup.
  - `git/` — read-only Git context (5 files, `GitGateway` the only one importing `git4idea`). No write path
    exists, and a test fails the build if one appears.
  - `ui/` — the tool window and its panels, the chat panel's collaborators, Settings as 7
    `Settings*Section.kt` behind the `SettingsSection` interface, and `ui/jcef/` = the host↔web bridge
    (`JcefHost`, `JcefBridge`, `JcefTheme`, `JcefStatus` + the `Jcef*` payload builders named above).
  - `context/` (attachments, clipboard via `ClipboardCli`/`ImageAttachments`, file picker), `actions/`,
    `settings/` (`SettingsStore` = the PasswordSafe document, `SecretStore`, `AlwaysAllowTools`,
    `SettingsLaunchEnv`/`SettingsExecutionTrust`/`SettingsSensitivePolicy`, plus the `Legacy*` migration off
    the old `.idea/claude-code.xml`), `util/`.
- `src/main/resources/jcef/` — the inlined web app: `shell.html`, 30 `app-*.js` in five families
  (`core` = bridge/markdown/diagram/theme · `transcript` = rows/tools/links/find · `composer` =
  menus/pills/attach/readout/palette/boot/auth · `session` = the dashboard's cards/mcp/workloads · `tabs` =
  guard/tree/pill/scroll), `css/*.css`, vendored `marked`/`purify`/`highlight`. Served under a
  hash-pinned CSP, as ONE document assembled by `JcefHost`.
- `src/main/resources/META-INF/` — `plugin.xml` plus two optional-dependency descriptors:
  `claude-terminal.xml` (Terminal) and `claude-git.xml` (Git4Idea, deliberately empty).
- `src/test/kotlin/` — unit + `headless/` (`BasePlatformTestCase`) + `integration/` (drives `bin/fake-claude`)
  + `drift/` (`ProtocolSurface`, `DriftDetector`, the `driftLive`-tagged `DriftLiveCheck`).
- `src/test/frontend/` — vitest + jsdom over the **real** `resources/jcef/*.js`.
- `src/uiTest/` — RemoteRobot, off by default (`-PuiTest.enabled=true`); `resources/sandbox-project/` is the
  fixture project it opens.
- `docs/` — release procedure and checklist, branching, binary compat, CI setup, drift, telemetry, UI
  testing, FAQ, troubleshooting and 3 ADRs. `docs/BACKLOG.md` is probed against the real binary, not guessed.
- `scripts/` — `css-usage.py` (unreachable CSS; reads the parts through `JcefHost.CSS_PARTS`, not the
  `app.css` that no longer exists), `probe-binary.sh`, `apply-rulesets.sh`, `bootstrap-ci.sh`,
  `gen-ci-signing-key.sh`, `drift-baseline.properties`, plus `split-css.py`, a **one-shot migration record**
  kept on purpose — do not re-run it. *(`drop-span.py` and `retarget-queries.py` were deleted on 08-13: they
  were throwaway helpers that edited source files wholesale, which hides the change from the diff the human
  reviews. Edits go through the editing tools, never a script.)*

## Entry points

- **Plugin**: `src/main/resources/META-INF/plugin.xml` → tool window `Claude Code` →
  `ui/ClaudeToolWindowFactory.kt` → `ui/ChatTabsPanel.kt` → one `ui/JcefChatPanel.kt` per chat (a 305-line
  assembler; everything it would grow into is a collaborator).
- **Session**: `session/ClaudeSession.start()` → `session/SessionLauncher.buildArgs` →
  `process/ClaudeProcess.kt` (stdio) → `protocol/ProtocolParser.kt` → `ClaudeSession.onEvent`.
- **Web app**: `ui/jcef/JcefHost.kt` assembles `resources/jcef/shell.html` + the CSS block + the scripts in
  `appNames` order and pins a CSP hash over each; the page boots `app-core.js` and registers `window.cc.*`.
- **Test stand-in for the binary**: `bin/fake-claude` (Python) with fixtures in `src/test/resources/fixtures/`.

## Commands

Every row marked **RUN 08-13** was executed on this machine at the 08-13 refresh, with the whole tree in
place. The rest cite what the claim rests on instead.

| What | Command | Evidence |
|---|---|---|
| Frontend tests | `npm test` (vitest + jsdom) | **RUN 08-13: 161 tests in 13 files, all passing.** CI job `Frontend tests` |
| Protocol drift | `./gradlew checkDrift -PclaudeBinary=/usr/bin/claude` | **RUN 08-13: PASS, no drift** at baseline `claude` 2.1.226 / SDK **0.3.231** (`scripts/drift-baseline.properties`). NB the task runs `npm update`, so it can move `package-lock.json`; reconcile the baseline afterwards. Task at `build.gradle.kts:170` |
| Build the plugin zip | `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew buildPlugin` → `build/distributions/` | **RUN 08-13: 2.5 MB zip, zero `node_modules` entries.** `intellijPlatform` 2.16.0. In CI it runs only as a dependency of `verifyPlugin` (job `Plugin verifier`) — the job *named* `Build plugin` builds nothing, it downloads that artifact and asserts over it |
| JVM tests + coverage gate | `./gradlew test koverVerify` | **RUN 08-13: 1 000 tests, 0 failures, 2 skipped; coverage gate passes.** `ci.yml` job `JVM tests` (there with `--no-daemon --stacktrace`) |
| Static analysis | `./gradlew detekt` · `./gradlew spotlessCheck` | **RUN 08-13: both clean, baseline untouched.** `ci.yml` job `Static analysis` (detekt 1.23.8, spotless 8.9.0) |
| Fix formatting | `./gradlew spotlessApply` | spotless plugin task (**not run here** — it rewrites the whole project; never run it while anything else is editing the tree) |
| Plugin verifier | `./gradlew verifyPlugin` | **RUN 08-13: Compatible on IU-253.33813.55, IU-261.27258.48, IU-262.9437.65, IU-262.9437.185, PY-262.9437.71.** One *experimental* API usage remains (`VcsChangesLazilyParsedDetails.getChanges()`), which is deliberately not a failure level — experimental means "may change", not "will be removed". `ci.yml` job `Plugin verifier` |
| Verify against local IDEs | `./gradlew verifyPlugin -PlocalIdePath=<dir>[,<dir>…]` | property read at `build.gradle.kts:391` (comma-separated) |
| Frontend lint/format | `npm run lint` · `npm run format:check` · `npm run format` | `package.json` scripts; CI job `Static analysis` |
| Production dependency audit | `npm audit --omit=dev --audit-level=low` | verbatim from `ci.yml` job `Dependency audit` |
| UI end-to-end | `./gradlew runIdeForUiTests` then `./gradlew uiTest -PuiTest.enabled=true` | `build.gradle.kts:265` (`runIdeForUiTests`) + `:238` (the `-PuiTest.enabled` gate) |
| Unreachable CSS rules | `python3 scripts/css-usage.py` | file exists; see `docs/`/`CLAUDE.md` |
| Sandbox IDE | `./gradlew runIde` | platform-plugin task, **not run here** |

On this machine only: `node` needs `OPENSSL_CONF=/dev/null`, and `claude` is a system install at
`/usr/bin/claude` (the drift task defaults to `~/.local/bin/claude`).

## Conventions and invariants

- **Where new behaviour goes**: a `ClaudeSession` collaborator, a `ui/` collaborator, a JS module or a JSON
  builder — never back into `ClaudeSession` or `JcefChatPanel`, which are an orchestrator and an assembler.
  The 5.5.0 splits (permission rules, protocol models, Settings sections, chat-panel collaborators) all follow
  the same shape: **one file per seam, registered in a table, not appended as a branch.**
- Threading: I/O and parsing off-EDT; every UI mutation on the EDT via the session's `edt {}` dispatcher.
- `protocol/` stays free of IDE classes so it unit-tests on a plain JVM. Same for `ui/jcef/JcefBridge.kt`.
- `git/`: only `GitGateway` may name a `git4idea` type in code, and the package has no write path.
- Frontend: no bundler, no CDN; a new CSS class used from JS needs a real rule or `css-contract.test.js`
  fails. The stylesheet is 7 files concatenated **in cascade order** (`JcefHost.CSS_PARTS`) and the scripts
  are 30 files concatenated in **dependency order** (`JcefHost.appNames`) — both lists are semantics, not
  tidiness, so a new part goes where it belongs, never at the end. `src/test/frontend/helpers/load.js` parses
  both lists straight out of `JcefHost.kt`, so the harness cannot drift from the served page.
- **One state vocabulary** for everything the page colours: `running` · `completed` · `failed` · `stopped`,
  decided in `ui/jcef/JcefStatus.kt`. The page paints the word the host sends; it never derives one.
- Tests live under the mirrored package path; headless and integration tests must run inside the `test`
  task (the platform runtime is only wired there). `checkDrift` is a separate `Test` task and is explicitly
  excluded from Kover (`build.gradle.kts:504`).

## Minefields

- `session/ClaudeSession.kt` (**2 507 lines**, most-churned source file by a factor of ~1.6 over the next) —
  property/`init` **declaration order matters**: `InitOrderContractTest` scans the sources (a 4-space-indented
  `init` in a class body, then any property declared below it) because the compiler only catches the direct
  case. The same applies to `ui/JcefChatPanel.kt`, whose collaborators are declared above `init`
  *and in dependency order among themselves*.
- `permission/ToolInputScanner.pathCandidates` walks **every string leaf** of a tool input as a path
  candidate, and `permission/ForeignTerritory` hard-DENIES a hit **regardless of caller trust**. Two live
  false positives already came from that pair. **A third is live now**: `ForeignTerritory.isUnc` accepts any
  `//<non-blank, no-whitespace>` as a UNC host, so an ordinary glob or regex containing that inside a shell
  command is read as a network mount and refused with no override. Change these two files with tests first.
- `ui/jcef/JcefHost.kt` — the CSP is hash-pinned over the exact bytes of each inline script AND of the
  concatenated stylesheet. Editing `shell.html`'s inline blocks changes the hash; anything that would need
  `unsafe-inline` is a no, and so is loading an asset by URL (the scheme handler serves ONE document).
  Adding a JS file means adding it to `appNames` **in the right position** — omitted, it is silently not served.
- `resources/jcef/shell.html` — `#boot` and `#auth-card` are children of `#conversation`, which sits inside
  the `#work` wrapper alongside `#dock` and `#palette`. That nesting is the point: the waiting screens are
  transcript rows, not overlays. Hoisting either back up to `#app` re-covers the chat tabs while a chat starts.
- `.github/workflows/release.yml` — a push to `main` publishes; a tag matching `v[0-9]+.[0-9]+.[0-9]+` is the
  manual escape hatch. `guard` first asserts the commit is reachable from `main` (**fails** otherwise), then
  resolves the version from `build.gradle.kts` (single source of truth) and sets `release=false` with a
  `::notice::` — **not** a failure — when that tag already exists, so a docs merge to `main` is not a red run.
  Published tags are immutable.
- `session/SessionTranscriptReader.kt` — restoring a transcript from the binary's JSONL. Synthetic `user`
  lines (`<task-notification>`, caveats) are not the user speaking; `isMeta`/`isSidechain` are on the wire.
  It is also the **only** JSONL parser: `AgentRegistry` reuses it deliberately (two parsers is how 4.0.4's
  duplicated-thinking bug happened).
- `bin/fake-claude` + `src/test/resources/fixtures/` — the integration tests' contract. A fixture edited
  without its test is a green suite that proves nothing.
- `scripts/split-css.py` — **one-shot, already applied.** Kept as the record of a mechanical migration;
  re-running it now would corrupt the tree.
- **`Asks.WORKSPACE_DIFF` and `Asks.PLAN` are declared but NOT REACHABLE.** `session/ControlAsks.kt` models
  both replies and `session/SessionQueries.kt` exposes `requestWorkspaceDiff`/`requestPlan`, and **nothing
  calls either** — the UI side was never built. This is the repository's signature defect (see the `/login`
  terminal lookups, the `git/` package and the onboarding card's "Check again" button, all three shipped or
  nearly shipped implemented-and-unreachable). Either wire them or do not claim them as features.

## Out of the map

`node_modules/` (the SDK there is **protocol reference only**, never shipped), `build/`, `.gradle/`,
`.idea/`, `build/distributions/*.zip`. `HANDOFF-5.5.0.md` at the root is an untracked working note, not part
of the shipped documentation set.
