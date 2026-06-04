# Troubleshooting

Step-by-step diagnostics for the most common problems. If none of these
help, open a bug report using the template under
[`.github/ISSUE_TEMPLATE/bug_report.md`](../.github/ISSUE_TEMPLATE/bug_report.md)
and attach the relevant log snippet from the [Logs](#logs) section below.

## "Claude binary not found"

The plugin locates `claude` via `ClaudeBinaryLocator`, which checks:

1. The path configured in **Settings → Tools → Claude Code Native → Claude
   binary path**, if set.
2. The system `PATH` of the IDE process.
3. `~/.local/bin/claude` (Linux/macOS).
4. On Windows, `claude.cmd` next to the npm prefix.

Fixes:

- Confirm in a terminal: `which claude` (Linux/macOS), `where claude`
  (Windows). The path that prints should also be reachable by the IDE.
- On macOS / Linux, GUI IDEs do **not** always inherit the shell's `PATH`.
  Either add the directory to the system-wide path or set the explicit
  binary path in Settings.
- On Windows, npm installs the binary as `claude.cmd`. Set the explicit
  path to that file — bare `claude` will not work because the spawn does
  not go through `cmd.exe`.
- If you use a custom env script (Settings → "Source script before spawn"),
  make sure it really exports `PATH` in a way the plugin can read.

## "Connection refused" or no response on the first prompt

Usually means the binary started but its auth token has expired or is
missing.

1. Open a terminal and run `claude`. If it prompts you to log in, follow
   it through.
2. Once `claude` works interactively, restart the chat tab in the IDE.

If `ANTHROPIC_API_KEY` is set in your environment but the value is wrong,
the binary will also fail. Either correct it or unset it to fall back to
subscription auth.

## Chat is empty after restart

By default the plugin reopens the previously active chat tabs by calling
`claude --resume <session-id>`.

- If the binary cannot find the session file under
  `~/.claude/projects/<cwd-encoded>/<sessionId>.jsonl`, the tab opens
  empty.
- Disable the behaviour: Settings → Tools → Claude Code Native →
  **Restore open chats on startup** → off.
- Open a specific older session via the chat tab menu → **Open Previous
  Session…**.

## Permission card never appears

When permission mode is `bypassPermissions` or `acceptEdits`, tools are
auto-approved and the inline Accept / Reject card is suppressed by design.
Switch to `default` or `plan` from the mode chip in the composer to see
the card again.

If the card is missing in `default` mode, check the IDE log
(see [Logs](#logs)) for entries from `PermissionBroker` — a hung control
request will be visible as a 30s watchdog warning.

## Leftover diff tabs

Diffs opened for review are real editor tabs, not modal dialogs, so they
remain until you close them. Right-click any editor tab → **Close All
Diffs**, or use the standard close shortcut on each one.

## Tool window does not appear

- Make sure the plugin is enabled: Settings → Plugins → Installed →
  Claude Code Native → enabled.
- View → Tool Windows → **Claude Code**.
- If the menu entry is missing, the plugin failed to load — check the log
  for a stack trace at startup.

## MCP servers do not load

- The JetBrains MCP server toggle requires the **MCP Server** plugin from
  JetBrains to be installed and enabled.
- For `stdio` transport the plugin synthesizes a command line from the
  running IDE's `mcpserver` lib. If you launched the IDE from a stripped
  install, that lib may be missing — switch to `sse` or
  `streamable-http`.
- Custom MCP entries must be valid JSON keyed by server name. Invalid JSON
  blocks saving the settings and is logged.

## Logs

The IDE writes a single rolling log file. The plugin tags its entries with
`claudejb`, `ClaudeSession`, `ClaudeProcess`, `PermissionBroker`,
`ProtocolParser`, or `DiffPresenter`.

Log file locations:

- **Linux:** `~/.local/share/JetBrains/IntelliJIdea*/log/idea.log`
- **Windows:** `%LOCALAPPDATA%\JetBrains\IntelliJIdea*\log\idea.log`
- **macOS:** `~/Library/Logs/JetBrains/IntelliJIdea*/idea.log`

(Replace `IntelliJIdea*` with your product — `PyCharm*`, `GoLand*`,
`WebStorm*`, etc.)

Quick way to extract a relevant snippet on Linux/macOS:

```bash
grep -nE 'claudejb|ClaudeSession|ClaudeProcess|PermissionBroker' \
  ~/.local/share/JetBrains/IntelliJIdea*/log/idea.log | tail -n 200
```

On Windows PowerShell:

```powershell
Select-String -Pattern 'claudejb|ClaudeSession|ClaudeProcess|PermissionBroker' `
  -Path "$env:LOCALAPPDATA\JetBrains\IntelliJIdea*\log\idea.log" |
  Select-Object -Last 200
```

Redact any path under `/home/<you>/` or `C:\Users\<you>\` that you do not
want public before attaching to a bug report.
