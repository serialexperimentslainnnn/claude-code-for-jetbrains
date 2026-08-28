# Claude Code Native

[![Version](https://img.shields.io/badge/version-5.7.1-E07B5A)](CHANGELOG.md)
[![IDE](https://img.shields.io/badge/JetBrains-2025.3.1%20%E2%86%92%20263.*-000000?logo=jetbrains)](#requirements)
[![Marketplace](https://img.shields.io/badge/Marketplace-Claude%20Code%20Native-2A2A2A)](https://plugins.jetbrains.com/plugin/31965-claude-code-native)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

An unofficial IntelliJ Platform plugin that puts [Claude Code](https://code.claude.com/docs/en/overview)
inside JetBrains IDEs as a full graphical client: a streaming chat, inline permission cards, file edits
reviewed as real IDE diffs you can modify before approving, a tab per agent, and a deterministic
security layer that gates every tool call.

It drives the `claude` binary — the one you already have, or one it installs for you on first run if
you do not — speaking its `stream-json` and control
protocol directly from Kotlin. There is no Node.js at runtime, no bundled SDK, and no credentials of
ours — you bring your own Claude subscription or API key.

> **This repository is the project's origin**, written and maintained by
> [Lain](https://github.com/serialexperimentslainnnn) — every release on the JetBrains Marketplace is
> published from here. Canonical location:
> **<https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains>**. Forks are welcome and
> licensed; see [Upstream and forks](#upstream-and-forks) for where they are and how to tell them apart.

## Contents

- [How it compares](#how-it-compares)
- [Requirements](#requirements) · [Installation](#installation) · [First run](#first-run)
- [User guide](#user-guide)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Build from source](#build-from-source) · [How it works](#how-it-works)
- [Documentation](#documentation)
- [Upstream and forks](#upstream-and-forks) · [Licence](#licence-and-attribution)

## How it compares

Three different things are often confused. All of them are legitimate; they solve different problems.

| | **Claude Code Native** (this plugin) | **Claude Code [Beta]** (Anthropic's own plugin) | **AI Assistant / Claude Agent** (JetBrains) |
|---|---|---|---|
| Where you type | A chat panel in the IDE | The IDE's terminal | The AI Assistant chat panel |
| Diffs | The IDE's own diff viewer, opened on the permission request; your edits to the proposed side are what gets written | The IDE's own diff viewer, for reviewing and modifying proposed changes | JetBrains' own diff flow |
| Permissions | An inline card per call, plus a deterministic lock that runs before any auto-approval | Handled by the CLI in the terminal | JetBrains' own approvals |
| Account | Your `claude` subscription or API key | Your `claude` subscription or API key | JetBrains AI credits, your own Anthropic API key, or a Claude Console account |
| Agents / background tasks | A tab and a transcript per agent; background tasks keep their output | Visible as terminal output | Not applicable |
| Needs the `claude` CLI | Yes — and installs it for you if you do not have it | Yes | No |

Anthropic's [Claude Code [Beta]](https://plugins.jetbrains.com/plugin/27310-claude-code-beta-) is not
"just a terminal launcher" — it runs `claude` in the IDE's integrated terminal and adds diff viewing in
the IDE's own viewer, automatic sharing of the current selection and open tab, diagnostics sharing, and
a file-reference shortcut (`Cmd+Option+K` / `Ctrl+Alt+K`). What it deliberately does not do is replace
the terminal with a GUI. That is the gap this plugin fills.

JetBrains' **Claude Agent** lives inside AI Assistant. It does not use your local `claude` CLI: it
authenticates through a JetBrains AI subscription (credits), your own Anthropic API key, or a Claude
Console account. If you want a graphical client driven by the CLI you already have, this plugin is the
option; if you are already inside the JetBrains AI ecosystem, theirs is the shorter path.

This project is unofficial and not affiliated with Anthropic or JetBrains.

## Requirements

**JetBrains IDE 2025.3.1 or newer** — `sinceBuild 253.29346.138`, `untilBuild 263.*`, so the range is
declared ahead of the 2026.3 branch and an EAP user is never locked out by a ceiling nobody widened.
IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, GoLand, RubyMine, CLion, Rider, DataGrip, DataSpell, Aqua
and RustRover.

> **Why 2025.3.1 is a hard floor — and why it is .1 and not .0.** The whole chat UI is the IDE's
> embedded browser (JCEF). From build **262** the platform ships that browser as a *separate bundled
> plugin*, `com.intellij.modules.jcef`, and a plugin that does not declare a dependency on it gets no
> browser classes in its classloader at all — every chat dies on `NoClassDefFoundError:
> com/intellij/ui/jcef/JBCefApp`. Declaring the dependency is the fix. That module id does not exist in
> **2025.3** (build 253.28294.334) either, so there the IDE refuses to load the plugin outright; it
> appears in **2025.3.1** (253.29346.138), ten days later. There is no browser-less mode to fall back
> to, so the dependency is declared hard and the floor is the first build that can satisfy it.
> **On 2025.1, 2025.2 or 2025.3.0, stay on plugin version 5.1.1** — or update your IDE.

**The `claude` CLI — and you do not have to install it yourself.** If the plugin cannot find it, its
first screen offers to install it for you, using the official route for your OS, and runs it in the
IDE terminal. Nothing to prepare before you start; it looks for an existing one first, in this order:

1. the path set in **Settings ▸ Claude Code ▸ claude executable path**, if any — and if that path has
   gone stale, detection continues rather than failing hard;
2. the IDE process's `PATH`;
3. typical locations — `~/.local/bin`, `~/.claude/local`, `/usr/local/bin`, `/opt/homebrew/bin`,
   `/usr/bin` on Linux/macOS; `%USERPROFILE%\.local\bin`, `%APPDATA%\npm`,
   `%LOCALAPPDATA%\Programs\claude`, scoop shims, volta and Chocolatey `bin` on Windows.

Only if all three come up empty does it ask — and then it installs it for you (see
[below](#installing-the-claude-cli)).

**An account**: a paid Claude plan (Pro, Max, Team, Enterprise) or a Claude Console account, signed in
through the plugin — or an `ANTHROPIC_API_KEY`. The free Claude.ai plan does not include Claude Code.

## Installation

From the JetBrains Marketplace:

1. **Settings ▸ Plugins ▸ Marketplace**
2. Search for **Claude Code Native**
3. Install, then restart the IDE

Or install a signed archive by hand from the
[GitHub releases](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains/releases):
**Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk**.

The tool window appears on the right, next to where AI Assistant lives.

### The plugin installs the `claude` CLI for you

You do not need to install it beforehand. If it is missing, the plugin's first screen detects your OS
and distribution, offers the official route, and runs it in the IDE terminal on one click. These are
the commands it uses, if you would rather run them yourself:

```bash
# macOS, Linux, WSL
curl -fsSL https://claude.ai/install.sh | bash

# macOS, with Homebrew
brew install --cask claude-code
```

```powershell
# Windows, PowerShell
irm https://claude.ai/install.ps1 | iex

# Windows, with WinGet
winget install Anthropic.ClaudeCode
```

On Debian/Ubuntu, Fedora/RHEL and Alpine the card also offers Anthropic's signed `apt`, `dnf` and
`apk` repositories, detected from the running distribution. Verify with `claude --version`.

## First run

Open the **Claude Code** tool window. What you see first depends on what the plugin finds, and it is
re-checked every few seconds while no session is running — installing the binary or signing in
elsewhere takes effect without closing the tab.

- **"Claude Code was not found"** — the binary is not installed, or not anywhere the plugin looks. The
  card lists the official install commands for your OS (readable before you run them, because
  corporate networks block installers) and has a field to point at an existing binary.
- **Sign in** — no credential is held yet. One button opens your browser; the binary itself captures
  the callback. If your browser shows you a code instead of returning automatically, paste it into the
  same card. There is also a field for an `ANTHROPIC_API_KEY`, and a skip button that consents to
  riding your terminal's own `claude` login for the session.
- **Loading** — the binary is starting. You can switch to another chat while it does.

### Where your credential lives

Your sign-in is kept in the **IDE's password safe**, which resolves to whatever you have configured it
to use: the OS keychain by default (KWallet / GNOME Keyring on Linux, Keychain on macOS, DPAPI on
Windows), or the IDE's own encrypted file.

- `claude auth login` writes `~/.claude/.credentials.json` in plaintext. The plugin **harvests that
  file into the safe and deletes it**, including a login you made in your own terminal.
- **Nothing ever writes it back.** The credential reaches the binary as an environment variable,
  never on a command line, never in a log or the transcript.
- Access tokens expire in hours; the refresh token is good for weeks. The plugin renews silently at
  launch using the binary's own non-interactive refresh path — no browser, no prompt. The plugin holds
  no OAuth client and calls no token endpoint itself.
- **Log out** clears only what the plugin holds. Your terminal `claude` login is left alone.

Your **settings** live in the same safe, as **one document per IDE installation, per project**. Two
repositories can disagree about the model, the permission mode or a security rule, and two IDEs on one
checkout keep their own. What stays global is what a credential is: the sign-in, the account, the
per-provider API keys and the Git host tokens.

Nothing is lost on upgrade. Before 5.5.0 settings sat in `.idea/claude-code.xml` — per project, in the
clear, and committable, environment block included; between 5.5.0 and 5.7.0 they were one global
document. Both are read as a seed, so a project with no settings of its own starts from what you
already had, and only diverges once you change something in it. The old project file is removed only
after the safe confirms it holds the copy; the global document is never removed, because it is what
every project opened from now on inherits.

Moving between IDEs is a gesture rather than magic: **Settings ▸ Claude Code ▸ Transfer** exports and
imports a file, and migrates straight from another JetBrains IDE on the same machine.

## User guide

### The chat

Each chat tab is an independent session with its own `claude` process. Type in the composer and press
`Enter`. Replies stream in token by token; tool calls appear as collapsible cards that colour by state
(in flight, finished, failed) and show elapsed time. A `Bash`/PowerShell/MCP-exec call renders the
exact command as its own copyable code block, visible without expanding the card.

You can keep typing while a turn is running: follow-ups go into a visible queue and are sent in order.
Reasoning ("Thought process") is collapsed by default.

#### Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Enter` | Send |
| `Shift+Enter` | New line |
| `Shift+Tab` | Cycle permission mode (Ask each time → Accept edits → Plan) |
| `Tab` (empty composer) | Put the suggested next prompt into the field — it is not sent, you still press `Enter` |
| `Esc` | Close an open chip menu; otherwise interrupt the running turn |
| `Ctrl/Cmd+F` | Find in transcript (`Enter` / `Shift+Enter` walk the hits, `Esc` closes) |
| `Ctrl/Cmd+O` | Collapse / expand all reasoning |
| `/` (empty composer) | Slash-command palette |

#### The composer bar

Along the bottom: **provider · model · permission mode · effort · thinking** chips, all changeable
mid-conversation. Model and mode take effect immediately; toggling extended thinking restarts the
session behind the scenes with `--resume`, so nothing is lost.

**Attach files** sits to their left. On the right: **Auto-scroll (follow output)**, **Vibe Mode**, and
**Send** (which becomes **Stop** during a turn).

The model list is read from the binary's own handshake and each entry shows its real version, so new
tiers appear on their own — nothing is hardcoded. Older generations sit in a collapsed **Other
models** group. Effort runs `low · medium · high · xhigh · max`, defaulting to **high**.

#### Attachments and context

The attach button offers files, a directory, an image, the current selection, the open file, and a
filterable list of recently-opened files. You can also **drag an image in or paste one** — including
on native-Wayland desktops, where the plugin reads the system clipboard host-side because the embedded
browser cannot.

From the editor, right-click gives you **Explain with Claude**, **Add Selection to Claude Context** and
**Add File to Claude Context**.

Paths, directories and symbols in Claude's replies become links **only once the IDE has confirmed they
exist**, so a link never dead-ends. Clicking one opens the file at the line, or reveals a directory.

### When Claude wants to change a file

Nothing is written without you seeing it. On the permission request the proposal opens as an
**editable diff tab** in the editor — Current | Proposed — with an inline **Accept / Reject** card in
the chat. Never a modal dialog.

- **Edit the proposed side before accepting.** What gets written is your edited version.
- **Accept or reject the change as a whole.** Per-hunk selection was removed in 4.0.5 because
  accepting an incoherent subset of an edit produced code that did not hold together.
- The diff closes on accept, reject, stop or interrupt.
- **View diff** on any past tool card reopens what that call actually wrote, at any time.
- On acceptance **the binary writes the file**, and the IDE refreshes that exact path immediately
  (plus a tree rescan after `Bash` or a mutating MCP tool, which may have touched anything).

**Undo.** Every completed Edit/Write/MultiEdit card carries a **Restore**, which asks Claude Code to
rewind the files to the turn that made that edit (probed with a dry run first); if the binary cannot,
the plugin offers to revert them itself from its own pre-write snapshot, with a confirmation you can
tell it to remember.

Reverting a write that *created* a file removes that file, which is the only way to undo a creation.

To see everything a long run touched rather than one edit at a time, use ⚙ ▸ **Review This Session's
Changes…**, which diffs the whole session against its base. Undoing a *commit* is [Git](#git), below.

#### Permission modes

The mode chip decides how often you are asked:

| Mode | Behaviour |
|---|---|
| **Ask each time** (default) | A card for every tool call |
| **Accept edits** | File edits auto-approved; the diff still opens so you can see it |
| **Plan** | Claude proposes a plan and waits for you before doing anything |
| **Bypass permissions** | No cards, except where the security lock demands one |
| **Don't ask** · **Auto** | The binary's own additional modes, available from the chip menu |

`Shift+Tab` cycles the first three, matching the CLI. Whatever the mode, the
[security lock](#security) is evaluated **first** and cannot be switched off — at most, a rule you
disable in Settings turns an automatic block into a card you must answer.

Other request types render inline too: **AskUserQuestion** as option cards with wrapped labels and
descriptions, and **MCP elicitation** as a form built from the server's schema (a URL flow is gated to
`http`/`https`, so an untrusted server cannot reach `file:` or `javascript:`).

### Agents, subtabs and Workloads

When Claude spawns agents, **each gets its own tab and its own transcript**, so its thinking and tool
calls stay out of the main conversation. Before 5.5.0 a session running dozens of agents put all of it
in one transcript, interleaved and unfollowable.

- The bar under the chat tabs shows which transcript you are reading.
- Resting on a chat's tab for a second — or clicking its `⋮` — opens the whole tree at once: agents,
  their agents, and the background tasks each of them started. Clicking any row goes there.
- **A finished agent keeps its tab**, marked finished. Reading why something failed is the point.
- **Closing a subtab hides a view; it destroys nothing.** The card that spawned it opens it again.
- **Pin** turns the subtab you are reading into a tab of its own, next to the chats.

**Workloads** — one of the view buttons in the tab bar — draws everything running across *every* open
chat as one diagram: chats at the root, agents beneath them, tasks under whoever started them. Every node
is somewhere you can go, and a running task can be stopped from there.

### Background tasks

The binary stops listing a background task the moment it ends — which is exactly when its output is
worth reading. So the plugin keeps its own record: the task, its command and its output survive the
task's death, are tailed live from the file the binary writes, and are rebuilt from the session
transcript after an IDE restart.

### The dashboard and your plan limits

The tab bar carries the dashboard's view buttons: **Chat** (the way back out), **Session**, **Workloads**,
and — only while the session has that surface to show — **Git** and **Plan**. One view at a time; the
button that is lit is where you are.

The **Session** view shows what the current session is doing and costing: the context breakdown by
category, token usage and cost (input / output / cache read / cache write, in USD when the binary
reports it), your plan's limit windows with the time left on each, the account you are signed in as
(email / organisation / plan / provider), the active model, the working directory, the binary version,
and MCP server health with per-server reconnect and enable/disable.

**Plan** is the plan-mode document, on its own rather than as a card among the numbers — prose you go
back and re-read while working. Its button appearing is also how you learn one has been written.

Your plan limits also sit as small labelled bars under the composer, so you can see them without
opening anything: **blue below 65%, amber below 85%, red at or above**. They refresh every 30 seconds
whether or not the chat is on screen — a window can reset, or fill up from another device, while you
are looking elsewhere.

Above them, a status line always carries the same session's numbers: running or idle, context used,
tokens out, the live reasoning-token estimate, and the cost in USD once there is any. In the transcript,
a collapsible "Recalled N memories" row names which memories (scope · path · content) influenced a turn.

### Sessions

Chats **are** the binary's own sessions, stored in its own files, so they are the same conversations
you see from the terminal. From the tool window's gear menu:

- **Open Previous Session…** — every past chat for this project, by the title Claude gave it, reopened
  with its transcript via `--resume`.
- **Rename Session…** and **Fork Session** — fork branches the conversation into a new tab from the
  same history.
- **Session Info**, **Agents**, **Binary Version…**, **Effective Settings…**, **Add Current File as
  @-context**, **Settings…**.

Chats you had open are restored when the IDE starts (switchable in Settings). **The plugin stores no
transcripts of its own** — only which tabs were open.

**The plugin never deletes your conversations.** There is deliberately no "delete session" action. This
is pinned by a source contract (`NoFileDeletionContractTest`), written after an earlier release
destroyed a user's history: **recursive deletion is banned outright anywhere in the codebase**, and a
single-file deletion is allowed only in the handful of source files that contract names, each for one
purpose — and every file any of them removes is one the plugin itself wrote:

- `~/.claude/.credentials.json`, once it has been harvested into the keychain — that removal *is* the
  feature;
- the plugin's own superseded settings and bookkeeping files, after their contents have been adopted and
  the new location has confirmed the write: `.idea/claude-code.xml` and
  `~/.claude/ide/claude-code-native/settings.json`.

Nothing else in the plugin can call a delete at all; the build fails if it tries.

The one other thing the plugin can remove is a file that an `Edit`/`Write` *created*, and only when you
press **Revert** on it — undoing a creation means removing it, not leaving a zero-byte husk. Nothing else
on your disk is ever removed, and nothing you authored is.

A chat that needs you while you are looking elsewhere — a permission, a finished turn, an error —
raises a notification and badges its tab. Suppressed for the chat already on screen.

### Git

The **Git** button in the tool window's title bar opens a chat dedicated to the integration, and with it
the dashboard's **Git** view: where `HEAD` is, what can be done to the repository, and its recent history.
Entries are there only when the IDE's Git plugin is enabled, and each one hides itself when it does not
apply — absent rather than greyed out, re-derived every time the menu opens, so creating a repository or
enabling the Git plugin takes effect without reopening anything.

**Reading** is three gear entries, all of which hand off to the IDE's own Git UI rather than drawing
another one:

- **Recent Commits on `<branch>`…** — the label names the branch you have checked out, so the menu itself
  answers "which branch is Claude working on". Opening it lists the last 20 commits of the repository your
  project lives in, one line each: short hash, subject, author, age, and how many files it touched.
  Choosing one opens the IDE's Git Log.
- **Git History for the Current File** — hands the file in the active editor to the IDE's own file-history
  view. Only for a file inside the project: anything outside it is refused, by the same canonical,
  symlink-resolving check the write path uses.
- **Open Git Log** — brings up the IDE's Version Control tool window.

The package behind all three is **read-only, and it is the code that says so**: no ref moves, no history
rewriting, no remote traffic, and it never runs `git` itself. A source contract
(`GitReadOnlyContractTest`) enforces that — an allowlist of four read-only APIs, plus a scan for the
symbols that would mean it had grown its own way to execute Git. Adding a write path fails the build.

**Changing the repository** is offered three different ways, and which way an action gets is the design:

- **Claude does it.** *Commit with Claude* and *Revert this file with Claude* — in the Git view, and in
  the gear menu as **Commit Changes with Claude** and **Revert This File with Claude** — run no `git`.
  Each puts a bounded prompt into the Git chat and lets Claude do the work, so the command is on screen in
  an approval card before it runs and you can answer the tab ("squash those two", "not that file")
  instead of getting one shot at a button. That tab's turns are **always approved by hand**, whatever
  permission mode you are in and whatever you have marked "Always allow": the plugin started the turn, so
  it does not inherit permissions you granted for your own work.
- **The IDE does it.** Branches, pull, fetch, push, merge, rebase, stash, unstash and the commit dialog
  are under ⚙ ▸ **Git Operations**, and those entries *are* the IDE's own actions — same dialogs, same
  shortcuts, same enablement. They are there because the IDE does them better than a chat card would, and
  reimplementing them would only make them worse.
- **The plugin does it, once.** *Initialize repository*, offered in the Git view on a project that is not
  a repository yet, runs `git init -b main` itself. It is the only `git` this plugin ever runs: a fixed
  argument vector with no shell involved and nothing of yours in it, deliberately outside the read-only
  package. `-b main` rather than a bare `git init`, which still lands on `master` unless you have set
  `init.defaultBranch`. Being the plugin spawning a process rather than Claude asking for a tool,
  **the [sensitive-data lock](#security) does not see it**: that guard sits on the tool requests the
  binary makes, and this is not one. So the exception is exactly one command, on an empty directory,
  behind a menu entry that only appears where there is no repository to damage.

Those two facts do not contradict each other: the read-only contract is a claim about the `git/` package,
and it still holds — the one direct execution lives in `ui/`, outside it, on purpose. No gate was
bypassed.

The plugin builds no Git UI of its own — the commit list is a picker, not a viewer, and everything you act
on is the platform's own Git Log, in your theme and with your shortcuts. Nothing here is sent to Claude
unless you pick an action that asks it something.

### Settings that matter

**Settings ▸ Claude Code** (one page, grouped by subject):

| Setting | Default | Why you would change it |
|---|---|---|
| Model · permission mode · effort · thinking | top Opus tier · Ask each time · high · adaptive on | The launch defaults for every new chat |
| **claude executable path** | auto-detect | A non-standard install, or a GUI IDE that does not inherit your `PATH` |
| **Provider** | Anthropic | DeepSeek's Anthropic-compatible endpoint. Each provider's key is stored separately in the safe; an `sk-ant-` key is rejected in a third-party slot so your subscription can never leak to another endpoint |
| **Sensitive Guard** | every rule Enforcing | Its own page since 5.7.0 — **Settings ▸ Claude Code Security**: a mode for the guard as a whole, a mode per rule grouped by category, the three whitelists, and the extra credential globs and blocked domains. See [Security](#security) |
| **Restore open chats on startup** | on | Start with a single empty chat instead |
| **Allowed / disallowed tools**, **Always-allowed tools** | empty | Stop being asked about a tool; revocable here. This one list stays shared by every project — most settings are per project since 5.7.0, but a remembered tool approval is about the tool, not the repository. The Sensitive Guard still decides first: nothing here bypasses it |
| **Environment variables**, **Source script** | empty | Seed the binary's environment. The source script is *executed* at session start, so it — and any custom `stdio` MCP server — is gated behind a per-project trust prompt the first time |
| **Reduce motion** | off | Flatten the chat's animations |
| **Advanced launch** | flags omitted | `--max-turns`, `--max-budget-usd`, `--fallback-model`, extra `--add-dir` roots, beta flags, strict MCP config |
| **IDE tools (MCP)** | off | Below |

### IDE tools (MCP) — optional, off by default

Let Claude query the IDE directly (diagnostics, open files, usages, …) through JetBrains' own MCP
server. Two steps:

1. **Enable JetBrains' MCP Server plugin** (Settings ▸ Plugins) and confirm it is running.
2. In **Settings ▸ Claude Code**, tick *Enable JetBrains MCP server*, pick the **transport**
   (`sse` by default, or `streamable-http` / `stdio`) and the **port** if you changed it from `64342`.
   Apply, then start a **new chat** — the setting is applied when the `claude` process launches.

You can also register **custom MCP servers** as a JSON object of `name → server`; both are merged into
a single `--mcp-config`. Invalid JSON blocks saving.

> **Security.** `sse` and `streamable-http` use JetBrains' localhost endpoint, which any process on
> your machine can reach; `stdio` launches a helper process instead. Enable only on a machine you
> trust. Every IDE tool call is still gated by the permission card *and* by the
> [sensitive-data lock](#security) — and MCP servers are third-party callers there, so a credential
> hit from one is denied outright.

## Security

The plugin ships a **deterministic sensitive-data lock** (`permission/SensitiveGuard`). It is not a
model-side guardrail: the classification is out-of-band Kotlin with no model input, evaluated in
`PermissionBroker.handle` **before any auto-approval branch**. There is no prompt that argues it into
a yes.

The permission mode you pick is the *plugin's*, never the binary's — `acceptEdits` and
`bypassPermissions` are translated to `default` on the command line, so every call still arrives as a
control request and the verdict stays the plugin's to make. Auto-approval is something the plugin then
chooses to do, which is what lets the lock hold in the modes whose whole point is not being asked.

**What it classifies**

| Category | Examples |
|---|---|
| Credential / key material | SSH and GPG keys, cloud and cluster credentials, database and shell-history secrets, browser and password-manager stores, crypto wallets, AI-agent and code-host tokens |
| Dangerous commands | Credential dumps, file exfiltration, network-piped-to-shell, LOLBINs, recognised offensive tooling |
| Foreign territory | Another user's home, UNC / network mounts, non-`/mnt/c` WSL drives |

Patterns are **structural**, so one rule covers Linux, macOS, Windows (`C:\Users\…\.ssh`) and WSL
(`/mnt/c/Users/…`). The whole input object is walked for path-like values — not a fixed key list — so
an MCP tool naming its argument `target` or `destination` is still covered. Paths are canonicalised on
disk (symlinks, `..`) and commands go through a de-obfuscation stage (broken quotes, `$IFS`, variable
substitution, base64 payloads) before matching.

**How it decides** — by trust of the caller, as an allowlist:

- the agent's **own tools** → an explicit permission card, **every time**, in every mode;
- **MCP servers and Skills** → denied outright; third-party code has no business reading your keys;
- **foreign territory** → denied for every caller, trusted or not.

**Per-rule switches** (Settings ▸ Claude Code Security, its own entry in the settings tree, kept per
project). Every rule can be turned off independently, and so can a whole category at once — all **on** by
default. Turning one off is never a silent allow: detection still runs, and a hit is only *downgraded* from
an automatic deny to a permission card, shown every time, to every caller. Every card names the rule and the
Settings path.

**One switch above all of them**: a shield in the chat's button row, and the same control on that page,
turns the guard off for a chosen duration — 5 minutes up to *Forever*, five of the seven choices expiring on
their own. It is **on** by default, the shield is unlit whenever it is not, and while it is off the guard
evaluates nothing at all.

**Whitelisting a command** is the narrow alternative to switching a rule off: an exact command, matched whole
and de-obfuscated on both sides, at one of three reaches — that rule, that category, or everywhere. Any rule
can be whitelisted, and a blocked call offers a **Whitelist Command** link that files the command under the
rule that stopped it.

The built-in sensitive-path list is additive only by construction: it can be widened with extra globs and
can never be shrunk. Paths under the project root are exempt from both the credential and
the foreign rules — your repository is the sanctioned zone — and your own home is exempt from the
foreign rule alone, so the credential globs still cover it. Dangerous-command classification is
location-independent. A session refuses to start at all when the project itself sits on a remote or
network-mounted path.

Detecting a path concealed inside an arbitrary shell string is best-effort and gets widened over time;
the **enforcement** of a match is absolute. Separately, jump-to-code links can only ever open inside
the project or your own home (canonical, symlink-safe), while the **write** gate stays project-only.

The threat model is written down in [ADR 0002](docs/adr/0002-threat-model.md), including what it does
*not* defend against: **prompt injection is assumed to succeed, not detected**, which is precisely why
the lock judges the tool call and never the model's reasoning. Full model and reporting policy in
[`SECURITY.md`](SECURITY.md).

**Telemetry: none.** The plugin collects nothing about you and sends nothing to us — there is no
analytics endpoint, no crash reporter and no usage counter. Your conversation goes from the `claude`
binary to Anthropic over the same channel it already uses in your terminal. The only other network
traffic the plugin makes is optional and goes to **your** forge: give it a GitHub or GitLab token and
it asks that server about the branch you are on, to show you your own pull requests and CI status.
None of that reaches us either.

## Troubleshooting

| Symptom | Usually |
|---|---|
| The chat never loads, or the tool window is blank | The embedded browser (JCEF) is unavailable. Below build **253.29346.138** — so on 2025.1, 2025.2 and 2025.3.0 (`253.28294.334`) — this version does not run at all; see [Requirements](#requirements). Otherwise check the `ide.browser.jcef.enabled` registry key |
| "Claude Code was not found" with the binary installed | It is somewhere the plugin does not look, or the IDE did not inherit your `PATH`. Paste the full path into the card, or set it in Settings |
| Signed out again after a restart | The stored credential could not be renewed. Sign in again from the card, and check the IDE can reach your keychain |
| A tool call is refused with no card to override it | The [security lock](#security) blocked it. The message names the rule and the Settings path; foreign-territory blocks are absolute by design |
| A chat is empty after reopening it | The session file is gone from `~/.claude/projects/…`, or the working directory changed. The plugin keeps no transcripts of its own |
| The agent seems stuck | `Esc` interrupts the turn. If a tool card sits running forever, its agent's tab shows what it was actually doing |
| Leftover diff tabs | They are real editor tabs, not modals. **Close All Diffs** in the Claude Code tool window's title bar closes every one the plugin opened |

Deeper cases, with log locations and commands:
[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) and [`docs/FAQ.md`](docs/FAQ.md).

Bugs and features: open an issue with the templates in
[`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE). Vulnerabilities: [`SECURITY.md`](SECURITY.md).

## Build from source

Requires **JDK 21** — the Gradle toolchain is pinned to it, because the IDE runs on JBR 21. The Gradle
wrapper is included.

```bash
JAVA_HOME=/path/to/a/jdk-21 ./gradlew buildPlugin
# → build/distributions/claude-code-native-5.7.1.zip
```

Install it with **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk**.

```bash
./gradlew runIde          # sandbox IDE with the plugin loaded
./gradlew test            # unit + headless + integration (JVM)
./gradlew koverVerify     # coverage gates (blocking in CI)
./gradlew detekt spotlessCheck
./gradlew verifyPlugin    # IntelliJ plugin verifier across the declared range
./gradlew checkDrift      # protocol drift vs. the latest SDK + binary
npm test                  # frontend suite (vitest + jsdom)
npm run lint && npm run format:check
npm audit --omit=dev      # the distributed scope; must be clean
```

`checkDrift` needs a real `claude` binary and looks in `~/.local/bin` by default — point it elsewhere
with `-PclaudeBinary=/usr/bin/claude` (or the `CLAUDE_BINARY` environment variable). It is **not**
wired into `check`: it updates the SDK and the binary to latest, which is a deliberate act, not a side
effect of running the tests.

`verifyPlugin` can run **fully offline** against locally extracted IDEs:

```bash
./gradlew verifyPlugin -PlocalIdePath=/path/to/idea-A,/path/to/idea-B
```

### Testing

The suite is a real pyramid:

- **unit** (pure JVM) — protocol parse/build, diff reconstruction, the exhaustive `PermissionBroker`
  and `SensitiveGuard` matrices, hunk encode, path-traversal guards, settings enums;
- **headless component** — `BasePlatformTestCase` in-process, for the project services and settings UI;
- **integration** — a real `ClaudeSession` driven against the deterministic `bin/fake-claude` stand-in
  with JSONL fixtures;
- **UI end-to-end** — RemoteRobot against a real IDE, gated behind `-PuiTest.enabled=true` (see
  [`docs/UI_TESTING.md`](docs/UI_TESTING.md));
- **frontend** — vitest + jsdom loading the real inlined `src/main/resources/jcef/*.js`, including a
  JS↔CSS class contract and an accessibility contract.

CI has no `push` trigger — deliberately, so one commit does not pay for two identical pipelines; the pull
request is the door, and a branch with no pull request gets no checks. The gate is **not uniform**: a pull
request into `develop` runs the JVM suite (with `koverVerify`) and the frontend suite; the expensive half —
static analysis, `npm audit --omit=dev`, `verifyPlugin` and the artifact assertions — runs at the
`develop → main` door, which is the merge that publishes. The UI end-to-end suite answers only to a nightly
schedule and a manual dispatch, and is never a required check. CodeQL runs on pushes to both branches as
well as on pull requests, and both CodeQL and the protocol-drift check run weekly.

## How it works

The plugin speaks **directly with the `claude` binary** over its `stream-json` + control stdio
protocol — no Node.js and no TypeScript SDK at runtime. One long-lived process per chat handles
streaming input and output; `can_use_tool` control requests are answered by the plugin, so **the binary
writes the file** only after your approval.

Nothing is mirrored from terminal output. Every state — compaction, cost, hooks, subagents, MCP health
— is reconstructed natively from the protocol's structured fields.

The TypeScript SDK package under `node_modules/` is kept as a **protocol reference only** and is never
distributed. `./gradlew checkDrift` updates the SDK and binary to latest and reports any protocol kind
the plugin does not model yet.

Architecture, protocol details and the empirically verified facts about the binary's behaviour are in
[`CLAUDE.md`](CLAUDE.md); where each thing lives is in [`PROJECTMAP.md`](PROJECTMAP.md).

## What's new

**5.5.0** — a tab and a transcript per agent, with the whole tree one hover away; a single **Workloads**
diagram of everything running across every chat; background tasks that keep their output after they
end and survive a restart; a [Git integration](#git) whose write actions are asked of Claude rather than
run by the plugin; settings moved into the IDE's password safe. It also **fixes a plugin that was dead on
2026.2**, which is why the minimum IDE is now 2025.3.1.

**5.1.x** — per-model plan-limit windows (the ones the CLI's `/usage` showed and the plugin did not),
moved to their own row under the composer; older model generations selectable again behind an *Other
models* group.

**5.0.0** — the standards-compliance major: a screen-reader live region and a visible focus ring
throughout, a written [threat model](docs/adr/0002-threat-model.md), third-party licence attribution
shipped inside the artifact, and the plan-limits panel.

Full history in [`CHANGELOG.md`](CHANGELOG.md); user-facing notes per release in
[`RELEASE_NOTES.md`](RELEASE_NOTES.md).

## Documentation

Using the plugin is covered above. Everything below is for working *on* it.

| Document | What it covers |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Architecture, protocol, empirical binary behaviour |
| [`PROJECTMAP.md`](PROJECTMAP.md) | Where things live — the "I want to change X → go to Y" index |
| [`AGENTS.md`](AGENTS.md) | Runbook for working on this repo with a coding agent |
| [`SECURITY.md`](SECURITY.md) | The sensitive-data lock, triage scope, reporting policy |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute |
| [`docs/adr/`](docs/adr/README.md) | Decision records — release process, threat model, i18n deferral |
| [`docs/FAQ.md`](docs/FAQ.md) · [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Common questions and fixes |
| [`docs/BINARY_COMPAT.md`](docs/BINARY_COMPAT.md) · [`docs/DRIFT_DETECTION.md`](docs/DRIFT_DETECTION.md) | Binary compatibility policy and drift detection |
| [`docs/RELEASE_PROCEDURE.md`](docs/RELEASE_PROCEDURE.md) · [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) · [`docs/BRANCHING.md`](docs/BRANCHING.md) | Release and branching workflow |
| [`docs/CI_SETUP.md`](docs/CI_SETUP.md) · [`docs/UI_TESTING.md`](docs/UI_TESTING.md) | CI/CD configuration and the RemoteRobot harness |

## Upstream and forks

**This repository is upstream.** It is not a fork of anything, and the claim is checkable rather than
asserted — GitHub records a repository's ancestry, and for this one it is empty:

```sh
gh repo view serialexperimentslainnnn/claude-code-for-jetbrains --json isFork,parent
# {"isFork":false,"parent":null}
```

The other anchors point at the same place: the Marketplace listing
([plugin 31965](https://plugins.jetbrains.com/plugin/31965-claude-code-native)) is published from this
repository by its author, every release tag here is cut by the release workflow and the artifacts are
signed, and the commits carry the maintainer's signature.

### Known forks

The GPL exists so that people can fork, study and modify this. Nothing below is a complaint — it is
simply a map, so that anyone who lands on a copy knows where the original is and can compare.

| Fork | Owner | Last seen active |
|---|---|---|
| [luxgoldix-coder/claude-code-for-jetbrains](https://github.com/luxgoldix-coder/claude-code-for-jetbrains) | luxgoldix-coder | 2026-08-10 |

*List reviewed 2026-08-13. It is maintained by hand and may lag; the live set is always*
`gh api repos/serialexperimentslainnnn/claude-code-for-jetbrains/forks --jq '.[].full_name'`.

### If you fork it

Please do — and two asks, the first of which the licence already requires of you:

1. **Say that it is modified, and by whom.** GPL-3.0 §5(a) requires a modified version to carry
   prominent notices stating that you changed it and when. In practice that means editing this README,
   the plugin description and the plugin `id` so a user can tell the two apart.
2. **Use your own plugin id and your own signing key** before publishing anywhere. Two artifacts
   claiming `dev.lain.claude-code-for-jetbrains` cannot coexist in a user's IDE, and a release signed
   with this project's key would misattribute your work to this project — and this project's bugs
   to you.

Neither ask restricts what the licence grants you. They exist so that a user can always answer "whose
build am I running, and where do I report this?".

## Licence and attribution

Licensed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE).

The published archive redistributes third-party components (marked, DOMPurify, highlight.js,
kotlinx.serialization). Their notices are in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), with the
full licence texts under [`LICENSES/`](LICENSES) — each entry verified against the upstream `LICENSE` of
the exact version that ships, not against a manifest or a minified file's banner.

All of it is packaged **inside** the artifact, because a notice sitting in a Git repository does not
accompany the binary a user installs. Both halves of that are enforced rather than promised: the
*Build plugin* job in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) unpacks the very zip the
plugin verifier passed and fails the build unless the jar carries `META-INF/LICENSE`,
`META-INF/THIRD-PARTY-NOTICES.md` and one `META-INF/licenses/…` text for **every** file under
`LICENSES/` — the expected set is read from the checkout, so adding a dependency's licence text extends
the check by itself. The same job fails if the zip contains a single `node_modules` entry, which is what
turns "no npm code is distributed" from a claim into a check.

## Disclaimer

Unofficial, community-built, open-source plugin. **Not affiliated with, sponsored by, or endorsed by
Anthropic or JetBrains.** It requires your own separately-installed `claude` CLI and your own Claude
subscription or API key — no credentials are bundled or provided.

"Claude" and "Claude Code" are trademarks of Anthropic; "JetBrains", "IntelliJ", "PyCharm" and related
names are trademarks of JetBrains s.r.o. Used here for identification only.
