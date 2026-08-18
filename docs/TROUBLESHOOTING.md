# Troubleshooting

Step-by-step diagnostics for the most common problems. If none of these
help, open a bug report using the template under
[`.github/ISSUE_TEMPLATE/bug_report.md`](../.github/ISSUE_TEMPLATE/bug_report.md)
and attach the relevant log snippet from the [Logs](#logs) section below.

## "Claude binary not found"

The plugin locates `claude` via `ClaudeBinaryLocator`, which checks, in order:

1. The path configured in **Settings ▸ Claude Code ▸ claude executable path**,
   if set. A configured path that has gone stale does not fail hard — detection
   continues.
2. The system `PATH` of the IDE process.
3. The usual install directories: `~/.local/bin`, `~/.claude/local`,
   `/usr/local/bin`, `/opt/homebrew/bin`, `/usr/bin` — and on Windows
   `%APPDATA%\npm`, `%LOCALAPPDATA%\Programs\claude`, scoop, volta and
   chocolatey shims.

Fixes:

- The chat's own card will **install it for you**, using the official route for
  your OS. Detection re-runs every few seconds while no session is running, so
  installing it in a terminal also takes effect without closing the tab.
- Confirm in a terminal: `which claude` (Linux/macOS), `where claude`
  (Windows). The path that prints should also be reachable by the IDE.
- On macOS / Linux, GUI IDEs do **not** always inherit the shell's `PATH`.
  Either add the directory to the system-wide path or set the explicit
  binary path in Settings.
- On Windows, prefer the native **`claude.exe`**. The extensionless npm shim is a
  bash script and `CreateProcess` rejects it outright ("%1 is not a valid Win32
  application"); the plugin therefore tries `claude.exe`, then `claude.cmd`, then
  `claude.bat`.
- If you use a custom env script (Settings → "Source script before spawn"),
  make sure it really exports `PATH` in a way the plugin can read.

## "Connection refused" or no response on the first prompt

The plugin will not start a session without a credential it holds itself, so this
is rarely auth any more — but when it is, the tab shows the **sign-in card**
rather than failing a turn. Sign in from there.

If `ANTHROPIC_API_KEY` is set in your environment but the value is wrong, the
binary will fail regardless. Note also that an API key must be **approved once**
before the binary will use it; typing it into the sign-in card *is* that
approval, and the key is validated before being stored — which is why a key that
"looks invalid" when exported by hand works when entered through the card.

## I signed in, and after a reboot it asks me to sign in again

Fixed in **5.0.1** — upgrade if you are below it.

The credential was persisting correctly all along; what expired was the *access
token* inside it, which `auth login` issues with a life of about ten hours. Any
restart the next day found a perfectly good blob that authenticated nothing, and
"no usable token" was read as "signed out". The blob always carried a refresh
token good for weeks, but spending it means the binary rewriting
`~/.claude/.credentials.json` — the file the plugin exists to remove.

The plugin now renews it through the binary's own non-interactive branch (no
browser, no terminal), takes custody of the result, and the refresh token rotates
each time, so ordinary use extends it indefinitely. A failed renewal arms a
five-minute cooldown rather than retrying every poll.

If it still happens on 5.0.1 or later, the renewal itself is failing — check
`idea.log` for `CredentialsVault` around IDE startup, and confirm the machine had
network at that moment.

## My settings are gone / where is `.idea/claude-code.xml`?

Since **5.5.0** the settings live in the IDE's **PasswordSafe**, not in a file.
The old `.idea/claude-code.xml` is read once, copied into the safe, and deleted
only after the safe has accepted the copy. Two things follow:

- **Settings are now global**, not per project. The safe is application-wide,
  which is also the scope these settings actually had.
- **If the safe cannot be read**, the plugin refuses to save over it rather than
  treating a failed read as an empty configuration. On Linux that usually means a
  locked KWallet/keyring — unlock it and restart the IDE. A read that failed
  once, followed by a save, is exactly how a configuration gets lost, and that is
  the case being refused.

Deleting the old file by hand before it has been migrated loses the settings;
there is no other copy.

## An agent tab or a background task looks stuck, stale, or missing

Most of these are the intended behaviour, so it is worth knowing which is which.

- **A finished task keeps its row, its tab and its output.** That is deliberate.
  The binary's `background_tasks_changed` is a *level* signal — it lists what is
  live right now — so rendering it directly made a task's output vanish at the
  exact moment there was something to read. The plugin keeps its own record and
  uses the level only for liveness.
- **An agent shown as running belongs to a turn that is still running.** A nested
  subagent has no tool call of its own to settle it, so it inherits its parent's
  ending; it cannot outlive the turn that spawned it.
- **Agents restored from a previous run are judged by their transcripts.** The
  plugin cannot know how a past agent ended, so it reads the last record of the
  agent's own transcript: a finished assistant turn is *completed*, anything else
  is *stopped*. It does not paint them all red, and does not paint them all
  green.
- **Agents you started from a terminal never appear**, even in the same session.
  An agent is shown only if this plugin saw the `Task` call, or recorded it
  previously in `~/.claude/ide/claude-code-native/agent-index.json`, or its
  parent is already shown. Deleting that index file makes past agents disappear
  from restored chats.
- **A backgrounded task with no output** is showing you the truth: a backgrounded
  shell command publishes no output file, so what is displayed is what the binary
  actually reported. A backgrounded *agent* does publish one, and it is tailed
  live and replayed from the session transcript after a restart.

## Chat is empty after restart

By default the plugin reopens the previously active chat tabs by calling
`claude --resume <session-id>`.

- If the binary cannot find the session file under
  `~/.claude/projects/<cwd-encoded>/<sessionId>.jsonl`, the tab opens
  empty.
- Disable the behaviour: **Settings ▸ Claude Code** → **Restore open chats on
  startup** → off.
- Open a specific older session via the chat tab menu → **Open Previous
  Session…**.

## Permission card never appears

When permission mode is `bypassPermissions` or `acceptEdits`, tools are
auto-approved and the inline Accept / Reject card is suppressed by design.
Switch to `default` or `plan` from the mode chip in the composer to see
the card again.

The reverse also happens and is not a bug: **a card appears even in
`bypassPermissions`** when the call touches credential material, a dangerous
command or foreign territory. That check runs before any auto-approval and has no
opt-out; the per-rule toggles under Settings ▸ Claude Code ▸ Security only
downgrade an automatic refusal to a card, never to a silent allow.

If the card is missing in `default` mode, check the IDE log
(see [Logs](#logs)) for entries from `PermissionBroker` — a hung control
request will be visible as a 30s watchdog warning.

## Leftover diff tabs

Diffs opened for review are real editor tabs, not modal dialogs, so they
remain until you close them. Close them the way you close any editor tab — the
standard close shortcut, or right-click ▸ **Close All Tabs**. The plugin also
closes the ones it opened when the session that opened them goes away.

## The chat never loads, or `NoClassDefFoundError: com/intellij/ui/jcef/JBCefApp`

The whole chat UI is the IDE's embedded browser (JCEF), so without it there is nothing to
show.

- **Below build `253.29346.138` (IDEA 2025.3.1)**: this plugin does not run there at all, and
  that includes the first 2025.3 (`253.28294.334`) as well as 2025.1 and 2025.2. The platform
  serves the browser classes through a module id (`com.intellij.modules.jcef`) that a plugin
  must declare a dependency on, and that id does not exist in any of those builds — so the
  dependency is mandatory where it can be satisfied and unsatisfiable before it. Update the
  IDE, or **stay on 5.1.1** on those versions. Your build number is in Help ▸ About.
- **On `253.29346.138` or newer**: check that the IDE's embedded browser is available — Help ▸
  Find Action ▸ *Registry*, key `ide.browser.jcef.enabled`. Some stripped or
  remote-development setups ship without it.
- The stack trace names `JcefHost.<init>`; anything else with the same symptom belongs in an
  issue, with the log.

## An IDE popup opens but ignores the mouse (Linux / Wayland)

⚙ ▸ *Git Operations* ▸ *Branches* — or any other popup the IDE owns — appears
and then discards every click, while the keyboard still drives it.

**This is not the plugin, and there is nothing in it to change.** The intuition
it invites is that a tool window made of an embedded browser has captured the
pointer, and three separate facts have to be false for that to be possible:

- The chat's browser runs **off-screen** (`JBCefOsrComponent`), so it is a Swing
  component painted by Java2D. It owns no native surface for a popup to be
  attached to, or for a compositor to hand a pointer grab to.
- A Wayland popup's parent is resolved **structurally, not from focus**:
  `WLComponentPeer.getToplevelFor` walks the AWT container chain and returns the
  first `Window` that is not itself a popup, and `AbstractPopup.show` forces
  `SwingUtilities.getRoot(owner)` on Wayland. A child component can never be the
  answer, so the parent is the project frame however the browser is rendered.
- The plugin installs no global `AWTEventListener` and no `IdeEventQueue`
  dispatcher, so it cannot consume a click addressed to something else.

Moving focus out of the chat before invoking the action therefore changes
nothing. The one thing that *does* change a popup's owner is **undocking the
tool window**: `AbstractPopup.getTargetWindow` returns a `FloatingDecorator`
early, so a floating tool window — not the project frame — becomes the popup's
parent toplevel. Dock it back if it is floating.

What to do, in order:

1. Open the chat's **Git** button and press *Branches* there. It is the same
   platform action (`Git.Branches`; the gear menu and the Git view read one
   catalogue, so they cannot offer different things), invoked with no menu popup
   to unwind first. If it works here and not from the gear, the compositor is
   mishandling the gear's popup chain and this button is the standing way round
   it.
2. If it fails there too, the popup path is broken for the whole IDE rather than
   for this plugin, and the way out is to leave the native Wayland toolkit:
   **Help ▸ Edit Custom VM Options…**, add

   ```
   -Dawt.toolkit.name=XToolkit
   ```

   and restart. Since 2026.1 the launcher passes `-Dawt.toolkit.name=auto`,
   which selects `sun.awt.wl.WLToolkit` whenever the session is Wayland; the
   line above pins the X11 toolkit and the IDE runs under XWayland. What it
   costs is crisp *fractional* scaling — nothing at an integer scale factor.
   Confirm which toolkit is live in `idea.log`: the startup banner prints
   `toolkit: sun.awt.wl.WLToolkit` or `toolkit: sun.awt.X11.XToolkit`.

**Upstream.** No JetBrains ticket matches this symptom exactly, so there is no
number to quote as *the* bug. The open meta issues for it are
[JBR-563](https://youtrack.jetbrains.com/issue/JBR-563) and
[IJPL-55086](https://youtrack.jetbrains.com/issue/IJPL-55086) ("mouse clicks are
blocked" on Linux). The nearest exact precedent,
[IDEA-353169](https://youtrack.jetbrains.com/issue/IDEA-353169) — KDE Plasma 6
on Wayland, clicks on toolbars, dialogs and popups ignored while the editor and
the keyboard stayed fine — was closed *Third Party Problem* when a plugin's
global AWT listener turned out to be eating the events, so disabling
third-party plugins is worth doing before filing anything. Native Wayland
support itself is tracked under
[JBR-3206](https://youtrack.jetbrains.com/issue/JBR-3206).

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
