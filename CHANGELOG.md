# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
