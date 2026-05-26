# Claude Code Native — Release Notes

## v1.1.0 — 2026-05-26

### Fixed

**Quota bar**
- Reset countdown and overage indicator now stay visible even when the usage % isn't reported (e.g. Max plans with plenty of headroom). The % meter drops out gracefully when the binary doesn't send utilization data instead of hiding the whole bar.
- `isWarning` / `isExhausted` flags no longer trigger on `overageStatus = "rejected"` alone, avoiding false quota-exhausted state.

**Token counter**
- Multi-message turns (tool calls, chained assistant messages) now accumulate correctly. Previously only the last message's token count was shown; each message's tokens are now folded into the session total before the next one starts.

**Error handling**
- Failed turns that carry no `result` text (SDK `error_*` subtypes) now always surface a message in the transcript — falling back to the `errors` list or the subtype name. Silent failures after a tool error are gone.

**Session lifecycle**
- `dispose()` now sends EOF to the binary before killing the process, matching the same order as `stop()` for a clean exit.
- `LiveUsage` token updates now run on the EDT alongside every other state mutation, eliminating a read-modify-write race on the session token counters.
- `ready` and `process` fields are now `@Volatile`, fixing a visibility gap across threads on session start/stop.
- Pending messages queued before `system/init` are now flushed immediately after handshake, so messages sent at startup are never silently dropped.

**Build**
- `JBUI.scale` → `JBUIScale.scale` (correct API for stroke scaling in IntelliJ Platform 2025+).

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

