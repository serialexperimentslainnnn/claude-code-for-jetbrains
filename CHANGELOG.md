# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.2.1] — 2026-06-04

### Added
- **API provider selector (Anthropic / DeepSeek)** — a new `Provider:` setting (Settings ▸ Claude Code) and a composer chip pick the endpoint the `claude` binary talks to. **Anthropic** uses the binary's own native login (subscription/OAuth). **DeepSeek** routes to its Anthropic-compatible endpoint (`https://api.deepseek.com/anthropic`) and **requires its own issued key**. Each provider keeps an **isolated** API key in the IDE **password safe** (not in `claude-code.xml`), shown with its **brand logo** on the chip and in the menu. (`settings/Provider.kt`, `settings/ClaudeSettings.kt`, `ui/ClaudeSettingsConfigurable.kt`, `ui/OptionMenus.kt`, `ui/ChatPanel.kt`, `ui/ChatTheme.kt`, `resources/icons/provider-*.svg`.)

### Fixed
- **Reasoning toggle now persists across turns** — new "Thought process" blocks correctly inherit the Ctrl+O toggle state instead of always appearing expanded. Previously toggling reasoning off hid existing blocks but every new turn's reasoning popped open again. (`ui/TranscriptView.kt`)

### Security
- **Credentials are pinned to their provider — no Anthropic credential ever leaks to a third party.** Switching provider sets `ANTHROPIC_BASE_URL` **and** `ANTHROPIC_API_KEY` as an **atomic pair**, and ONLY when a key is present (never a lone base URL, which would make the SDK ship your Anthropic OAuth bearer to the other endpoint). Because `ANTHROPIC_API_KEY` is set, the binary's SDK does not even load the stored OAuth `credentials.json`, so the subscription can't be sent elsewhere. We never emit `ANTHROPIC_AUTH_TOKEN`. The settings form rejects an Anthropic-shaped key (`sk-ant-…`) for a third-party provider; selecting a third-party provider with no stored key **does not switch or restart** — it prompts to configure the key first. **`/login` is restricted to the Anthropic provider** (a third-party auth failure is a wrong key, not a missing OAuth login). The pure `Provider.launchEnv` rules are unit-tested. (`settings/Provider.kt`, `session/ClaudeSession.kt`.)

## [3.2.0] — 2026-06-04

### Added
- **Two-row, adaptive options bar** — the composer controls are split into row 1 (model · mode · effort · thinking pills, centred) and row 2 (the toggles + attach, centred, with the Play/Stop button right-aligned). Each pill is a flat **capsule** with its own category glyph and a coral hover glow; the value label is now just the live value (full name in the tooltip). The Send control is a thin **neon outline** glyph (triangle/stop-square) stroked in the accent. (`ui/ChatPanel.kt`, `ui/ChatTheme.kt`, `resources/icons/chip-*.svg`.)
- **Coral focus ring + editor-font prompt** — the composer card lights its border coral (with a soft halo) while the prompt is focused, and the prompt now uses the IDE **editor font** (typically a mono) at the UI-scaled size, for a code-native feel. (`ui/ChatPanel.kt`, `ui/ChatTheme.kt`.)
- **Output follow toggle** — a follow button (coral while active, on by default) force-follows the streaming bottom even if you scroll up; off, the transcript still follows naturally while you're parked at the bottom so you can read history mid-stream. (`ui/ChatPanel.kt`, `ui/TranscriptView.kt`.)
- **🌈 Vibe Coder Mode** — an opt-in gag toggle that animates the coral accent through the rainbow: the send glyph, option pills, every bordered box, the tool/chip/attach **icons** (retinted live), the prompt's vibe ring, and the avatar (a **Nyan Cat**, with the tool-window stripe icon swapped to match). Off by default; one timer, stopped on dispose. (`ui/ChatPanel.kt`, `ui/ChatTheme.kt`, `resources/icons/chip-follow*.svg`, `resources/icons/claude-vibe.svg`.)
- **Composer paste fixed (Ctrl/Cmd+V)** — pasting an image into the composer now works, including the Linux case (Wayland over XWayland / X11) where a clipboard image arrives as a raw `image/…` flavor (`InputStream`/`byte[]`) rather than `DataFlavor.imageFlavor`. The keyboard paste is bound through the **IDE action system** (a `DumbAwareAction` on the platform `$Paste` shortcut via `registerCustomShortcutSet`, so it honours the user's keymap and is correct on every OS), and clipboard access goes through the cross-platform `CopyPasteManager`. A latent bug where the drag&drop `TransferHandler` captured a null delegate (breaking text paste/drop) is also fixed. New pure, unit-tested `context/ClipboardImageReader` centralizes raw-image extraction; the rendered-`java.awt.Image` fallback stays in the composer. (`ui/ChatPanel.kt`, `context/ClipboardImageReader.kt`.)
- **Richer attachment menu** — the attach menu now offers, besides current file / selection / clipboard image: **Add files…** and **Add directory…** (native `FileChooser`), plus **Add open files…** and **Add recent files…** submenus (from `FileEditorManager`/`EditorHistoryManager`). File chips use a **root-relative** label so same-named files in different folders no longer collide on dedupe. New `context/FilePickerHelper` (the pure `displayName` is unit-tested). (`ui/ChatPanel.kt`, `context/FilePickerHelper.kt`.)
- **Native visual identity** — a custom 16×16 SVG icon set replaces the borrowed `AllIcons` on tool-call rows (bash/read/edit/search/web/task/generic), the attach button, and attachment chips; file attachments show their **real file-type icon**. Attachment chips gain hover highlight, hand cursor, a custom ✕, and **click-to-open** in the editor (project-confined). (`resources/icons/*.svg`, `ui/ChatTheme.kt`, `ui/ChatMessageViews.kt`, `ui/AttachmentStripPanel.kt`, `ui/ChatPanel.kt`.)
- **Diff History tab + rollback** — a toolbar action opens a **Diff History** tab listing every reviewable Edit/Write/MultiEdit in the session (root-relative path, tool, native `+a/-b` summary) with **View diff** and per-edit **Revert**, plus a header **Roll back all changes**. Revert is IDE-side and **path-confined** (a `WriteCommandAction` restores the captured pre-write `beforeText` via VFS only when inside the project root), refreshes the VFS, and **reseeds the binary's read-state** (`seed_read_state`) so its next Edit re-validates against the rolled-back contents. A **Revert** button also appears on each reviewable transcript row beside *View diff*. The enumeration + rollback live in a new `session/RollbackManager` collaborator and a pure `diff/FileRollback` (ordering helpers unit-tested) — `ClaudeSession` stays a thin delegating orchestrator. (`ui/DiffHistoryPanel.kt`, `ui/ClaudeToolWindowFactory.kt`, `session/RollbackManager.kt`, `diff/FileRollback.kt`, `ui/ChatMessageViews.kt`, `ui/TranscriptView.kt`, `ui/ChatPanel.kt`.)

### Changed
- **Minimum IDE is now 2025.1 (build 251)** — `since-build` moves up from 243. The composer attach menu uses the fluent `FileChooserDescriptorFactory.multiFiles()` / `singleDir()` descriptors introduced in 2025.1; they don't exist on 2024.3, where the old build would `NoSuchMethodError`. 2024.3 users stay on the last compatible release. (`build.gradle.kts`.)
- **Attachment mentions are cwd-relative on the wire** — a file attachment is sent to the binary as an `@<cwd-relative>` mention it actually expands (an absolute `@/…` path wasn't recognized), while the user bubble shows a **clickable `jb://open` link** to the file (wire text and display text are now built separately). (`session/ClaudeSession.kt`.)
- **More file references become links** — the markdown linkifier now links bare file paths **without** a line number too: permissive inside code spans (a `src/Foo.kt` in backticks links at line 1), conservative in prose (only an obvious path with a `/`, or an explicit `path:line`, so a product name like "Node.js" isn't turned into a dead link). (`ui/MarkdownRenderer.kt`.)
- **Compact attachment chips** — smaller chips with a small self-painted ✕ (replacing the chunky stock close button) and a down-scaled file-type glyph. (`ui/AttachmentStripPanel.kt`.)
- **Settings page no longer sprawls** — the page is pinned to a fixed content width on the left and its HTML security notes wrap, so on a wide (2K+) monitor the form and the tool-checkbox grids stop stretching edge-to-edge. (`ui/ClaudeSettingsConfigurable.kt`.)
- **Native `/login` — no IDE terminal** — signing in no longer drops you into a terminal tab (which broke once the **Reworked terminal** became the default engine in 2025.2: the legacy `createShellWidget` factory creates a deprecated *Classic* tab whose command-send races shell startup, so `claude auth login` was dropped). `/login` now spawns `claude auth login` under a real PTY (**pty4j**, bundled in the platform), lets the binary drive its own OAuth flow, opens the authorize URL in the IDE browser, collects the code from the callback page via a native input dialog, writes it back to the PTY, and restarts the session on success. The pure output parser (URL / "paste code" prompt / result extraction, layout-agnostic to the Ink TUI's cursor positioning) is unit-tested. (`process/ClaudeLoginFlow.kt`, `process/LoginOutputParser.kt`, `session/ClaudeSession.kt`, `ui/ChatPanel.kt`.)

### Fixed
- **IDE terminal launch on 2025.2+ (Reworked terminal default)** — the terminal helper, now only the fallback for the native login flow above, drives the **Reworked Terminal API** (`TerminalToolWindowTabsManager` + `TerminalView…shouldExecute().send()`, available 2025.3+) on modern IDEs and only falls back to the deprecated Classic `createShellWidget` path below 253 — all reflectively, so the verifier sees no deprecated/experimental API. (`process/TerminalLauncher.kt`.)
- **Rollback of a file-creating Write deletes the file** instead of leaving a 0-byte husk — the snapshot now records whether the file existed before, so reverting a creation removes it while reverting an overwrite restores the prior contents. Revert (per-row and Roll-back-all) also surfaces a success/failure **notification** so a click is never a silent no-op. (`diff/EditSnapshotStore.kt`, `diff/FileRollback.kt`, `session/ClaudeSession.kt`.)
- **Ctrl+O no longer jumps the scroll** — collapsing the reasoning blocks used to leave the viewport pointing at shifted content; the view now re-pins after the relayout (and `scrollToBottom` validates the layout before reading the extent). (`ui/TranscriptView.kt`.)
- The in-card **Diff/Revert action buttons** on tool rows get a comfortable padded hit area + hover highlight and are spaced apart, so they're no longer tiny adjacent targets. (`ui/ChatMessageViews.kt`.)

## [3.0.1] — 2026-06-03

### Added
- **Log in from the IDE**: `/login` (and any auth-failure result / `auth_status` error) can't run inside the TTY-less stream-json session — the binary answers *"not available on this environment"*. The plugin now detects this and offers a **"Log in in terminal"** notification that opens a native IDE terminal running `claude auth login`, **always launched with the binary's absolute path** so a GUI IDE that didn't inherit the user's login `$PATH` still finds it. Typing `/login` in the composer is intercepted client-side and routed to the same flow, and `/login` is now listed in the command palette (the binary never advertised it over stream-json). On **Windows** the command is prefixed with PowerShell's call operator (`& "…\claude.exe" auth login`) so the quoted path executes instead of being echoed. Uses the bundled Terminal plugin (runtime access via an optional `<depends>`), guarded so a disabled Terminal plugin degrades to a notice carrying the exact command. (`process/TerminalLauncher.kt`, `session/LoginDetection.kt`, `session/ClaudeSession.kt`, `ui/ChatPanel.kt`, `ui/CommandPalette.kt`.)

### Performance (no behaviour change)
- **Streaming delta coalescing**: buffer consecutive assistant/thinking deltas (and the live token usage) on the reader thread and flush them to the EDT in a single `invokeLater` per batch — flushed before every non-delta event / boundary / finalize / result / stop, so ordering is preserved and no delta is lost. Drastically fewer EDT hops/repaints during streaming. (`session/ClaudeSession.kt`; the shared buffer is lock-guarded so an EDT-side restart can't race the reader thread.)
- **Per-row transcript render**: `TranscriptView.flushDirty` revalidates/repaints only the changed rows and falls back to a full layout only when a row's preferred height actually changes (`ui/TranscriptView.kt`).
- **Markdown memoisation + highlighter cache**: skip re-render when the text is unchanged; cache the `SyntaxHighlighter` per language; don't highlight unterminated code fences (`ui/ChatMessageViews.kt`).
- **O(1) tool-output anchoring**: use the existing `byToolUseId` map instead of an `indexOfLast` predicate scan (`session/TranscriptModel.kt`).
- **Single shared pulse timer** for all tool boxes instead of one `Timer` per box (`ui/ChatMessageViews.kt`/`ui/TranscriptView.kt`).
- **Session-scoped quota poll**: one poll per session instead of one per open tab (`session/ClaudeSession.kt`/`ui/ChatPanel.kt`).
- **Lazy transcript restore**: reconstruct the last N entries (`DEFAULT_RESTORE_CAP`) on restore/fork/open-previous; full context still resumed by the binary (`session/SessionTranscriptReader.kt`).
- **O(n) stdout line splitter**: read-offset scan + single compaction instead of per-line `delete` (`process/ClaudeProcess.kt`).

### Fixed (post-review hardening)
- **Unbounded stdout buffer**: cap a newline-free stream at 16 MiB (drop + warn) so a malformed/stuck binary stream can't grow memory without bound (`process/ClaudeProcess.kt`).
- **Streaming auto-follow**: when pinned to the bottom, always re-pin after a flush instead of only when a row's height changed — a stale `preferredSize` no longer stops the transcript from following the stream or clips the newest line (`ui/TranscriptView.kt`).
- **Transcript restore cap**: drop orphan tool-result rows anywhere in the kept window (not just leading ones), so a restored session never shows a result without its call (`session/SessionTranscriptReader.kt`).
- **Usage meter**: a quota poll while the process is stopped no longer overwrites the last good cost/context with null (`session/ClaudeSession.kt`).
- **Delta drain on teardown**: `flushDeltas` applies synchronously when already on the EDT (stop/dispose), so final streamed text isn't lost to an unrun `invokeLater` (`session/ClaudeSession.kt`).
- **Markdown memo**: bust the cached HTML on IDE theme change so code-block colours follow a LAF switch (`ui/ChatMessageViews.kt`).
- Defensive handling + tests for duplicate `tool_use_id`; removed a redundant render pair in the composer.
- **Toggling thinking on/off no longer kills the session**: switching extended thinking (or the model) restarts the process via `--resume`, and the *old* process's late `onTerminated` could arrive after the new one was up and tear it down — Claude Code "disappeared" / didn't come back. A per-launch generation counter now ignores stale termination callbacks, so the restart resumes cleanly (`session/ClaudeSession.kt`).
- **Diff shows in every permission mode**: the pre-write snapshot is now captured on the `tool_use` event (before the binary writes), not only at `can_use_tool` approval — so the inline diff + "View diff" appear in acceptEdits / bypass / auto / dont-ask too, where the binary auto-executes without asking. First-capture-wins so a later re-capture can't overwrite the before-text (`session/ClaudeSession.kt`, `diff/EditSnapshotStore.kt`).
- **`/login` no longer dead-ends**: in the stream-json session the binary has no TTY, so `/login` answered *"not available on this environment"* and the user was stuck. It's now intercepted and routed to an interactive IDE terminal (see *Added*), and a detected auth failure surfaces the same actionable prompt instead of just an error line.

### Notes
- A performance pass plus a post-review hardening pass (thread-safety, behaviour-equivalence, security), and one new user-facing capability (IDE-terminal login). Tests added for the tool index, stream-event parsing, transcript cap (incl. mid-window orphans), delta coalescing, duplicate tool ids, the login detector, and the terminal login command. 503 tests, 0 failures.

## [3.0.0] — 2026-06-03

### Added
- **Full SDK protocol surface**: all `system/*` and stream events parsed (E1) and every host→binary control request wired (E2) — `get_settings`, `get_binary_version`, `mcp_reconnect`/`mcp_toggle`, `stop_task`, `rename_session`, and more.
- **Graphical session consumption** (E7): `SessionUsagePanel` paints context window + honest session-output tokens + a unified quota bar (utilization %, reset countdown **and** absolute reset hour), replacing the old loose quota labels and the inline token suffix.
- **Rich IDE attachments** (E8): `Attachment`/`AttachmentEncoder`/`AttachmentStripPanel`/`AttachmentActions` — pin files/selections as chips (editor actions + 📎 button) and drag&drop/paste images as native base64 content blocks via `ControlProtocol.userMessageWithImages`; size-guarded and read off the EDT.
- **Subagent live strip** (E10): `SubagentTasksPanel` — one card per in-flight Task subagent (tokens/tool-uses/elapsed + Stop), with status/error surfaced; `TaskTracker.onUpdated` merges status/error patches and clears on stop/restart.
- **Advanced launch options** (E6): max turns, max budget (USD), fallback model, `--add-dir` roots, `--betas`, strict MCP config — in Settings and threaded through `SessionLauncher`.
- **Plan mode + richer permissions** (E4): ExitPlanMode plan cards, decision reasons, blocked-path context.
- **Session management** (E5): rename / fork / delete past sessions (binary session files remain the source of truth; `SessionStore.delete` is UUID-guarded).
- **Native hooks** (E3): `hook_callback` answered host-side by the pure `HookBroker` (decision + IDE side effects); the real tool gate stays `can_use_tool`.
- **Account & diagnostics** (E11/E2-UI): Account, Binary Version, Effective Settings, and an interactive MCP-runtime dialog in the gear menu.
- **Diff button** on every Edit/Write/MultiEdit row + syntax-highlighted code (E9).
- **Tool-call lifecycle on the box**: each tool card reflects its state by border colour — **sky-blue while in flight**, **pulsing sky↔amber** for a sense of motion while it works, **green when finished**. The elapsed time is shown while running (the protocol carries no completion %, so time is surfaced instead of a fake progress bar).

### Changed
- **Architecture refactor**: `ClaudeSession` and `ChatPanel` decomposed into single-responsibility collaborators (TokenAccountant, TaskTracker, TranscriptReconciler, DiffLifecycleManager, SessionControlClient, PermissionCardManager, HookBroker, SessionLauncher; UI sub-panels) — the orchestrators are now thin, enabling parallel epic work.
- **Native UI pass**: the consumption readout is rebuilt from native components (`JProgressBar` + labels) and shows the token breakdown (in/cache/out) **inline**; the "thinking" indicator uses the IDE-native `AsyncProcessIcon`; the Settings page is wrapped in a scroll pane (responsive). The 📎 attach button opens a **selector menu** (current file / selection / clipboard image) instead of attaching the open file directly.
- **Authoritative usage**: session tokens come from the binary's cumulative `get_session_cost.apiUsage` (and context from `get_context_usage`) rather than a drifting local fold; quota shows only when the binary reports `utilization` (no misleading 0%), retaining the last known value.
- Composer model/mode chips clarified: model default → "Default · Opus 4.8 (recommended)"; permission-mode menus/combo show human labels ("Ask each time", "Accept edits", …). Shared `TokenFormat`.

### Fixed
- Final hardening pass: image read/encode moved off the EDT (with a pre-read size guard); absolute reset hour + non-colour warning marker restored (WCAG 1.4.1); subagent status/error updates wired; duplicate `formatTokens` divergence removed.
- Approving a plan (ExitPlanMode) now flips the plugin's permission mode back to default — the Mode chip no longer stays stuck on "plan".

### Notes
- **474 tests** in the default `test` task (0 failures, 2 Windows-only skips), plus the gated RemoteRobot UI suite (locators validated against a live IDE). Compatible with IU-261 and IU-262 (RC), zero deprecated/internal APIs.

## [2.2.2] — 2026-06-03

### Added
- **Headless component tests** (`src/test/.../headless/`, IntelliJ Platform `BasePlatformTestCase`, run in-process): `OpenedDiffsService`, `ChatSessionManager`, `SessionHistory` service round-trip, `ClaudeSettings` service (defaults + always-allow), `ClaudeSettingsConfigurable` (combo fallbacks + apply/reset/dispose), and real **token-accounting** verification (all four usage components fold into the session total across messages).
- **Integration tests** (`src/test/.../integration/`) driving a real `ClaudeSession` against `bin/fake-claude` with JSONL fixtures: init/streaming, thinking turn, token accounting, multi-message token fold, rate-limit, tool-use permission resolution, resume reconstruction, interrupt, and the "Write-unsafe context" regression path.
- **End-to-end UI tests** (`src/uiTest/`, RemoteRobot, gated by `-PuiTest.enabled=true`): chat smoke, View diff, Close All Diffs, jump-to-code, thinking toggle, keyboard shortcuts, Open Previous Session, Settings model combo, notifications — ready to run in the nightly UI workflow.
- **Release automation**: `.github/workflows/release.yml` (tag-triggered: full test + verifyPlugin gate, then sign + publish to Marketplace, plus a GitHub Release) and `.github/workflows/ui-tests.yml` (nightly RemoteRobot under Xvfb). `docs/BRANCHING.md` documents the GitFlow + branch-protection conventions.
- `ClaudeSession.handleEventForTest(event)` — a `@TestOnly` seam so headless tests can drive event reconciliation without spawning the binary.

### Notes
- Same runtime behaviour as 2.2.0/2.2.1 for end users; this release completes the automated test pyramid (unit → headless → integration → UI) and the maintenance/release workflow. Test count: **239** in the default `test` task (0 failures, 2 Windows-only skips), plus the gated UI suite.

## [2.2.1] — 2026-06-03

### Added
- **Maintenance baseline**: `SECURITY.md` (responsible disclosure policy + SLAs), `CONTRIBUTING.md` (dev workflow), `CODEOWNERS`, GitHub issue & PR templates, and Dependabot config for Gradle + the SDK reference (`@anthropic-ai/claude-agent-sdk`).
- **CI**: `.github/workflows/ci.yml` runs `./gradlew test verifyPlugin buildPlugin` on every push/PR with JDK 21 + Gradle cache and uploads the plugin zip as an artifact.
- **Drift detection**: `.github/workflows/sdk-drift.yml` (weekly) opens an issue when a newer SDK is published; `.github/workflows/binary-drift.yml` (daily) when a newer `claude` binary is released; `.github/workflows/binary-probe.yml` (weekly + manual) runs the real binary against canonical inputs and opens an issue if it emits an event type the plugin doesn't parse.
- **Documentation**: `docs/RELEASE_PROCEDURE.md`, `docs/RELEASE_CHECKLIST.md`, `docs/BINARY_COMPAT.md`, `docs/FAQ.md`, `docs/TROUBLESHOOTING.md`, `docs/TELEMETRY.md` — a real release/maintenance workflow for an in-Marketplace plugin.
- **Test pyramid foundations**: new Gradle source sets `integrationTest` and `uiTest` (`./gradlew integrationTest` runs against a deterministic `bin/fake-claude` Python stand-in fed JSONL fixtures from `src/integrationTest/resources/fixtures/`; `uiTest` reserved for the Sprint 3 RemoteRobot end-to-end suite, gated by `-PuiTest.enabled=true`).
- **Coverage**: `kotlinx-kover` integrated; `./gradlew koverHtmlReport` produces a coverage report.
- **Layer A unit tests** (67 new, total **202 / 0 fail / 2 skipped on non-Windows**): `DiffPresenter.isWithinRoot` direct (incl. symlink escape attempts), exhaustive `PermissionBroker` matrix (mode × tool × within-root × remembered), `ClaudeBinaryLocator` (incl. Windows `.cmd` shim regression resolved with `Assumptions.assumeTrue`), `McpConfigBuilder` (SSE / streamable-http / stdio + custom server merging + invalid JSON tolerance), `Protocol.parseAskQuestions`, and `MarkdownRenderer` edge combinations (table cells with code/links, unterminated fences, nested task lists, contiguous autolink + `path:line`).
- `bin/fake-claude` Python stand-in plus the `init_basic.jsonl` fixture: handles the initialize handshake, replays a streamed text turn with `message_start` / `content_block_delta` / `message_delta` / `result`, and emits per-message usage with all four token components so integration tests can pin token-accounting behaviour without hitting the real model.

### Changed
- README install path now points to the JetBrains Marketplace as the canonical source (GitHub remains the source of truth for code).

### Notes
- 2.2.1 has the same runtime behaviour as 2.2.0 — this release is the infrastructure update (tests + workflows + docs) so the plugin can be maintained seriously with real users on the Marketplace.

## [2.2.0] — 2026-05-28

### Added
- **Model picker reflects what the binary actually returns** — the Settings combo now lists the binary's modern aliases (`default` = Opus 4.8 with 1M context, `sonnet` = Sonnet 4.6, `haiku` = Haiku 4.5) and updates **live** as soon as the `initialize` handshake lands, showing each as its `displayName` ("Default (recommended)", "Sonnet", "Haiku") instead of the raw wire value. The historical Opus 4.7/4.5/Opusplan tags stay as fallback for back-compat.
- **Path:line links inside code spans** — `` `src/Foo.kt:42` `` (the natural way the model writes references) now renders as a clickable `jb://open` link wrapped in `<code>` instead of inert monospace text. Project-confinement (`DiffPresenter.isWithinRoot`) still gates the click.
- Protocol surface bumped to SDK 0.3.161 / binary 2.1.161: `ModelInfo` carries `supportsEffort` / `supportedEffortLevels` / `supportsAdaptiveThinking` / `supportsFastMode` / `supportsAutoMode`; `AccountInfo` carries `apiProvider` / `apiKeySource`. Extra `system/*` events from the new binary (`task_progress`, `task_notification`, `background_task_*`, `auth_status`, `session_state_changed`) are tolerated by the lenient codec — UI surfacing to come.
- Tests: `MarkdownRenderer` linkify-inside-code-span (134 total).

### Changed
- **Default model is now `default`** (the binary's recommended-tier alias), not the hard-coded `claude-opus-4-7`. Fresh installs follow the binary's recommendation (currently Opus 4.8); existing settings keep their persisted model value untouched.

### Fixed
- **Marketplace publishing**: migrated the bundled MCP plugin lookup from the internal `PluginManager.getInstance().findEnabledPlugin(PluginId)` to the public static `PluginManager.getPlugin(PluginId)?.takeIf { it.isEnabled }`. This was the lone internal-API hit that blocked the 2.0.1 upload re-check; verified with `javap` against the platform jars.

## [2.1.0] — 2026-05-27

### Added
- **Persistent diff from the transcript** — Edit/Write/MultiEdit tool cards carry a "View diff" button that re-opens the old↔new diff at any time, in any permission mode. A new `EditSnapshotStore` captures the pre-write file contents at approval time, keyed by `tool_use_id`.
- **Hunk-by-hunk acceptance** — the permission card lists the change's hunks (via the platform diff `ComparisonManager`) with checkboxes; accepting a subset sends a narrowed `updatedInput` so the binary writes only the selected hunks. `file_path` is never modified.
- **AskUserQuestion options wrap** — labels, descriptions and the per-option `preview` (previously unused) render in full instead of clipping to one line.
- **"Explain with Claude"** editor-popup action sends the current selection (with file path) to the active session.
- **Jump-to-code** — `path:line` references in replies become `jb://open` links that navigate to the file/line in the IDE.
- **"Always allow" per tool** — persisted in `ClaudeSettings`; remembered tools auto-approve while reviewable writes stay confined to the project root. Settings ▸ Claude Code now lists the remembered tools with a Remove action, so the rule can be revoked without editing XML.
- **Session attention notifications + tab badge** — a background session with a pending permission, a finished turn, or an error raises a notification and badges its tab; suppressed when that tab is the one on screen.
- **Session history (reads the binary's own files)** — past conversations are read back from the `claude` binary's session transcripts (`~/.claude/projects/.../<sessionId>.jsonl`), the single source of truth. "Open Previous Session…" lists the project's sessions by their real title (as `--resume` shows them) and re-attaches via `--resume`. The plugin persists **no transcripts** — only the open-tab session ids, in `workspace.xml` (not committed by convention).
- **Restore on startup** — the tabs you had open are reopened automatically; if none were recorded, the most recent session is restored. Toggle: Settings ▸ Claude Code ▸ "Restore open chats on startup".
- Markdown rendering: strikethrough (`~~`), GFM task-list checkboxes, nested lists.
- Tests: `EditSnapshotStore`, `PermissionBroker` tool_use_id plumbing, `HunkSelection`, `MarkdownRenderer`, `SessionHistory` open-tab ids, `SessionStore` path-traversal guard + cwd encoding, `SessionTitleReader`/`SessionTranscriptReader` JSONL parsing, and the settings enums (132 total).

### Changed
- Permission mode, effort and MCP transport are now backed by typed enums (`PermissionMode`/`EffortLevel`/`McpTransport`) as the single source of truth for allowed values and branching; the persisted/wire strings are unchanged (no config migration).

### Security
- Jump-to-code navigation is confined to the project root (`DiffPresenter.isWithinRoot`): a crafted `jb://open` link cannot open absolute paths, `~/.ssh`, `/etc`, or `..`-traversed files.
- Explicit Markdown links are restricted to an allow-list of schemes (`http`/`https`/`jb`) with the href quote-escaped; other schemes (`javascript:`, `file:`, `data:`, relative) render as plain text.
- No conversation content is written to project files anymore: session history keeps only open-tab ids in `workspace.xml`. Session-file reads are confined to `~/.claude/projects` and gated by a UUID-shaped id check (`SessionStore`), so a crafted session id can't traverse out of the tree.

### Fixed
- Markdown: a bare URL inside an explicit link's href is no longer double-linkified (`<a href="<a href=…">`).
- Notifications no longer pop for the chat already on screen (the over-strict tool-window `isActive` check is gone; visible+selected tab is enough to suppress), and the notification's **Open** action now dismisses it.
- Fixed a "Write-unsafe context!" crash when refreshing files the agent edited: the VFS refresh is now asynchronous (`refreshIoFiles`), which is safe from the non-write-safe modality it runs under.
- **Extended thinking shows again** on current models (Opus 4.7 / `claude` 2.1.152+): reasoning is now enabled via the launch flags `--thinking adaptive --thinking-display summarized` instead of the deprecated `set_max_thinking_tokens` control, which no longer surfaces "Thought process" blocks. Thinking is now on/off (adaptive — the model decides depth); toggling the chip restarts the session via `--resume`.

## [2.0.1] — 2026-05-27

### Changed
- Extended the supported IDE range to the current EAP (`until-build` = `262.*`); verified Compatible against IU-262.
- Replaced the internal `PluginManagerCore` lookup for the bundled MCP Server plugin with the public `PluginManager` by-id API, removing the last internal-API usage.

## [2.0.0] — 2026-05-26

### Security
- Auto-approved file writes in `acceptEdits` / `bypassPermissions` are confined to the project root: a write whose canonical path (symlinks resolved) falls outside the project degrades to a manual Accept/Reject card instead of being written silently.
- Trust-on-open gate: when a project-level `claude-code.xml` carries a source script or a custom stdio MCP server — both of which execute code at launch — the plugin prompts for confirmation once before running them (declining aborts the launch).
- The source script is invoked with its path as a positional shell argument instead of being interpolated into the command string, removing a shell-injection vector via a crafted path.
- Settings now warn that environment variables are stored in plain text in `claude-code.xml` and that the source script is executed on session start.

### Fixed
- EDT freeze on session start: environment resolution (sources a login shell, multi-second timeout) and process spawn now run on a pooled thread; the resolved environment is cached per session. Opening the first chat or sending the first prompt no longer hangs the IDE.
- In-flight control requests are now completed (with failure) on `stop()` / process termination / dispose, fixing dialogs stuck on "Loading…" and leaked callbacks.
- Control requests now have a 30s watchdog; a hung binary no longer leaves the callback pending indefinitely.
- Process start failures are surfaced via notification (not just the transcript) and no longer leave a half-initialized "ready" session; `writeLine` logs (and reports) lines dropped to a dead stdin instead of discarding them silently.

### Added
- First unit-test suite (80 tests): `ProtocolParser`, `ControlProtocol`, `DiffPresenter` reconstruction, `TranscriptModel` hierarchy, `RateLimitInfo` math, and environment parsing (`EnvScriptLoader.parse`, `ClaudeSettings.parseEnv`).

### Changed
- MCP config building extracted to a standalone, testable `McpConfigBuilder` (identical wire output).
- Thread-safe tab counter (`AtomicInteger`); named constants for UI timings/quota thresholds; debug logging on previously silent decode/parse failures.

## [1.3.5] — 2026-05-26

### Added
- **IDE tools over MCP (opt-in).** Two independent controls in Settings ▸ Claude Code:
  - *Enable JetBrains MCP server* — wires JetBrains' own MCP Server plugin via `--mcp-config`. Pick the transport (`sse`, `streamable-http`, or `stdio`) and port; for `sse`/`streamable-http` the default localhost endpoint is synthesized (no JSON to type), and `stdio` is built automatically from the running IDE's paths (JBR `java` + the bundled `mcpserver` libs), so it works on Windows unchanged.
  - *Custom MCP servers* — add any number of your own servers as a JSON object (`name → server config`), merged alongside the JetBrains one.
- Off by default; tool calls remain gated by the in-chat permission prompt. Invalid custom JSON is rejected on save.

## [1.3.1] — 2026-05-26

### Fixed
- Settings: the model dropdown was empty when opened before the binary's initialize handshake — it now always lists the available models plus known fallbacks (shared with the gear menu).
- Settings: removed the blank entry in the Effort dropdown.

### Changed
- Default model is now **Opus 4.7** (`claude-opus-4-7`).
- Default effort is now **medium**.

## [1.3.0] — 2026-05-26

### Added
- Windows support: the `claude` binary is detected on Windows (`claude.exe` / `claude.cmd`) across npm, scoop, volta, chocolatey and `~\.local\bin`. npm `.cmd` shims are driven as `node cli.js` directly, bypassing cmd.exe (which corrupted the streaming stdio pipe and mangled argument quoting).
- Settings: explicit overrides for the `claude` and `node` executable paths — the catch-all for non-standard installs, version managers, or a GUI IDE that doesn't inherit the user's PATH.
- Settings: configurable environment variables (`KEY=VALUE` per line), injected into the binary's process — useful on Windows for `PATH` additions.
- Settings: **Source script** — point to a `.sh` (sourced in the login shell on Linux/macOS) or a PowerShell profile/`.ps1` (dot-sourced on Windows); the resulting environment is captured and applied to the `claude` process, so the IDE inherits the same `PATH`/setup as the user's own shell.
- "Binary not found" notification now carries a **Configure paths…** action that opens the settings page directly.

### Changed
- Auto-detected `claude` path is persisted to settings on first successful launch (and refreshed if a saved path goes stale), so launches are stable and the path is visible/editable.

## [1.2.0] — 2026-05-26

### Added
- Tool output is now shown in the chat as a code block immediately below the tool call card. Outputs longer than 200 lines are truncated with an indicator. Supports all tools (Bash, Read, Edit, Grep, Glob, WebFetch, etc.)
- Tool calls are now collapsible groups: a disclosure triangle on each tool card shows/hides its output. Applies to every tool that produces output.
- Subagent (`Task`/Agent) activity nests under its Agent: the subagent's tool calls, outputs and text are anchored and indented beneath the Agent card, and collapse hierarchically (collapsing the Agent hides its whole subtree; collapsing a sub-tool hides only its output).

### Changed
- Info bar above the composer reordered: (1) Resets in countdown, (2) Reset Hour, (3) Session Usage %, (4) Brewing / live tokens / Esc to interrupt

### Fixed
- Tool outputs now anchor directly under their tool call instead of drifting to the end of the transcript — including tools that require human interaction (permission cards, `AskUserQuestion`) and long-running calls. Parallel tool calls keep each output under its own call.
- Replaced all deprecated `JBUI.scale()` calls with `JBUIScale.scale()` across the UI (`ChatPanel`, `TranscriptView`, `ChatMessageViews`, `CommandPalette`, `ClaudeSettingsConfigurable`, `ChatTheme`)

## [1.1.0] — 2026-05-26

### Fixed
- Quota bar stays visible with reset countdown when utilization % is not reported (Max plans); % meter hides independently
- `isWarning` / `isExhausted` no longer fire on `overageStatus = "rejected"` alone
- Token counter now accumulates correctly across multi-message turns (tool calls, chained assistant messages)
- Failed turns with no `result` text (`error_*` subtypes) surface the `errors` list or subtype name — no more silent failures
- `dispose()` sends EOF before killing the process (clean exit, same order as `stop()`)
- `LiveUsage` updates moved to EDT to eliminate read-modify-write race on token counters
- `ready` and `process` marked `@Volatile` — visibility gap on session start/stop across threads
- Startup queue flushed after `system/init` — messages sent before the handshake are no longer dropped
- `JBUI.scale` → `JBUIScale.scale` for correct stroke scaling on IntelliJ Platform 2025+

### Added
- `errors: List<String>` field on `ResultMessage` to capture SDK `SDKResultError.errors` payloads

## [1.0.0] — 2026-05-26

### Added
- Native stream-json + control protocol transport (one long-lived process per tab)
- Streaming chat transcript with markdown rendering (bold, code blocks, tables)
- Multi-chat tabs via `ChatSessionManager`
- Permission-gated diff review: Edit/Write proposals shown as in-editor diff tab + inline Accept/Reject card
- `AskUserQuestion` support with multi-select option cards
- Slash-command palette (all commands from `initialize` + client-side `/btw`)
- Model / effort / permission-mode / thinking chips + gear menu
- Multi-prompt queue (send follow-ups while agent works)
- Quota bar + live token counter + reset countdown
- Auto-diff on acceptEdits / bypass permission mode
- Ctrl+O toggle for reasoning blocks
- Status bar with thinking indicator, live token count and "Esc to interrupt"
- Settings: model, permission mode, effort, thinking tokens, allowed/disallowed tools, setting sources, output style
- UI rethemed to follow the active IDE theme (light/dark); Claude logo icon

[2.1.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v2.0.1...v2.1.0
[2.0.1]: https://github.com/lain/claude-code-for-jetbrains/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.3.5...v2.0.0
[1.3.5]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.3.1...v1.3.5
[1.3.1]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/lain/claude-code-for-jetbrains/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/lain/claude-code-for-jetbrains/releases/tag/v1.0.0
