# Claude Code Native

[![Version](https://img.shields.io/badge/version-6.0.0-E07B5A)](CHANGELOG.md)
[![IDE](https://img.shields.io/badge/JetBrains-2025.3.1%20%E2%86%92%20263.*-000000?logo=jetbrains)](#requirements)
[![Marketplace](https://img.shields.io/badge/Marketplace-Claude%20Code%20Native-2A2A2A)](https://plugins.jetbrains.com/plugin/31965-claude-code-native)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Claude Code, living inside your JetBrains IDE — with hands.** This plugin runs your own `claude` CLI in
a native chat and hands the agent the IDE itself: it opens files for you, takes you to a line, shows you a
commit in the Log or a diff in the diff viewer, finds you a pull request and puts it on screen; it reads
through the index, edits through the document model, refactors with the refactoring engine, builds, runs,
tests and debugs through the run system, drives Git and GitHub through the IDE's own account and views,
works the Services panel, and can fire any menu entry the IDE registers. Everything is shown to you as it
happens, without ever taking your focus, and every call passes a deterministic security guard first.

Unofficial, community-built, open source. Not affiliated with, sponsored by, or endorsed by Anthropic or
JetBrains. It needs your own `claude` CLI and your own Claude subscription or API key; nothing is bundled.

## Contents

- [Requirements](#requirements) · [Installation](#installation) · [First run](#first-run)
- [What you can ask for](#what-you-can-ask-for) — the manual
  - [Open it, show me, take me there](#open-it-show-me-take-me-there)
  - [Ask about what is on screen](#ask-about-what-is-on-screen)
  - [Read and navigate the code](#read-and-navigate-the-code)
  - [Edit, refactor, format](#edit-refactor-format)
  - [Diagnose and analyse](#diagnose-and-analyse)
  - [Build, run, test](#build-run-test) · [Debug](#debug) · [Run a command](#run-a-command)
  - [Git](#git) · [Pull requests and releases](#pull-requests-and-releases)
  - [Services, containers, databases, HTTP, SSH](#services-containers-databases-http-ssh)
  - [Drive the IDE itself](#drive-the-ide-itself) · [Leave marks for me](#leave-marks-for-me)
  - [The IDE's internals](#the-ides-internals)
- [How it shows up in the IDE](#how-it-shows-up-in-the-ide)
- [The chat](#the-chat)
- [Security](#security)
- [Settings that matter](#settings-that-matter)
- [Any MCP client can drive the IDE](#any-mcp-client-can-drive-the-ide)
- [Troubleshooting](#troubleshooting) · [Build from source](#build-from-source) · [Documentation](#documentation)

## Requirements

**A JetBrains IDE on 2025.3.1 or newer** (`sinceBuild 253.29346.138`, `untilBuild 263.*`): IntelliJ IDEA,
PyCharm, WebStorm, PhpStorm, GoLand, RubyMine, CLion, Rider, DataGrip, DataSpell, Aqua, RustRover. The floor
is hard: the chat is the IDE's embedded browser (JCEF), which from build 262 is a bundled plugin the plugin
must declare, and that module id first exists in 2025.3.1. On 2025.1, 2025.2 or 2025.3.0 stay on plugin
5.1.1, or update the IDE.

**The `claude` CLI.** You do not have to install it yourself: if the plugin cannot find it, its first screen
offers the official install route for your OS and runs it in the IDE terminal. It looks first at **Settings
▸ Claude Code ▸ claude executable path**, then at the IDE's `PATH`, then at the usual places (`~/.local/bin`,
`~/.claude/local`, `/usr/local/bin`, `/opt/homebrew/bin`, `/usr/bin`; on Windows `%USERPROFILE%\.local\bin`,
`%APPDATA%\npm`, `%LOCALAPPDATA%\Programs\claude`, scoop, volta, Chocolatey).

**An account**: a paid Claude plan (Pro, Max, Team, Enterprise) or a Console account signed in through the
plugin, or an `ANTHROPIC_API_KEY`. The free Claude.ai plan does not include Claude Code.

Some capabilities depend on IDE plugins that are bundled but optional, and degrade to "not available"
without them: Git (Git4Idea), GitHub, Java (for UAST), IntelliLang, Database Tools, Terminal, Docker and
Kubernetes, SSH, Deployment, Qodana, Package Checker. Everything else needs nothing beyond the IDE.

## Installation

1. **Settings ▸ Plugins ▸ Marketplace**, search **Claude Code Native**, install, restart.
2. Or install a signed archive from the
   [GitHub releases](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains/releases) with
   **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk**.

The **Claude Code** tool window appears on the right.

If the CLI is missing, the first screen installs it on one click, with these commands should you prefer to
run them yourself:

```bash
curl -fsSL https://claude.ai/install.sh | bash      # macOS, Linux, WSL
brew install --cask claude-code                     # macOS, Homebrew
```

```powershell
irm https://claude.ai/install.ps1 | iex             # Windows
winget install Anthropic.ClaudeCode                 # Windows, WinGet
```

Debian/Ubuntu, Fedora/RHEL and Alpine also get Anthropic's signed `apt`, `dnf` and `apk` repositories,
detected from the running distribution.

## First run

Open the tool window. It shows one of three cards, re-checked every few seconds while no session runs:
**Claude Code was not found** (install, or point at an existing binary), **Sign in** (one button opens the
browser; a field takes an `ANTHROPIC_API_KEY`), or **Loading**.

Your sign-in lives in the **IDE's password safe** (the OS keychain by default). `claude auth login` writes
`~/.claude/.credentials.json` in plaintext; the plugin harvests it into the safe and deletes the file, and
the credential reaches the binary only as an environment variable — never a command line, a log or the
transcript. Settings live in the same safe, one document per IDE installation per project.

The IDE integration is **on by default** — the flame in the chat bar is lit: all four servers, every rule.
There is nothing to configure before you start asking.

## What you can ask for

This is the manual. Every chapter is a kind of request in your own words, what Claude does with it and what
appears on your screen. Claude reaches the IDE through **178 tools in 55 domains**, served by four MCP
servers the plugin runs inside the IDE — `code`, `run`, `vcs`, `ops` — over Unix sockets, with no port,
nothing to install and nothing exposed. The tool-by-tool reference is
[`docs/SKILL_INVENTORY.md`](docs/SKILL_INVENTORY.md); you never need it to use the plugin, because Claude is
told the rule of every tool on every turn and picks them itself. You ask in plain language.

Two things hold for the whole surface. **Every registered action of your IDE is reachable**: what has no
named tool is one action away, with a file, a commit or a Services node as its target, so a menu entry of a
plugin nobody here ever saw is still yours to ask for. And **everything is shown, nothing is stolen**: what
Claude opens for you lands in the IDE — the editor, the Log, the diff viewer, a tool window — while your
caret stays where it was and your Terminal keeps its tab.

### Open it, show me, take me there

> **"Open `SessionLauncher.kt` for me."** · **"Take me to line 120 of that file."** · **"Show me the last
> commit."** · **"Show me commit `d7351c4`."** · **"Show me the diff of this branch against `v5.7.0`."**
> · **"Show me the diff of my last change."** · **"Show me how this file looked on `main`."** · **"Find me the
> last pull request and open it."** · **"Open the Problems view."** · **"Open the Git Log."** · **"Open
> Settings at Editor ▸ Code Style."** · **"Show me `api` in Services."** · **"Open this folder in the file
> manager."** · **"Show me who wrote this."** · **"Show me the history of this file."** · **"Compare these
> two files."** · **"Show me the coverage."** · **"Open the request file `users.http`."** · **"Go back to
> where I was."**

This is the first thing the integration is for: **you tell Claude what you want to see and it appears in
the IDE**, in the right place, without you touching the mouse. A file opens in the editor at the line you
named, or in the italic preview tab if Claude is only reading it. A commit is selected in the Git Log
(opened first if it was closed) with its details and changes; a range of commits shows only that range.
A diff opens in the IDE's diff viewer: two files, a file against the active editor, a file against how it
was at any branch, tag or commit, the whole uncommitted work, or the clipboard against a file. A pull
request is found through the IDE's GitHub account and selected in the Pull Requests view — not a browser
tab. Blame turns the annotation gutter on; a file's history opens in its tab; Local History opens its view.
The Problems view, the Build, Run, Debug, Services, Terminal and any other tool window open on request,
Settings opens at the page you name, a Services node is revealed, a file is selected in the Project view,
a path opens in the file manager, the IDE terminal or its default application, coverage shows its window,
and the navigation history moves back and forward like the arrows in the toolbar. **Your focus never
moves**: the thing appears, you keep typing.

### Ask about what is on screen

> *"What file am I in?"* · *"What is under my caret?"* · *"What did I have open before this?"* · *"Which
> files did I change last?"* · *"Which run configurations does this project have?"* · *"Is the index
> ready?"* · *"What is running?"* · *"What is in the clipboard versus this file?"*

Claude knows where you are: the active file, the caret and the selection, the editor's recent and
recently-changed files, its tabs, the running processes, the run configurations, the indexing state, the
SDK, modules and dependencies of the project as Project Structure shows them, the installed plugins. So
"this file" and "here" mean what they mean to you. Questions about the project are answered from the IDE,
never from memory, and Claude says what it looked at.

### Read and navigate the code

> *"What does `SessionLauncher` do?"* · *"Where is `resolveHelper` called from?"* · *"Take me to the
> definition."* · *"Who implements `ToolGate`?"* · *"Show me the structure of this file."* · *"Find every
> `*Gateway.kt`."* · *"Who calls this, and who calls them?"* · *"Show me the docs of this symbol."*

Claude reads files **as the editor holds them**, unsaved edits included, and searches text, globs and
symbols through the IDE's index instead of scanning the disk. Symbols resolve as the IDE resolves them:
definition, references, implementations, the signature and documentation of what is under a position, the
Structure-view outline of a file, the call hierarchy three levels deep. Quick Documentation opens as the
popup you know. Anything it reads is put in the preview tab so you can follow along.

### Edit, refactor, format

> *"Rename `adopted` to `reconcile` everywhere."* · *"Extract these lines into a method."* · *"Move
> `GodMode.kt` to the settings package."* · *"Replace `foo` with `bar` across the project."* · *"Undo
> that."* · *"Reformat and optimise the imports."* · *"Expand the `main` template here."* · *"Create a
> Kotlin class from the file template."* · *"Inline this variable."* · *"Change the signature."*

Every edit goes through the IDE's document model — **one undo entry, saved, shown as a Before/After diff**
— so `Ctrl+Z` in the file works and Local History has it. Rename, move and safe-delete are the IDE's own
refactorings: every reference follows, a conflict refuses. The rest of the Refactor menu (introduce
variable/constant/field/parameter, extract method/interface/superclass/delegate, inline, change signature,
pull up, push down, move members, encapsulate fields) runs the IDE's refactoring at the position you name,
in place or with its dialog left open for you to finish. Undo and Redo go through the IDE's undo stack per
file; Replace in Files replaces across the project; the editor's line operations and the Code menu's
editing actions (override, implement, generate, surround, unwrap, comment, move statement or line,
rearrange, fold) run with the caret where you said; Reformat Code and Optimize Imports use the project's
code style and `.editorconfig`; live and file templates expand as `Tab` and *New* would. For structural
edits Claude can act on the **PSI tree** itself and let the IDE re-resolve and reformat the result.

### Diagnose and analyse

> *"Is this file clean?"* · *"Is the project clean?"* · *"What does Qodana say?"* · *"Run the inspections
> on this module."* · *"Clean up this package."* · *"What does this file depend on?"* · *"Where does this
> value come from?"* · *"Here is a stack trace, take me to it."* · *"Is this duplicated anywhere?"*
> · *"Which dependencies are vulnerable?"* · *"Where are the tests for this class?"*

Claude reads what the IDE's analysis shows — the highlights of a file with line, column, severity and
inspection; the whole Problems view; the Qodana, Vulnerable Dependencies and Security Analysis tabs — and
**checks that it introduced no problem before calling an edit done**. It runs inspections on demand (one
by id, or Code ▸ Inspect Code on a scope into the Inspection Results window), applies Code Cleanup, walks
file dependencies (backward too, in the IDE's analysis window), opens Analyze Data Flow at an expression,
resolves a pasted stack trace to your files and opens the Analyze Stack Trace console, locates duplicates,
infers nullity, and finds what is related to a symbol: its tests, its subject, its supers, its
implementations.

### Build, run, test

> *"Build the project."* · *"Recompile this file."* · *"Run the `Kotlin tests` configuration."* · *"Run
> the tests in this file."* · *"Run the test on this line."* · *"Run it with coverage."* · *"Run `Server`
> and show me its output."* · *"Stop it."* · *"Open the run configuration editor on `Server`."*
> · *"Attach the debugger to that process."*

Builds go through the Build menu — project, rebuild, module or one file — and come back with the
compiler's errors positioned, the Build window showing them. Run configurations start exactly as the Run
button starts them, before-launch tasks included, under run, debug, coverage or the profiler; their output
streams to the chat card and the Run window, and a long run answers `running` and is resumed rather than
blocking. Tests run through the IDE's test runner (a file, the test at a line, a named configuration) and
come back as pass/fail counts with each failure's message and frame, drawn in the IDE's test tree. A tool
the project has no configuration for gets one under `.idea/runConfigurations`, so what Claude runs is
something you can run too.

### Debug

> *"Put a breakpoint on line 85 and debug `ToolModelTest`."* · *"Step over."* · *"Step into."* · *"What
> is `arguments` here?"* · *"Evaluate `arguments.size`."* · *"Set `x` to 3 and resume."* · *"Show me the
> frames."* · *"Which breakpoints do I have?"* · *"Remove them all."*

Claude debugs with breakpoints instead of prints: line breakpoints with conditions (temporary if you like),
a configuration started under the debugger and waited for to the first stop, threads and frames, the
variables of a frame, expressions evaluated in the debuggee, values set, steps of every kind (over, into,
out, force into, smart into, run to, resume, pause, mute) and the next stop waited for. The Debug window
shows the execution point as it goes; the gutter shows the breakpoints.

### Run a command

> *"Run `git status`."* · *"Run the migration script."* · *"Run `npm test`."* · *"Tail the log."*

A command runs in the IDE's Terminal window, in a tab named **Claude**, and comes back with its exit code
and output; several commands go in one call. The tab is shown but never focused and never switched while
you are in the Terminal, and the output stays there when the tab is reused, so *View in terminal* on the
card lands on it. Claude's own `Bash` is retired while the IDE serves: a new process would cost a guard
pass and a permission, and the IDE already has a shell.

### Git

> *"What changed?"* · *"Show me the last five commits."* · *"Commit these two files."* · *"Create a branch
> from here."* · *"Push."* · *"Cherry-pick `a1b2c3d`."* · *"Revert that commit."* · *"Rebase this branch
> onto develop."* · *"Merge develop into this."* · *"Stash this, pop it later."* · *"Roll back this
> file."* · *"Make a patch of my changes."* · *"Add a worktree for `hotfix`."* · *"Open the merge
> dialog."*

Git is read as the IDE sees it — status with upstream ahead/behind, log, diff, branches — and written
through the IDE's Git: stage, commit (signed and hooked as your Git configures, the IDE answering any
prompt), branch, fetch, pull, push with the IDE's credentials. The **Log's commit menu** works on any hash
(cherry-pick, checkout, browse at revision, compare with local, reset, revert, undo, reword, fixup, squash,
drop, interactive rebase, push up to, new branch or tag, copy revision, open in browser): the commit is
selected in the Log and the action runs with the Log's own context, and anything that rewrites history
opens the IDE's dialog for you to finish. The **Branches popup** (merge, rebase, compare, diff with local,
rename, delete, checkout, checkout as new, new tag) goes through the IDE's branch machinery with its
progress and conflict resolution; so do worktrees and remotes. Uncommitted work: stash, the IDE's shelf,
patches, rollback. A file's past: blame with the gutter shown, history in its tab, Local History with labels
you can revert to, and its content at any ref diffed against the working tree. Every entry of the Git menu
and its GitHub and GitLab submenus opens by name for you to finish.

### Pull requests and releases

> *"Find me the PR."* · *"Show me the open pull requests."* · *"Open #74 in the IDE."* · *"Open a PR from
> this branch to develop."* · *"Comment on it."* · *"Is the CI green?"* · *"Merge it when it is."* · *"Is
> v6.0.0 tagged, released, and on the Marketplace?"*

Pull requests come **through the IDE's own GitHub account** — no `gh`, no token of its own — and are
shown in the IDE's Pull Requests view with the row selected. Claude lists them (open, closed, merged),
reads one with its branches, review decision and description, creates one from named branches, comments on
it, reads its mergeability and every check on its head commit polling until they settle, and merges it with
a merge commit only once the state is clean and no check failed — saying so first when the target branch
publishes on merge. It then verifies what a release left behind: the tags, the GitHub Actions runs of a
branch, the GitHub Release of a tag with its assets, and the plugin's versions on the JetBrains
Marketplace. This plugin's own releases are driven that way. GitLab merge requests live in the IDE's GitLab
view and actions.

### Services, containers, databases, HTTP, SSH

> *"Which containers are there?"* · *"Start `api` and show me its log."* · *"Stop the cluster."* · *"What
> can I do on this node?"* · *"Which data sources are configured?"* · *"What tables does `orders` have?"*
> · *"Run `select count(*) from users`."* · *"Run the requests in `users.http`."* · *"Which SSH hosts does
> the IDE know?"* · *"Open an SSH session to `staging`."* · *"Upload this to the deployment server."*

The **Services** window is the DevOps panel and Claude sees it as you do: the tree of every contributor
(Docker containers, images, networks, volumes, Kubernetes, run dashboards, database sessions, whatever your
plugins add), the actions the IDE offers on a node with whether each is enabled right now, and a node's
console text. It performs an action exactly as clicking it would, with the node selected, so the plugin's
own enablement decides — and reveals the node so you see what happened. Databases: the data sources, the
schema the IDE introspected, SQL over the IDE's connection. HTTP Client: the project's `.http` files run
through their run configuration with the response console, or opened in the editor. SSH hosts as the IDE
knows them (never the secret), SSH sessions, Deployment (upload, download, sync, compare, browse,
configure), Qodana and the Package Checker go through those plugins' own actions when they are installed.

### Drive the IDE itself

> *"Open the Problems view."* · *"Close the Terminal."* · *"Turn on presentation mode."* · *"Hide the
> navigation bar."* · *"Split this tab to the right."* · *"Pin this tab."* · *"Zoom the editor in."*
> · *"Show whitespace."* · *"Switch to the Darcula theme."* · *"Is the Kotlin plugin installed?"*
> · *"What does Code ▸ Analyze hold?"* · *"Run the action `ReformatCode` on this file."* · *"Generate
> the Javadoc."* · *"Open the Groovy console."* · *"Show me the bytecode of this Kotlin file."*

Claude can list every action your IDE registers with whether it is enabled in context, walk the main menu
as you see it, and dispatch any action by id on a file, a commit or a Services node. It opens and closes
tool windows, opens Settings at a page, lists the plugins, flips View ▸ Appearance (presentation,
distraction-free, full screen, zen, compact, the Presentation Assistant) and the interface parts (toolbar,
navigation bar, tool window bars, status bar, main menu), manages editor tabs and the tool window layout,
zooms the editor or the whole IDE, toggles line numbers, whitespace, soft wraps and gutter icons, keeps
bookmarks, moves through the navigation history, and lists or switches the theme, colour scheme, keymap
and code style. The Tools menu's generators (Javadoc, the command-line launcher, XML validation and schema
generation, Markdown import and export) and consoles (Groovy, Kotlin bytecode, Python) open as their
entries would.

### Leave marks for me

> *"Highlight the lines you are unsure about."* · *"Put a warning on line 40 with why."* · *"Ask me in
> the file which of the two I want."* · *"Bookmark the places you changed."* · *"Notify me when the build
> is done."* · *"Put it in a scratch file."*

Claude can point at code without editing it: highlighted, warning or error ranges, gutter icons with a
tooltip, inline hints, all in every editor of the file and all gone when the session ends. It can ask you
something **where you are reading** — a banner over the file's editor with the choices as buttons — put a
line in the status bar, open a scratch file that is never committed, set bookmarks on files and lines, and
raise a balloon in the IDE's notification area so you see it without reading the chat.

### The IDE's internals

> *"Show me the PSI tree of this function."* · *"What does UAST see at this line?"* · *"Which files does
> the `FilenameIndex` hold under this key?"* · *"Which languages are injected in this file?"* · *"List the
> source roots from the workspace model."*

For the rare request that needs the IDE's model as data: the PSI tree and the element at a position, the
UAST (the AST unified across Java, Kotlin, Scala and Groovy, on IDEs with the Java plugin), the file-based
and stub indexes by name, the injected language fragments of a file and a temporary injection at a
position, and the workspace model's modules, roots, libraries and SDKs, read-only.

## How it shows up in the IDE

**The mirror.** One switch, on by default — **Settings ▸ Claude Code ▸ Claude IDE Integration ▸ Mirror
Claude's work in the IDE**. What Claude reads opens in the preview tab, what it edits in a real tab, a
commit it names is selected in the Log, a service in Services, a problem in its tab, a run in its window; a
range it points at flashes; the focus stays where you left it. Where the platform steals focus anyway, the
plugin hands it back. Turn on **View ▸ Appearance ▸ Presentation Assistant** and every action Claude fires
is announced on screen.

**The cards.** Every call is a card in the chat: one per item when a list was passed, live lines for the
long ones, a diff and a Restore for edits, and a one-click link into the IDE — the commit, the Log, the
tool window, the terminal tab, the run, the problem, the diff. Results come back as compact tables (TOON),
which is what keeps the token bill low: the integration costs *less* than the native tools it replaces,
because a file read is one call where a `cat` was a process plus a permission.

**God Mode.** The flame in the chat bar is the whole integration in one switch: all four servers and every
rule. **Settings ▸ Claude Code ▸ Claude IDE Integration** fine-tunes it — the servers, whether an
unexpected client must be approved, the mirror, and each rule per server. Each rule is one instruction in
Claude's system prompt, repeated every turn, naming which tool replaces which native one, so a session does
not drift back to `grep` and `sed`. An upgrade that adds domains switches their rules on.

**The guard sits inside the servers.** Every tool call is judged by the [Sensitive Guard](#security) before
it runs; a refusal comes back as the tool's error naming the rule and the string that tripped it, and the
answer is to change that string, never to switch tool.

## The chat

Each tab is an independent session with its own `claude` process — the binary's own sessions, so they are
the same conversations you see from a terminal. Replies stream; tool calls are collapsible cards coloured by
state; reasoning is collapsed by default; follow-ups typed during a turn queue in order. The composer bar
carries provider, model (read from the binary's handshake, nothing hardcoded), permission mode, effort and
thinking, all changeable mid-conversation, plus attachments (files, a directory, an image, the selection,
recent files, paste or drag an image) and the flame, the shield and the phone.

**When Claude wants to change a file** the proposal opens as an editable diff tab — Current | Proposed —
with Accept / Reject in the chat, never a modal. Edit the proposed side before accepting; what gets written
is your version. Every completed edit card carries **Restore**, and ⚙ ▸ **Review This Session's Changes…**
diffs the whole session against its base. Permission modes: **Ask each time**, **Accept edits**, **Plan**,
**Bypass permissions** — and whatever the mode, the guard decides first.

**Agents** get their own tabs and transcripts; their tool calls draw under the Task card that spawned them.
**Workloads** draws everything running across every chat as one tree. Background tasks keep their output
after they end. The **Session** view shows context, tokens, cost, plan limits, account, MCP health; the
**Plan** view shows the plan-mode document; the **Git** view is a chat dedicated to the repository with
*Commit with Claude* and *Revert this file with Claude* as bounded, hand-approved prompts.

**Sessions**: Open Previous Session…, Rename, Fork, restore-on-startup. The plugin stores no transcripts of
its own and **never deletes a conversation** — there is no delete action, and a source contract bans
recursive deletion anywhere in the codebase. **Remote Control** (the phone button) connects a chat to
claude.ai/code or the mobile app; the guard still decides first, on this machine.

Keyboard: `Enter` send · `Shift+Enter` newline · `Esc` close a menu or interrupt · `Ctrl/Cmd+F` find ·
`Ctrl/Cmd+O` fold all reasoning · `/` the slash-command palette · `Tab` on an empty composer takes the
suggested next prompt.

## Security

The plugin ships a **deterministic sensitive-data lock**, `SensitiveGuard`: out-of-band Kotlin with no model
input, evaluated before any auto-approval, for the agent's native tools *and* for every IDE tool call
inside the four servers. There is no prompt that argues it into a yes, and prompt injection is assumed to
succeed rather than detected — which is why it judges the tool call, never the reasoning.

It classifies **credential and key material** (SSH and GPG keys, cloud and cluster credentials, database
and shell-history secrets, browser and password-manager stores, wallets, agent and code-host tokens),
**dangerous commands** (credential dumps, exfiltration, network-piped-to-shell, LOLBINs, offensive tooling)
and **foreign territory** (another user's home, network mounts, non-`/mnt/c` WSL drives), with structural
patterns that cover Linux, macOS, Windows and WSL, on canonicalised paths and de-obfuscated commands. The
whole input object is walked for path-like values, payload keys included.

It decides by trust of the caller: the agent's own tools get an explicit card every time; third-party MCP
servers and Skills are denied outright; foreign territory is denied for everyone. **Settings ▸ Claude Code
Security** holds a mode for the guard as a whole, a mode per rule by category, three whitelists (a command
prefix, at the reach of a rule, a category or everywhere) and extra credential globs and blocked domains.
Turning a rule off never allows silently: a hit becomes a card you must answer. The shield in the chat bar
suspends the guard for a chosen duration; it is on by default and unlit whenever it is not.

The permission mode is the plugin's, never the binary's: `acceptEdits` and `bypassPermissions` are
translated to `default` on the command line, so every call arrives as a control request and the verdict
stays the plugin's. The guard's rules and their mechanics are in
[`docs/SECURITY-GUARD.md`](docs/SECURITY-GUARD.md); the threat model is [ADR 0002](docs/adr/0002-threat-model.md);
the full policy and how to report a vulnerability is [`SECURITY.md`](SECURITY.md).

**Telemetry: none.** No analytics, no crash reporter, no usage counter. Your conversation goes from the
`claude` binary to Anthropic over the channel it already uses; the IDE servers listen on Unix sockets with a
token generated per session and rotated while it runs; the only other traffic goes to your own forge
through the IDE's account, and to the public Marketplace API when you ask about releases.

## Settings that matter

**Settings ▸ Claude Code**, one page grouped by subject:

| Setting | Default | Why you would change it |
|---|---|---|
| Model · permission mode · effort · thinking | top Opus tier · Ask each time · high · adaptive | The launch defaults for every new chat |
| **claude executable path** | auto-detect | A non-standard install, or an IDE that does not inherit your `PATH` |
| **Provider** | Anthropic | An Anthropic-compatible endpoint; each provider's key is stored separately, and an `sk-ant-` key is refused in a third-party slot |
| **Claude IDE Integration** | on: four servers, every rule, mirror on | Turn a server or a rule off, require approval for unexpected clients, stop mirroring; the flame in the chat bar is the same switch |
| **Sensitive Guard** | every rule Enforcing | Its own page, **Settings ▸ Claude Code Security** — see [Security](#security) |
| **Restore open chats on startup** | on | Start with a single empty chat |
| **Allowed / disallowed tools**, **Always-allowed tools** | empty | Stop being asked about a tool; the guard still decides first |
| **Environment variables**, **Source script** | empty | Seed the binary's environment; the script runs at session start behind a per-project trust prompt |
| **Custom MCP servers** | empty | Your own servers as a JSON object, merged into one `--mcp-config` |
| **Advanced launch** | flags omitted | `--max-turns`, `--max-budget-usd`, `--fallback-model`, `--add-dir`, betas, strict MCP config |

Settings are per IDE installation and per project; credentials and host tokens are global. **Transfer**
exports and imports a settings file and migrates from another JetBrains IDE on the same machine.

## Any MCP client can drive the IDE

The four servers are ordinary MCP servers that happen to live inside the plugin. Claude Code is their first
client, not their only one: anything that speaks MCP can open the socket, authenticate with the session's
token and run the same tools under the same guard. The bundled stdio bridge and the socket protocol are in
[`docs/MCP_CLIENT.md`](docs/MCP_CLIENT.md). When the chat page cannot be shown at all, the servers still
start and a notification carries the configuration to paste into your client.

## Troubleshooting

| Symptom | Usually |
|---|---|
| The chat never loads, or the window is blank | JCEF is unavailable: below 2025.3.1 this version does not run; otherwise check `ide.browser.jcef.enabled` in the Registry |
| "Claude Code was not found" with the binary installed | It is somewhere the plugin does not look, or the IDE did not inherit your `PATH`; paste the path into the card |
| A tool call is refused with no card to override | The guard blocked it; the message names the rule and the Settings path. Foreign-territory blocks are absolute by design |
| Claude uses `Bash` or `grep` although the IDE tools exist | The flame is off, or a rule is: turn God Mode on, or the rule in Settings ▸ Claude Code ▸ Claude IDE Integration |
| A domain is missing from the servers | The IDE plugin behind it is not installed or disabled (Git, GitHub, Java, Database, Terminal…) |
| A commit or Services action answers "not enabled here" | The view had not been shown yet; ask again, the view is now open, or open it yourself |
| Signed out after a restart | The credential could not be renewed; sign in again, and check the IDE reaches your keychain |

Deeper cases with log locations: [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) and
[`docs/FAQ.md`](docs/FAQ.md). Bugs and features: the templates in
[`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE). Vulnerabilities: [`SECURITY.md`](SECURITY.md).

## Build from source

JDK 21 and Node 22+ (`.nvmrc`). `./gradlew buildPlugin` produces `build/distributions/claude-code-native-6.0.0.zip`;
`./gradlew test` runs the JVM suite, `npm test` the frontend suite, `./gradlew detekt spotlessCheck` and
`npm run lint` the static gates, `./gradlew verifyPlugin` the Plugin Verifier against the declared range.
The rules the code is held to — no deprecated or internal platform API, a 250-line ceiling per file, no
comments, one gateway file per external plugin, the guard off limits — are in
[`DIRECTIVES.md`](DIRECTIVES.md) and [`docs/PLATFORM_API_POLICY.md`](docs/PLATFORM_API_POLICY.md).

## Documentation

- [`docs/SKILL_INVENTORY.md`](docs/SKILL_INVENTORY.md) — every tool, its parameters and an example, plus the board of what shipped
- [`docs/MCP_CLIENT.md`](docs/MCP_CLIENT.md) — driving the IDE from any MCP client
- [`docs/MCP_ROADMAP.md`](docs/MCP_ROADMAP.md) — what was considered, what is out and why
- [`docs/PLATFORM_API_POLICY.md`](docs/PLATFORM_API_POLICY.md) — the platform APIs the plugin refuses and their replacements
- [`docs/SECURITY-GUARD.md`](docs/SECURITY-GUARD.md) — the Sensitive Guard: rules, categories, whitelists
- [`docs/FAQ.md`](docs/FAQ.md) · [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) · [`docs/BINARY_COMPAT.md`](docs/BINARY_COMPAT.md)
- [`docs/RELEASE_PROCEDURE.md`](docs/RELEASE_PROCEDURE.md) · [`docs/BRANCHING.md`](docs/BRANCHING.md) · [`docs/CI_SETUP.md`](docs/CI_SETUP.md)
- [`docs/adr/`](docs/adr) — the decisions, the threat model among them
- [`CHANGELOG.md`](CHANGELOG.md) · [`RELEASE_NOTES.md`](RELEASE_NOTES.md)

## Licence and attribution

GPL-3.0 — see [`LICENSE`](LICENSE) and [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). *Claude* and
*Claude Code* are trademarks of Anthropic, PBC; *JetBrains* and the IDE names are trademarks of JetBrains
s.r.o. This project is not affiliated with, sponsored by, or endorsed by either. The upstream repository is
[serialexperimentslainnnn/claude-code-for-jetbrains](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains).

## Disclaimer

This software is provided as is, without warranty of any kind. It runs an AI agent with access to your
files, your IDE and your repositories under the permissions you grant it and the guard described above;
read what it proposes before you accept it, and keep the backups you would keep anyway.
