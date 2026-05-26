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

---

- Failed turns that carry no `result` text (SDK `error_*` subtypes) now always surface a message in the transcript — falling back to the `errors` list or the subtype name. Silent failures after a tool error are gone.

**Session lifecycle**
- `dispose()` now sends EOF to the binary before killing the process, matching the same order as `stop()` for a clean exit.
- `LiveUsage` token updates now run on the EDT alongside every other state mutation, eliminating a read-modify-write race on the session token counters.
- `ready` and `process` fields are now `@Volatile`, fixing a visibility gap across threads on session start/stop.
- Pending messages queued before `system/init` are now flushed immediately after handshake, so messages sent at startup are never silently dropped.

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

