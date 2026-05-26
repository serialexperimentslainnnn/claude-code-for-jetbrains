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
- Built-in `/btw` command added by the plugin (client-side, not in the binary)

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

