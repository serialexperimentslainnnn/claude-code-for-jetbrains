# FAQ

Short answers to the questions we get most. For deeper diagnostics see
[`TROUBLESHOOTING.md`](TROUBLESHOOTING.md); for privacy see
[`TELEMETRY.md`](TELEMETRY.md).

## How do I install the plugin?

Settings → Plugins → Marketplace → search **Claude Code Native** →
Install → restart the IDE. Or install from disk using the zip from
[GitHub Releases](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains/releases).

After install, a "Claude Code" tool window appears on the right.

## Which Claude account does the plugin use?

**Whichever account your local `claude` binary is already authenticated
with.** The plugin spawns the binary you have on `PATH` (or at
`~/.local/bin/claude` on Linux/macOS) and reuses its credentials —
subscription, OAuth, or `ANTHROPIC_API_KEY`. The plugin never asks you to
log in again and stores no tokens of its own.

If you have not logged in yet:

```bash
claude login
```

## How do I change the model?

Click the **model chip** at the bottom of the chat composer (e.g. `Default`,
`Sonnet`, `Haiku`). Selecting a different model restarts the current session
under `--resume`, so the transcript is preserved.

You can also set the default model permanently in Settings → Tools →
Claude Code Native.

## What is the difference between Default, Sonnet, and Haiku?

- **Default** — the model alias your binary resolves to, usually the latest
  recommended model for your subscription tier.
- **Sonnet** — Claude Sonnet family. Balanced quality and latency.
- **Haiku** — smaller, faster, cheaper. Good for short edits and quick
  questions.

The exact mapping is decided by the `claude` binary, not the plugin.

## How do I see the cost of a session?

The quota bar in the composer shows live token usage. The control message
`get_session_cost` is queried periodically and the result is rendered next
to the spinner. For an authoritative breakdown across sessions, use
`claude /cost` in a terminal — same account, same data.

## I get "Connection refused" or "Claude binary not found"

The plugin looks for `claude` on `PATH` and then at `~/.local/bin/claude`
(Linux/macOS). If neither is found, an actionable notification appears.

Fix:

1. Verify the binary exists: `which claude` (or `where claude` on Windows).
2. If it is in a non-standard location, set it in Settings → Tools →
   Claude Code Native → **Claude binary path**.
3. On Windows, the npm install uses `claude.cmd` — point the setting at
   that file.

See [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) for more.

## How do I disable restoring open chats on startup?

Settings → Tools → Claude Code Native → uncheck
**Restore open chats on startup**. The plugin will then start with a
single empty tab instead of reopening your previous sessions via
`--resume`.

## How do I clean up leftover diff tabs?

Use the editor tabs context menu **Close All Diffs**, or close them
individually. Diffs opened by the plugin are real editor tabs, not modal
windows, so they stay until you close them.

## Does the plugin send my code or prompts anywhere?

No. The plugin itself sends nothing. Your conversations go from the
`claude` binary to Anthropic over the same channel `claude` already uses
in your terminal. See [`TELEMETRY.md`](TELEMETRY.md).

## How do I send feedback?

- Bugs and features: open an issue using the templates under
  [`.github/ISSUE_TEMPLATE/`](../.github/ISSUE_TEMPLATE).
- Security: see [`../SECURITY.md`](../SECURITY.md).
- General feedback: leave a Marketplace review.
