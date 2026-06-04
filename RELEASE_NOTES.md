## v4.0.0 — 2026-06-04

**Chat UI rebuilt on JCEF (embedded Chromium).**

The entire chat surface is now an embedded Chromium web view (JCEF) instead of Swing — an inlined web app (no CDN, no external resources), themeable to your IDE. Diffs stay native via the IDE's `DiffManager`; everything else got a new web front end. The old Swing chat UI (and its tests) was removed.

- **Modern streaming transcript.** Token-by-token rendering of sanitized model markdown (tables, lists), collapsible tool cards that show live elapsed time, fenced code blocks with a language label and a Copy button, and a Ctrl/Cmd+F find bar. Links never navigate the view; external `https` links open in your system browser.
- **Web composer.** Input with model · mode · effort · thinking · provider controls, a queued-prompt strip, a predicted-next-prompt ghost suggestion, a `/` command palette, and attachment chips — including image **drag-and-drop** and **paste** straight into the composer, plus a file picker.
- **Native permission / question / elicitation cards.** Permission prompts, `AskUserQuestion`, and MCP **elicitation** (URL flow gated to http/https, or a form built from the request schema) render as inline cards — no modal dialogs.
- **Session dashboard.** A toggle flips the transcript to a dashboard: context breakdown by category, usage & cost (in / out / cache, USD when the binary reports it), account (email / org / plan / provider), the active model, in-flight subagents (with Stop), and MCP server health (status + reconnect / enable-disable per server).
- **Hardened web view.** Served from an in-process, network-less origin with a full set of real security headers and a strict, **hash-pinned** CSP (every script and the stylesheet allowed only by `sha256`, no `unsafe-inline`/`unsafe-eval`, `connect-src 'none'`), so untrusted rendered content can never fetch, exfiltrate, or execute injected script.
- **Requires JetBrains 2025.2+ (build 252+)** with JCEF (bundled with the IDE's JBR).

**Post-rewrite UI/UX hardening (4.0.0).** A full QA pass over the new JCEF surface closed a stack of bugs and re-ported the Swing features that mattered — all in the frontend, **the Kotlin backend was not touched**:

- **Subagents nest properly.** A Task/Agent card now contains its subagents' tool activity; each nested card collapses/expands on its own (was a descendant-selector bug forcing them all open).
- **Native rewind as the default rollback.** "Restore" on an edit asks Claude Code to `rewind_files` to that turn (client-tagged message uuid + `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING`); falls back to the IDE-side per-file revert behind a confirmation (with a remembered "don't ask again"). Per-edit "View diff" + the Diff History rollback tab return.
- **Clipboard paste on Wayland.** Ctrl+V and "Paste image" are read host-side (text via AWT, image via `wl-paste`/`xclip` resolved across common paths), so image paste works where JCEF's web clipboard comes up empty; text pastes without duplicating.
- **Tool-card states** fade sky-blue↔amber while active, green on success, **red on error** (`is_error`). Inline edit diffs render colourised (added/removed/hunk).
- **Composer** is a flat single-row control bar (📎 · provider · model · mode · effort · thinking | 🌈 · history · follow · send) with the previous icon set; a session-usage line (running/idle + context + tokens) sits above it.
- **Restored/added:** Ctrl+O reasoning toggle (folds collapsed by default, with a hint), auto-follow scroll, the 🌈 Vibe Mode gag (Nyan Cat + rainbow), diffs open without stealing keyboard focus, request cards cap at half height with the body scrollable and actions always visible, `/login` runs in the IDE terminal (browser auto-capture) and shows in the palette, "Explain with Claude" carries the Claude icon, and a **Cancel** button on question cards.
- **Fixes:** the build didn't compile (`object a ChatTheme` + a nested-comment KDoc); session cost counters and the JetBrains MCP server now read the binary's `mcpServers` (camelCase) reply; ⚙ menu reuses the formatted dashboard instead of plain-text dialogs.

**Feature parity + web-only differentiators (4.0.0).** A second pass closed the remaining Swing gap and added what only the web view enables — still all frontend (backend wiring only reuses what already existed):

- **Hunk-by-hunk partial diff acceptance.** Reviewable Edit/Write/MultiEdit permission cards show a checkbox per changed region; accepting a subset narrows the input (`HunkSelection.encodeInput`) so the binary writes only the chosen hunks.
- **`jb://` jump-to-code links.** `@file` mentions render as clickable links that open the file at the line in the editor — DOMPurify-allowed and gated to the project root (`DiffPresenter.isWithinRoot`).
- **Rich attach menu (📎).** A search box + Files / Directory / Image + current selection/file + a filterable **Recent files** list (icon + name), AI-Assistant-style — recents from `FilePickerHelper.recentFiles`.
- **Syntax highlighting in the IDE's colours.** highlight.js token classes map to the live editor scheme (`DefaultLanguageHighlighterColors`), so code blocks match the IDE in any theme.
- **Inline images** (`data:` URIs, kept in-bounds) and a **responsive** layout for narrow tool windows.
- **Deliberately NOT added:** Mermaid / KaTeX — too much external bloat and they'd force relaxing the strict hash-pinned CSP. Kept the plugin lean (~1.6 MB) and the CSP intact.

**Still deferred (small, low-value now):** selecting text from an *open diff* to attach, and an "expand/collapse all" button.

---

## v3.3.0 — 2026-06-04

**The whole binary→host protocol surface, mapped into the UI — plus native MCP elicitation.**

This release closes the loop on the native protocol: **every event the `claude` binary sends the host is now parsed *and* used** — answered when it's a request, surfaced in the chat when it carries information. The two control requests that used to fail with an error are handled correctly, and a batch of events that were parsed-but-invisible are now on screen. Under the hood it's all delegated to small single-responsibility collaborators (the chat session stays a thin orchestrator), and a new `./gradlew checkDrift` keeps the native models in step as the binary and SDK keep moving.

- **MCP elicitation, natively.** When an MCP server needs your input, you now get an **inline card** (never a blocking dialog): a URL flow shows an **Open link** + Accept/Cancel; a form renders a labeled field per schema property and sends back what you type on Accept. URL links are restricted to `http`/`https` — an untrusted server can't get a `file:`/`javascript:` link opened.
- **Predicted next prompt.** A `💡` chip above tdhe composer offers the binary's predicted follow-up; click to drop it into the input (you still review and send it yourself).
- **See the model think.** The status line shows a live reasoning-token estimate mid-turn.
- **Hooks, visible.** Each hook the binary runs appears as a single transcript row that updates from "running…" to ✓/✗ — no more silent hooks.
- **Memory recall, surfaced.** A collapsible "Recalled N memories" row shows exactly what context influenced a turn.
- **Smaller touches.** Tool-use summaries render as quiet notes, and file uploads now confirm success, not just failures. `request_user_dialog` requests are answered correctly instead of erroring.
- **Drift detection.** A new `./gradlew checkDrift` keeps the native protocol models in sync as the `claude` binary and its SDK evolve.

---

## v3.2.1 — 2026-06-04

**Pick your model provider — Anthropic or DeepSeek — without ever leaking your Anthropic credentials.**

- **Provider selector.** A new `Provider:` option (in Settings and as a composer chip, with each provider's brand logo) lets you point the `claude` binary at **Anthropic** (your normal subscription/login) or **DeepSeek** (its Anthropic-compatible endpoint). Switching restarts the session.
- **DeepSeek needs its own key — and that's enforced.** A non-Anthropic provider requires its **own issued key**, kept **isolated per provider in the IDE password safe** (never in a project file). Pick DeepSeek with no key and the plugin asks you to configure it first instead of switching.
- **Your Anthropic credentials are never used for another provider.** The endpoint and key are set together as a pair, the binary won't even load your Anthropic OAuth when a provider key is present, and the form refuses an Anthropic key (`sk-ant-…`) in the DeepSeek slot. `/login` only applies to Anthropic.

This release also fixes a pesky reasoning-regression: new "Thought process" blocks now respect the Ctrl+O toggle instead of always showing expanded — toggle reasoning off once and it stays off for every subsequent turn.

It's also a hands-on way to see what these "Anthropic-compatible" Chinese endpoints actually do with Claude Code's tool calls.

---

## v3.2.0 — 2026-06-04

**Composer polish, smarter links, real rollback — and a Vibe Coder Mode nobody asked for.**

- **A two-row options bar.** Model · mode · effort · thinking pills on top (centred, each with its own icon and a hover glow), the toggles + attach + a thin neon Play/Stop on the bottom. The prompt lights a coral focus ring while you type and uses your editor font.
- **Follow toggle.** A button keeps the streaming answer pinned to the bottom even if you scroll up; turn it off to read history mid-stream (it still follows while you're at the bottom). Ctrl+O collapsing the reasoning no longer jumps the scroll.
- **Attachments that actually work.** A file you attach is sent to the binary as a `@cwd-relative` mention it expands, and shows in your message as a clickable link. More file references in answers become clickable too — even without a line number (inside backticks), while staying conservative in prose.
- **Rollback, finished.** Reverting a Write that *created* a file now deletes it (not a 0-byte husk); reverting an edit restores the prior contents; every revert shows a confirmation. The in-card Diff/Revert buttons are now easy to hit.
- **Settings that fit your screen.** The settings page no longer stretches edge-to-edge on a wide monitor.
- **Sign in without leaving the chat.** `/login` no longer opens a terminal tab (which stopped working once JetBrains made the reworked terminal the default). It now opens your browser to approve access and asks you to paste the code right in the IDE — then signs you in and reconnects automatically.
- **🌈 Vibe Coder Mode.** An opt-in toggle that turns the whole chat neon-rainbow — send glyph, pills, boxes, icons, the focus ring — and swaps the Claude avatar for a Nyan Cat. Purely for the lulz; off by default.

Also in this release (the composer overhaul):

- **Paste images (Ctrl/Cmd+V).** Paste a screenshot straight into the composer — fixed on Linux (Wayland/X11) where the clipboard hands over a raw `image/…` stream; wired through the IDE action system so it follows your keymap on every OS.
- **A proper attachment menu.** Add the current file / selection / clipboard image, files and a directory from a native picker, or pick from your **open** and **recently-opened** files; chips show the real file-type icon and open the file when clicked.
- **Its own visual identity.** Custom icons on every tool call (bash, read, edit, search, web, task…), the attach button and chips, with the Claude coral as the accent — everything else still follows your IDE theme.
- **Diff History tab.** Lists every Edit/Write Claude made this session with a `+a/-b` summary, **View diff** and **Revert**, plus **Roll back all changes**.

`verifyPlugin` Compatible against IU-261 and IU-262 (RC); zero deprecated/internal APIs.

---

## v3.0.1 — 2026-06-03

**Performance pass — the plugin was already faster than the CLI; now it flies.**

A focused optimization patch. Same features and behaviour as 3.0.0, materially lower latency and CPU, especially during streaming and on large sessions.

- **Streaming feels instant** — assistant/thinking deltas are coalesced and applied to the UI in a single hop per batch instead of one per token, cutting EDT churn dramatically.
- **Transcript renders only what changed** — a growing reply re-lays-out its own row, not the whole conversation; markdown is memoised and syntax highlighters are cached/reused.
- **Big sessions load fast** — restore reconstructs the recent tail (the binary keeps full context via `--resume`), and tool outputs anchor in O(1) instead of scanning the transcript.
- **Lighter under load** — one shared animation timer for all tool boxes (not one each), one quota poll per session (not per tab), and an O(n) stdout line splitter.

Includes a post-review hardening pass: a cap on the stdout buffer (no unbounded growth on a malformed stream), more robust streaming auto-scroll, orphan tool-result rows dropped on restore, the usage meter no longer blanks between turns, a synchronous delta drain on teardown, and markdown that re-renders on a theme switch. Plus two field-reported fixes: toggling thinking/model no longer closes the session (a restart race), and the edit diff now shows in **every** permission mode (acceptEdits / bypass / auto / dont-ask), not only when a permission card appears.

**Log in from the IDE.** `/login` used to dead-end with *"not available on this environment"* because the chat session has no interactive terminal. Now the plugin opens a real IDE terminal and runs `claude auth login` for you (launched with the binary's full path, so it works even when the IDE didn't inherit your shell `$PATH`). A detected auth failure also offers a one-click **"Log in in terminal"** prompt.

494 tests (0 failures) plus the gated UI suite; `verifyPlugin` Compatible against IU-261 and IU-262 (RC); zero deprecated/internal APIs.

---

## v3.0.0 — 2026-06-03

**The whole Anthropic Agent SDK, nativized in JetBrains.**

3.0.0 turns the plugin from "a great chat" into a full native surface for the Claude Agent SDK protocol — every `system/*` and stream event parsed, every host→binary control request wired to a GUI control, and a redesigned, IDE-themed composer. Still no Node or TS SDK at runtime: the plugin speaks the binary's `stream-json`/control protocol directly.

**What's new**
- **Live session consumption** — a native panel (IDE progress bars + labels) above the composer shows the context window, the **authoritative cumulative token breakdown** (input / cache write / cache read / output, from the binary's `get_session_cost`) **inline**, and a quota bar with the reset countdown **and** absolute reset hour. The quota % shows when the binary reports it (no misleading 0%).
- **Tool-call state on the box** — each tool card is **sky-blue while in flight (pulsing sky↔amber)** and **green when finished**, with elapsed time while running.
- **Rich IDE attachments** — the 📎 button opens a selector menu (current file / selection / clipboard image), files/selections pin as chips, and you can **drag & drop or paste images** straight into the composer (native image content blocks). Large files are size-guarded and read off the UI thread.
- **Native "thinking" indicator** — the IDE's own animated spinner while a turn runs.
- **Subagent (Task) live strip** — one card per in-flight subagent with its running tokens / tool-uses / elapsed time and a Stop button; paused/failed states surface inline.
- **Advanced launch options** in Settings — max turns, max budget (USD), fallback model, extra `--add-dir` roots, beta flags, strict MCP config.
- **Plan mode & richer permissions** — ExitPlanMode plan cards, decision reasons, blocked-path context.
- **Session management** — rename, fork, and delete past sessions (the binary's session files stay the source of truth).
- **Native hooks** — `hook_callback` answered host-side via a pure decision engine (the real tool gate is still `can_use_tool`).
- **Diagnostics & account** — Account, Binary Version, Effective Settings, and an interactive MCP-runtime dialog (reconnect/toggle per server) in the gear menu.
- **A Diff button on every Edit/Write/MultiEdit row** plus syntax-highlighted code.

**Under the hood**
- The two former god-objects (`ClaudeSession`, `ChatPanel`) were decomposed into focused, individually-tested collaborators (token accounting, task tracking, transcript reconciliation, diff lifecycle, control client, permission cards, hooks, launcher; UI sub-panels for permissions/queue/subagents/attachments/usage). The orchestrators are now thin.
- A final hardening pass across security, architecture, concurrency, UX and protocol-fidelity: image read/encode moved off the EDT, the absolute reset-hour and a non-colour warning marker restored, subagent status/error updates wired, shared token formatting, and more.
- A native-UI + correctness pass from hands-on testing: the consumption readout rebuilt from native components, usage sourced from the binary's authoritative `apiUsage`, human-readable permission-mode labels, the model default shown as "Default · Opus 4.8", a responsive (scrollable) Settings page, and a fix so approving a plan no longer leaves the Mode chip stuck on "plan".
- **474 automated tests** (0 failures) across unit / headless / integration, plus the gated RemoteRobot UI suite (locators validated against a live IDE). Verified Compatible against IU-261 and IU-262 (RC), with zero deprecated/internal APIs.

---

## v2.2.2 — 2026-06-03

**Full automated test pyramid + release automation — same runtime as 2.2.0**

Completes the testing and maintenance foundation started in 2.2.1. End-user behaviour is unchanged; under the hood every layer of the plugin is now covered by automated tests and releases are automated end-to-end.

**Test pyramid (now complete)**
- **Headless component tests** run the IntelliJ Platform in-process to cover services and Swing wiring that pure unit tests can't reach (diff registry, session manager, settings UI, real token accounting).
- **Integration tests** drive a real `ClaudeSession` against a deterministic `fake-claude` stand-in via JSONL fixtures — init, streaming, thinking, token fold, rate-limit, tool permission, resume, interrupt, and the auto-approve cascade regression.
- **End-to-end UI tests** (RemoteRobot) cover the click-paths a user actually takes; they run in a nightly workflow.
- **239 tests** in the default suite (0 failures), plus the gated UI suite.

**Release automation**
- Tag a `vX.Y.Z` and `release.yml` runs the full test + verifier gate, then signs and publishes to the Marketplace and cuts a GitHub Release. A nightly `ui-tests.yml` runs the RemoteRobot suite under Xvfb. `docs/BRANCHING.md` captures the GitFlow + branch-protection conventions.

---

## v2.2.1 — 2026-06-03

**Test pyramid + maintenance workflow — same runtime as 2.2.0, professional foundation underneath**

This release is the **infrastructure update**: the plugin's behaviour is identical to 2.2.0 but it now ships with the CI, documentation, drift detection, and test scaffolding that a Marketplace-listed plugin with real users deserves.

**Test pyramid foundations**
- **202 tests** (+67 since 2.2.0): direct security-gate coverage for `DiffPresenter.isWithinRoot` (incl. symlink escape attempts), exhaustive `PermissionBroker` matrix, `ClaudeBinaryLocator` cross-platform, `McpConfigBuilder` full transport coverage, `parseAskQuestions`, and `MarkdownRenderer` edge combinations.
- New Gradle source sets `integrationTest` and `uiTest`. The `integrationTest` task drives the plugin against `bin/fake-claude`, a deterministic Python stand-in fed JSONL fixtures from `src/integrationTest/resources/fixtures/` — Layer C scenarios will land here in 2.2.x. `uiTest` is reserved for the RemoteRobot end-to-end suite.
- `kotlinx-kover` coverage report (`./gradlew koverHtmlReport`).

**CI + maintenance workflow**
- `.github/workflows/ci.yml`: runs tests, `verifyPlugin`, and `buildPlugin` on every push and PR, uploading the plugin zip as an artifact.
- `.github/workflows/sdk-drift.yml` (weekly), `binary-drift.yml` (daily), `binary-probe.yml` (weekly + manual): open issues automatically when a newer SDK or `claude` binary is released, or when the binary starts emitting an event type the plugin doesn't parse.
- `SECURITY.md` (responsible disclosure + SLA), `CONTRIBUTING.md`, `CODEOWNERS`, issue + PR templates, Dependabot for Gradle and the SDK reference.

**Documentation**
- `docs/RELEASE_PROCEDURE.md` + `docs/RELEASE_CHECKLIST.md` (end-to-end release flow), `docs/BINARY_COMPAT.md` (which `claude` binary each plugin version was tested against), `docs/FAQ.md`, `docs/TROUBLESHOOTING.md`, `docs/TELEMETRY.md` (plain answer: we collect nothing).

**Why 2.2.1 instead of folding into 2.2.0**: 2.2.0 is the Marketplace-publishable bug-fix release that unblocked publication (`findEnabledPlugin` migration). 2.2.1 cleanly separates the runtime fixes from the maintenance/test infrastructure so the changelog tells the truth.

---

## v2.2.0 — 2026-05-28

**Marketplace-publishable, live model picker, links inside backticks, aligned with `claude` 2.1.161**

Compatibility + UX iteration on top of 2.1.0. 134 tests.

**Marketplace fix**
- Migrated the bundled MCP plugin lookup from an `@ApiStatus.Internal` API to the public static `PluginManager.getPlugin(PluginId)`. This was the lone internal-API hit blocking the upload re-check; the plugin is now Marketplace-acceptable.

**Model picker**
- The Settings model combo shows the binary's actual options (`Default (recommended)` = Opus 4.8 with 1M context, `Sonnet` = Sonnet 4.6, `Haiku` = Haiku 4.5) by their human label, and **refreshes live** when the `initialize` handshake lands — so opening Settings before the session warms up no longer leaves only the historical Opus tags.
- New installs default to `default` (the binary picks the recommended tier) instead of the hard-coded `claude-opus-4-7`. Existing settings keep what you had.

**Linkify in backticks**
- `` `src/Foo.kt:42` `` references (the natural way the model writes paths in code answers) now render as clickable `jb://open` links wrapped in `<code>`, instead of inert monospaced text. Still confined to the project root.

**Protocol**
- Bumped the SDK reference to **0.3.161**, aligned with `claude` **2.1.161**. New `ModelInfo` fields (`supportsEffort`, `supportedEffortLevels`, `supportsAdaptiveThinking`, `supportsFastMode`, `supportsAutoMode`) and `AccountInfo` fields (`apiProvider`, `apiKeySource`) are now decoded; new `system/*` events the binary now emits (`task_progress`, `task_notification`, `background_task_*`, `auth_status`, `session_state_changed`) are tolerated — UI integration in a follow-up.

---

## v2.1.0 — 2026-05-27

**Review edits anytime, readable questions, partial acceptance, sessions from the binary's own files, and more**

A round of native-only UX features, backed by new tests (132 total).

**Edit review**
- **Persistent diff from the transcript** — every Edit/Write/MultiEdit keeps a "View diff" button on its tool card, so you can re-open the old↔new diff at any time, in any permission mode (even after an auto-approved edit). The pre-write file contents are snapshotted at approval time and keyed to the tool call.
- **Hunk-by-hunk acceptance** — the permission card now lists the change's hunks with checkboxes; accept only the ones you want and the binary writes exactly that subset (the file path is never altered).

**Readability**
- **AskUserQuestion options** now wrap — long labels, descriptions and the (previously unused) per-option preview are shown in full instead of being clipped to one line.
- **Better Markdown** — strikethrough, GFM task-list checkboxes, nested lists, and a fix for double-linkified URLs.

**Editor integration**
- **"Explain with Claude"** in the editor right-click menu sends the current selection (with its file path) to the active chat.
- **Jump-to-code** — `path:line` references in Claude's replies become clickable links that open the file at that line (confined to the project tree).

**Permissions**
- **"Always allow" per tool** — approve a tool once and skip its prompt for the rest of the project; reviewable writes still stay confined to the project root. Settings lists the remembered tools with a Remove button, so you can revoke any rule.

**Sessions**
- **Reads the binary's own session files** — past conversations come straight from Claude Code's transcripts (`~/.claude/projects`), so the plugin never stores a copy of your chats. **Open Previous Session…** lists the project's sessions by their real title (the one `--resume` shows) and re-attaches with `--resume`.
- **Restore on startup** — the tabs you had open are reopened automatically; if none were recorded, your most recent session is restored. Turn it off in Settings ▸ Claude Code ▸ "Restore open chats on startup".
- A background session that needs attention (pending permission, finished turn, or error) raises a notification and a tab badge — suppressed only for the chat currently on screen; the notification's **Open** button dismisses it.

**Security**
- Jump-to-code links are confined to the project root (a crafted `path:line` can't open `~/.ssh`, `/etc`, or `..`-traversed files), and explicit Markdown links are restricted to an allow-list of schemes with their href escaped.
- The plugin writes **no conversation content** to project files — only which tabs were open (in `workspace.xml`). Session-file reads stay inside `~/.claude/projects` behind a UUID-shaped id check, so a crafted session id can't traverse out.

**Fixes**
- Reasoning shows again on current models: extended thinking now uses the launch flags `--thinking adaptive --thinking-display summarized` (the old `set_max_thinking_tokens` control stopped surfacing it). Thinking is on/off — adaptive, the model decides depth.
- No more "Write-unsafe context!" crash when refreshing edited files (the VFS refresh is now asynchronous).

---

## v2.0.1 — 2026-05-27

**Compatibility update**

- Extended the supported IDE range to the current EAP: `until-build` is now `262.*`, so the plugin installs and runs on the 2026.2 EAP builds (verified Compatible against IU-262 with `verifyPlugin`).
- Replaced the internal `PluginManagerCore` API used to locate the bundled MCP Server plugin with the public `PluginManager` lookup by plugin id (`com.intellij.mcpServer`), removing the only internal-API usage and dropping the fragile path-name heuristic.

---

## v2.0.0 — 2026-05-26

**Reliability & security hardening + first unit-test suite**

This release is a stability milestone: a multi-profile review (security, clean-code, SRE) drove a round of fixes across the process lifecycle, permission handling and the protocol layer, backed by the project's first automated tests.

**Reliability**
- Fixed an EDT freeze on session start: resolving the process environment (which sources a login shell, up to a multi-second timeout) and spawning the binary now run off the UI thread; the resolved environment is cached per session. The IDE no longer hangs when opening the first chat or sending the first prompt.
- In-flight control requests (`get_context_usage`, session cost, MCP status, the initialize handshake) are now resolved when the process stops or crashes, instead of leaving dialogs stuck on "Loading…".
- Control requests get a 30s watchdog, so a hung binary no longer leaves a callback pending forever.
- Process start failures are now surfaced (notification + log) instead of leaving a half-initialized "ready" session; writes to a dead stdin are logged instead of silently dropped.

**Security**
- Auto-approved file writes (in `acceptEdits` / `bypassPermissions`) are now confined to the project root: a write whose path resolves (symlinks included) outside the project falls back to a manual Accept/Reject card.
- Trust-on-open gate: if a project's `claude-code.xml` carries a source script or a custom stdio MCP server (both execute code at launch), the plugin asks for confirmation once before running them.
- The source-script argument is now passed without shell interpolation (no injection via a crafted path), and Settings now warns that environment variables are stored in plain text and that the source script is executed on start.

**Quality**
- First unit-test suite (80 tests): protocol parsing/building, diff reconstruction, transcript hierarchy, rate-limit math, environment parsing.
- Internal cleanups: MCP config building extracted to a testable unit, thread-safe tab counter, named constants, and quieter-failure logging.

---

## v1.3.5 — 2026-05-26

**IDE tools over MCP (opt-in)**
- New section in Settings ▸ Claude Code with two independent controls:
  - **Enable JetBrains MCP server** — let Claude query the IDE (diagnostics, open files, usages, …) through JetBrains' own MCP Server plugin. Choose the transport (`sse`, `streamable-http`, `stdio`) and port. For `sse`/`streamable-http` the localhost endpoint is filled in for you; **`stdio` is assembled automatically from the running IDE** (its JBR `java` and bundled `mcpserver` libs), so there's nothing to paste and it works on Windows unchanged.
  - **Custom MCP servers** — add as many of your own MCP servers as you like, as a JSON object (`name → server config`).
- Off by default. Every IDE tool call is still gated by the in-chat permission prompt; enable only on a machine you trust.

> Requires JetBrains' **MCP Server** plugin enabled (Settings ▸ Plugins) for the JetBrains option.

---

## v1.3.1 — 2026-05-26

**Fixes & defaults**
- Fixed the Settings model dropdown showing empty when opened before the initialize handshake — it now always lists available models plus known fallbacks.
- Removed the blank entry from the Effort dropdown in Settings.
- Default model is now **Opus 4.7**; default effort is now **medium**.

---

## v1.3.0 — 2026-05-26

**Windows support**
- The `claude` binary is now detected on Windows (`claude.exe` / `claude.cmd`) across npm, scoop, volta, chocolatey and `~\.local\bin`.
- npm `.cmd` shims are driven as `node cli.js` directly, bypassing cmd.exe — which corrupted the streaming stdio pipe (stdin EOF triggered "Terminate batch job (Y/N)?") and mangled argument quoting. This fixes both the "not a valid Win32 application" (error 193) failure and the "found but no response" hang.

**Configurable paths & environment**
- Explicit overrides for the `claude` and `node` executable paths in Settings — the catch-all for custom install dirs, version managers, or a GUI IDE that doesn't inherit the user's PATH.
- Configurable environment variables (`KEY=VALUE` per line) injected into the binary's process — useful on Windows for `PATH` additions.
- **Source script**: point to a `.sh` (sourced in the login shell on Linux/macOS) or a PowerShell profile/`.ps1` (dot-sourced on Windows); its resulting environment is captured and applied to the `claude` process, so the IDE inherits the same `PATH`/setup as the user's own shell.
- The "binary not found" notification now offers a **Configure paths…** action that jumps straight to the settings page.
- The auto-detected `claude` path is persisted on first launch (and refreshed when stale), so startup is stable and the path is visible/editable.

---

## v1.2.0 — 2026-05-26

**Hierarchical, collapsible transcript**
- Every tool call is now a collapsible group: a disclosure triangle on the tool card shows or hides its output.
- Subagent (`Task`/Agent) activity nests under its Agent — the subagent's own tool calls, outputs and text are anchored and indented beneath the Agent card. Collapsing is hierarchical: collapse the Agent to fold its whole subtree, or collapse a single sub-tool to fold just its output.
- Tool outputs now anchor directly under their tool call instead of drifting to the end of the transcript. This fixes outputs of tools that require human interaction (permission cards, `AskUserQuestion`) and long-running/parallel calls, where the result previously landed at the tail.

**Build**
- `JBUI.scale` → `JBUIScale.scale` (correct API for stroke scaling in IntelliJ Platform 2025+).

**Info bar**
- Reordered: (1) Resets in countdown, (2) Reset Hour, (3) Session Usage %, (4) Brewing / live tokens / Esc to interrupt.

---

## v1.1.0 — 2026-05-26

**Quota & rate-limit fixes**
- Quota bar stays visible with reset countdown when utilization % is not reported (Max plans); % meter hides independently.
- `isWarning` / `isExhausted` no longer fire on `overageStatus = "rejected"` alone.
- Token counter now accumulates correctly across multi-message turns (tool calls, chained assistant messages).

**Session reliability**
- Failed turns with no `result` text (`error_*` subtypes) surface the `errors` list or subtype name — no more silent failures.
- `dispose()` sends EOF before killing the process (clean exit, same order as `stop()`).
- `LiveUsage` updates moved to EDT to eliminate read-modify-write race on token counters.
- `ready` and `process` marked `@Volatile` — visibility gap on session start/stop across threads fixed.
- Startup queue flushed after `system/init` — messages sent before the handshake are no longer dropped.

**Protocol**
- `errors: List<String>` field added to `ResultMessage` to capture SDK `SDKResultError.errors` payloads.

---

## v1.0.0 — 2026-05-26

First stable release.

### Features

**Streaming chat**
- Real-time token streaming with animated thinking indicator and live token counter
- Multi-tab support — open independent sessions per project
- Multi-prompt queue: type while the agent is working, messages are sent in order when the turn ends
- `/btw` side-questions sent mid-turn without interrupting the active turn
- Interrupt (Esc) to stop the current turn at any time

**Full slash-command palette**
- All commands from the `claude` binary are surfaced natively (Ctrl+K / Cmd+K)

**Model & runtime controls**
- Model selector chip (all models available in your account)
- Permission mode chip (default / acceptEdits / bypassPermissions)
- Effort chip (low / medium / high) for extended thinking
- Thinking token budget (Ctrl+O to toggle)

**Native diff review for file edits**
- When the agent requests to write or edit a file, a diff opens inline in the editor area
- Non-modal Accept / Reject card in the chat — no popups or modal dialogs
- On acceptance the binary writes the file; VFS is refreshed automatically

**Permission & question handling**
- `can_use_tool` requests surface as native inline cards (Accept / Reject)
- `AskUserQuestion` rendered as a structured question card with option buttons
- Auto-approve in `acceptEdits` / `bypassPermissions` modes

**Quota bar**
- Subscription usage % shown when the binary reports it (near the usage limit)
- Displays reset window countdown and overage status

**Settings**
- Configurable default model, permission mode, effort, thinking tokens
- Allowed / disallowed tools, setting sources, output style
- All settings accessible via Settings → Tools → Claude Code

**IDE integration**
- Tool window anchored to the right panel (same area as AI Assistant)
- Light and dark theme support with custom icons
- Works in any IntelliJ Platform IDE (IDEA, PyCharm, WebStorm, GoLand, etc.)

### Requirements

- JetBrains IDE 2024.3 – 2025.1.x (build 243–261)
- [`claude` binary](https://claude.ai/code) installed and on `PATH` or `~/.local/bin/`
- Claude subscription (claude.ai) or `ANTHROPIC_API_KEY` environment variable

