# Claude Code Native

[![Version](https://img.shields.io/badge/version-5.5.0-E07B5A)](CHANGELOG.md)
[![IDE](https://img.shields.io/badge/JetBrains-2025.3%20%E2%86%92%20latest%20EAP-000000?logo=jetbrains)](#requirements)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-857%20JVM%20%2B%20149%20frontend-success)](#testing)

A native IntelliJ Platform plugin that integrates [Claude Code](https://claude.ai/code) into JetBrains IDEs — not a terminal wrapper, but a first-class GUI client with a modern **web UI** (an embedded Chromium / JCEF chat), native diff review, a deterministic security layer, and full protocol-level access to the `claude` binary.

> **Goal:** surpass AI Assistant and the official plugin (currently just a terminal launcher). Built to present to Anthropic.

## Why this plugin

- **No Node, no TS SDK at runtime.** It speaks the `claude` binary's `stream-json` + control protocol directly from Kotlin/JVM. One long-lived process per chat tab.
- **Nothing is mirrored from terminal output.** Every state — compaction, cost, hooks, subagents, MCP health — is reconstructed natively from the protocol's structured fields.
- **Diffs are real IDE diffs.** Edits open in the editor's own `DiffManager`, editable before you approve, never a modal dialog.
- **A security layer the model can't argue with.** Deterministic, out-of-band Kotlin gates every tool call before any auto-approval — see [Security](#security).

## Features

### Chat & transcript
- **Streaming chat** — token-by-token rendering in an embedded web (JCEF) transcript, with multi-chat tabs. The transcript, composer and permission/dashboard cards are an inlined web app (no CDN, strict CSP); diffs stay native via the IDE's `DiffManager`.
- **Command calls read like a terminal you can copy** — a `Bash`/PowerShell/MCP-exec call shows the exact command as its own copyable code block right under the header, visible without expanding the card, and the card gets its own accent. Detection is by input *shape*, not tool name, so any command-executing tool is covered.
- **Syntax highlighting** — code blocks, `Read`/`Write`/`Edit` output and coloured diffs are highlighted from the file's extension (~35 languages), painted in the IDE's own syntax colours.
- **Collapsible tool calls** — each card folds its output; outputs anchor under their own call. Live state by colour: sky-blue in flight (pulsing while working), green finished, red on error, with elapsed time.
- **A tab per agent** — an agent's work does not land in the chat's transcript: it gets its own, reachable from the bar under the chats, with the whole tree (agents, their agents, background tasks) one hover away. A finished agent keeps its tab, closing one only hides a view, and any subtab can be pinned as a tab of its own.
- **Workloads** — everything running across *every* open chat as one diagram: chats at the root, agents beneath them, tasks under whoever started them. Every node goes somewhere, and a running task can be stopped from it.
- **Background tasks that outlive themselves** — the binary stops listing a task the moment it ends, which is exactly when its output matters; the plugin keeps the task, its command and its output, live-tailed from the file the binary writes and rebuilt after a restart.
- **Multi-prompt queue** — send follow-ups while the agent is still working; queued messages are shown and reorderable.
- **Find in transcript** (Ctrl/Cmd+F) with hit navigation, **output-follow toggle**, and **Markdown** with tables, strikethrough, GFM task lists and nested lists.

### Permissions & diff review
- **Editable diff review** — Edit/Write/MultiEdit proposals auto-open an **editable** diff in the editor (Current | Proposed) *on the permission request*, in every mode. Tweak the proposed content before accepting and **Accept writes your edited version**; the transcript diff and "View diff" then show what was really written.
- **Inline permission cards** — Accept/Reject in the conversation, never a modal. A reviewable edit shows a read-only colour diff (red removed / green added) on the card. Edits are **atomic**: accepting an incoherent subset of an edit reliably broke code, so per-hunk selection was removed in 4.0.5.
- **"Always allow" per tool** — skip a tool's prompt for the rest of the project (revocable in Settings); reviewable writes stay confined to the project root.
- **MCP elicitation cards** — when an MCP server asks for input it appears inline (never a dialog): a URL flow opens an **http/https-only** link (an untrusted server can't reach `file:`/`javascript:`), a form renders a labeled input per schema field.
- **Diff History tab + rollback** — every Edit/Write in the session with a `+a/-b` summary, **View diff**, per-edit **Revert**, and **Roll back all changes**. Reverting a file-creating Write deletes the file.
- **Native rewind** — "Restore" asks Claude Code to `rewind_files` to that turn, with a confirmed IDE-side per-file revert as fallback.

### Editor integration
- **Editor actions** — right-click to **Explain with Claude**, **Add Selection to Claude Context**, or **Add File to Claude Context**.
- **Jump to code** — a file tool card names its file *relative to the project* and links it; paths, directories and symbols in Claude's replies become links **only after the IDE confirms them** (via the file index and *Go to Symbol*, so it works in every JetBrains IDE). Ambiguous or non-existent candidates stay plain text — a link is never dead.
- **Rich attachments** — current file / selection / clipboard image, drag & drop or paste images into the composer, native file & directory chooser, open and recent files. Chips show the real file-type icon and open on click.
- **Live VFS refresh** — every successful write refreshes the IDE immediately (by exact path for `Edit`/`Write`, re-scanning the tree after `Bash` or a mutating MCP tool), including newly created files.

### Sessions
- **Session history from the binary's own files** — the source of truth. "Open Previous Session…" lists the project's past chats by their real title; on startup your open tabs (or the most recent session) are re-attached via `--resume`. The plugin stores **no transcripts** — only which tabs were open.
- **A restored transcript shows what you actually said** — the binary writes its own bookkeeping (task notifications, caveats, command output) on the same `user` lines your prompts use, and those are no longer replayed in your voice.
- **Settings in the OS keychain** — one encrypted document in the IDE's password safe, shared by every project, instead of a plaintext per-project file that people commit with an API key in its env block. Existing settings are adopted on first run.
- **Session management** — rename, fork and delete past sessions.
- **Attention notifications + tab badge** — a background session needing you (permission, finished turn, error) notifies and badges its tab; suppressed for the chat already on screen.

### Model & runtime controls
- **Autodetected, versioned model picker** — the model list comes straight from the binary's `initialize` catalog, and each entry shows its **version** ("Opus 5 with 1M context", "Sonnet 5", "Haiku 4.5") rather than a version-less label. No model name or version is hardcoded anywhere; new tiers appear on their own. Fresh installs pin the concrete Opus tier.
- **Live chips** — model · permission mode · effort · thinking, changeable mid-session without a restart.
- **Full slash-command palette** — every command from the `initialize` handshake, plus client-side `/btw`.
- **Provider selector (Anthropic / DeepSeek)** — the official Anthropic endpoint (your subscription/login) or DeepSeek's Anthropic-compatible API. Each provider's key is isolated in the IDE password safe and never reused across providers.
- **Advanced launch options** — max turns, max budget (USD), fallback model, extra `--add-dir` roots, beta flags, strict MCP config.
- **Plan mode**, **native hooks** (each hook run shows as one transcript row that evolves to ✓/✗), and a **predicted next prompt** chip you review before sending.

### Usage & diagnostics
- **Session dashboard** — the context breakdown by category, usage & cost (in / out / cache, USD when the binary reports it), your plan's limit windows with the time left on each, account (email / org / plan / provider), active model, working directory, binary version, and MCP server health with per-server reconnect / enable-disable. What is *running* lives in its own **Workloads** view.
- **Plan limits where you can see them** — one bar per window under the composer (blue < 65%, amber < 85%, red above), refreshed on a timer whether or not the chat is on screen: a window can reset, or fill up from another device, with you looking elsewhere.
- **Live token counter** — a reasoning-token estimate and output count in the composer readout mid-turn.
- **Memory recall** — a collapsible "Recalled N memories" row showing which memories (scope · path · snippet) influenced the turn.
- **Account & diagnostics** — Account info, Binary Version, Effective Settings and an interactive MCP-runtime dialog in the gear menu.

### Login & look
- **`/login` from the chat** — runs the OAuth sign-in in an IDE terminal tab (the browser opens and the callback is captured automatically), falling back to a headless PTY-based flow if the Terminal plugin is unavailable. No copy-pasting a command into an external shell.
- **`AskUserQuestion`** — multi-select option cards rendered natively with wrapped labels, descriptions and previews.
- **IDE-themed** — surfaces, text, borders and syntax colours follow the active theme (light/dark), with the Claude coral as the accent and custom icons on every tool call.
- **🌈 Vibe Coder Mode** — opt-in toggle that animates the accent through the rainbow and swaps the avatar for a Nyan Cat. Off by default.

## Security

The plugin ships a **deterministic sensitive-data lock** (`permission/SensitiveGuard`). It is not a model-side guardrail: the classification is out-of-band Kotlin with no model input, evaluated in `PermissionBroker.handle` **before any auto-approval branch**. Because the binary is always launched in `default` mode, every call arrives as a control request — so the verdict is the plugin's to make, and it holds under `acceptEdits` and `bypassPermissions` alike.

**What it classifies**

| Category | Examples |
|---|---|
| Credential / key material | SSH & GPG keys, cloud and cluster credentials, DB and shell-history secrets, browser and password-manager stores, crypto wallets, AI-agent and code-host tokens |
| Dangerous commands | Credential dumps, file exfiltration, network-piped-to-shell, LOLBINs, recognised offensive tooling |
| Foreign territory | Another user's home, UNC / network mounts, non-`/mnt/c` WSL drives |

Patterns are **structural**, so one rule covers Linux, macOS, Windows (`C:\Users\…\.ssh`) and WSL (`/mnt/c/Users/…`). The whole input object is walked for path-like values — not a fixed key list — so an MCP tool naming its argument `target` or `destination` is still covered. Paths are canonicalized on disk (symlinks, `..`) and commands pass a de-obfuscation stage (broken quotes, `$IFS`, variable substitution, base64 payloads) before matching.

**How it decides** — by trust of the caller, as an allowlist:

- the agent's **own tools** → an explicit permission card, **every time**, in every mode;
- **MCP servers and Skills** → denied outright by default; third-party code has no business reading your keys;
- **foreign territory** → denied for everyone by default.

**Per-rule toggles (Settings ▸ Claude Code ▸ Security).** Credentials, dangerous commands, and each of the three foreign-territory checks (other users' homes, network/UNC mounts, foreign WSL drives) can each be switched off independently — all **ON** by default. Turning one off is never a silent allow: detection still runs, a hit is only *downgraded* from an automatic DENY to a permission card shown every time, to every caller. There's no toggle that makes a match invisible.

The sensitive-path list itself has a separate, always-additive knob: `sensitiveExtraGlobs` widens the blacklist, never empties it. Paths under the project root are exempt from the credential and foreign rules (your repo is the sanctioned zone); dangerous-command classification is location-independent. A session refuses to start when the project itself sits on a remote or network-mounted path.

Detecting a path concealed inside an arbitrary shell string is best-effort and can be widened over time; the **enforcement** of a match is absolute. See [`SECURITY.md`](SECURITY.md) for the full model and reporting policy.

Separately: jump-to-code links can only ever open inside the project or your own home (canonical, symlink-safe), while the **write** gate stays project-only.

## Requirements

- **JetBrains IDE** 2025.3 or newer (build 253+) — IntelliJ IDEA, PyCharm, GoLand, WebStorm, … — with the **embedded browser (JCEF)** available: the whole chat UI is a web view, so the plugin declares a hard dependency on it. 5.5.0 raised the floor from 2025.1 for exactly that reason — since build 262 the platform ships JCEF as a separate bundled plugin (`com.intellij.modules.jcef`), a plugin that does not declare it gets no browser classes at all, and that module id does not exist before 2025.3. Staying on 2025.1/2025.2 means staying on 5.1.1
- **`claude` CLI** installed and on `PATH` or a typical location (Linux/macOS: `~/.local/bin`; Windows: npm, scoop, volta, chocolatey, `~\.local\bin`)
  - Install: `npm install -g @anthropic-ai/claude-code`, or follow [claude.ai/code](https://claude.ai/code)
  - Custom location? Set the executable path (and any environment variables) in **Settings → Tools → Claude Code**
- **Auth** reused from the binary (Claude subscription / OAuth, or `ANTHROPIC_API_KEY`)

## Installation

**From the JetBrains Marketplace** (recommended):

1. **Settings → Plugins → Marketplace**
2. Search for **"Claude Code Native"**
3. Install and restart

The Marketplace listing tracks the latest release. This repository is the **source of truth for the code**; signed release archives are also attached to each [GitHub release](https://github.com/serialexperimentslainnnn/claude-code-for-jetbrains/releases).

**From source:** see [Build from source](#build-from-source).

## User guide

### First run

Open the **Claude Code** tool window (right side panel, same area as AI Assistant). What you see first depends on what the plugin finds:

- **"Claude Code was not found"** — the `claude` binary is not installed or not where the plugin looks. The card offers the official install command for your OS (it runs in the IDE terminal, or you copy it and run it yourself), plus a field to point at an existing binary. The tab starts on its own once the binary appears; you do not have to close and reopen it.
- **Sign in** — you are not signed in yet. One button, one browser round-trip, and the card walks you through it. If your browser shows you a code instead of returning automatically, paste it in the same card.
- **Loading** — the binary is starting. You can still switch to another chat while it does.

Your login is stored in the **IDE's password safe** (the OS keychain), not in a file on disk. The plugin deliberately removes `~/.claude/.credentials.json` after taking custody of it, and hands the credential to the binary through the environment — never on the command line, never in a log. **Log out** clears only what the plugin holds; your terminal `claude` login is left alone.

### The chat

Each chat tab is an independent session with its own `claude` process. Type in the composer, `Enter` to send.

| Shortcut | Action |
|---|---|
| `Enter` | Send |
| `Shift+Enter` | New line |
| `Shift+Tab` | Cycle permission mode |
| `Tab` (empty composer) | Accept the suggested next prompt |
| `Esc` | Interrupt the running turn |
| `Ctrl/Cmd+F` | Find in transcript (`Enter` / `Shift+Enter` to walk the hits) |
| `Ctrl/Cmd+O` | Collapse / expand reasoning ("Thought process") |
| `/` (empty composer) | Slash-command palette |

The row of **chips** under the composer — provider · model · permission mode · effort · thinking — changes any of them mid-conversation. Model and mode apply immediately; the thinking toggle restarts the session behind the scenes (with `--resume`, so nothing is lost).

**Attach context** with the 📎 button: files, a directory, an image, your current selection or the open file, plus a filterable list of recently-opened files. You can also drag an image in, or paste one. From the editor, right-click gives you *Explain with Claude*, *Add Selection to Claude Context* and *Add File to Claude Context*.

Paths and symbols in Claude's answers become links **only when the IDE can confirm they exist**, so a link never dead-ends; clicking one opens the file at the line, or reveals a directory in the Project view.

### When Claude wants to change a file

Nothing is written without you seeing it first. An edit proposal opens as an **editable diff tab** in the editor — Current | Proposed — with an inline **Accept / Reject** card in the chat.

- **Edit the proposed side before accepting.** What gets written is your edited version, not the original proposal.
- **Accept / Reject the change as a whole.** Per-line acceptance was removed in 4.0.5 because it produced code that did not hold together.
- The diff closes when you accept, reject, stop or interrupt.
- **View diff** on any past tool card reopens what that call actually wrote, at any time.
- **Restore** asks Claude Code to rewind the files to that point in the conversation; if the binary cannot, the plugin offers to revert them itself, with confirmation.

The **permission mode** chip decides how often you are asked: ask each time, accept edits automatically, plan mode (Claude proposes a plan and waits), or bypass. Whatever the mode, the [security lock](#security) is checked first and cannot be turned off — at most, a rule you disable in Settings turns an automatic block into a card you have to answer.

### Agents and background tasks

When Claude spawns agents, each one gets **its own tab and its own transcript**, so its thinking and its tool calls stay out of the main conversation. The bar under the chat tabs shows the one you are reading; hovering a chat's `⋮` opens the whole tree at once — agents, their agents, and the background tasks each of them started — and clicking any row goes there.

- **A finished agent keeps its tab**, marked as finished. Reading *why* something failed is the point.
- **Closing a subtab hides a view, it destroys nothing.** The card that spawned it reopens it.
- **Pin** (⇱) turns the subtab you are reading into a tab of its own, next to the chats.
- **Background tasks** keep their tab and their output *after* they end, and both come back after a restart.

The **Workloads** view (top-right of the tab bar) draws everything that is running across *every* open chat as one diagram: chats at the root, agents under them, tasks under whoever started them. Every node is a destination, and a running task can be stopped from there.

### The dashboard

The **Session** view shows what the current session is costing you: context breakdown, tokens and cost, your plan's limit windows with the time left on each, the account you are signed in as, the model, the working directory and the binary version — plus the MCP servers, which can be reconnected or toggled from there.

Your plan limits also sit as small bars under the composer, so you can see them without opening anything: blue below 65%, amber below 85%, red above.

### Sessions

Chats are the binary's own sessions, so they are yours in every tool: **Open Previous Session…** lists what exists on disk, with the titles Claude gave them, and reopens one with its transcript. Chats you had open are restored when the IDE starts (Settings ▸ Claude Code). The plugin stores **no transcripts of its own** — only which tabs were open.

### IDE tools (MCP) — optional

Let Claude query the IDE directly (diagnostics, open files, usages, …) via JetBrains' own MCP server. **Off by default**, two steps:

1. **Enable JetBrains' MCP Server plugin** (Settings ▸ Plugins) and confirm it is running.
2. **Turn it on here** — **Settings ▸ Claude Code ▸ IDE tools (MCP)**: tick *Enable JetBrains IDE tools (MCP)*, pick the **transport** (`sse` default, `streamable-http` or `stdio`) and the **port** if you changed it from `64342`. Apply, then start a **new chat** (the setting applies when the `claude` process launches).

You can also register **custom MCP servers** as a JSON object of `name → server`. Both are merged into a single `--mcp-config`.

> ⚠ **Security:** `sse`/`streamable-http` use JetBrains' localhost endpoint, which any process on your machine can reach; `stdio` launches a helper process instead. Enable only on a machine you trust. Every IDE tool call is still gated by the permission prompt *and* by the [sensitive-data lock](#security).

### When something goes wrong

| Symptom | What it usually is |
|---|---|
| The chat never loads, or the tool window is blank | The IDE's embedded browser (JCEF) is unavailable. On **2025.1 / 2025.2** this plugin no longer runs at all — see [Requirements](#requirements). Otherwise check Help ▸ Find Action ▸ *Registry* for a disabled `ide.browser.jcef.enabled` |
| "Claude Code was not found" with the binary installed | It is somewhere the plugin does not look. Paste the full path into the card, or set it in **Settings ▸ Claude Code** |
| Signed out again after a restart | Expected only if the stored credential could not be renewed — it renews itself silently on launch. If it keeps happening, sign in again from the card and check the IDE can reach your keychain |
| A tool call is refused with no card to override it | The [sensitive-data lock](#security) blocked it. The message names the rule and the Settings path; foreign-territory blocks are absolute by design |
| A chat is empty after reopening it | The session's file is gone from `~/.claude/projects/…` (deleted, or a different working directory). The plugin keeps no transcripts of its own |
| The agent seems stuck | `Esc` interrupts the turn. If a tool card sits running for ever, its agent's tab shows what it was actually doing |

Deeper cases, with logs and commands: [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) and [`docs/FAQ.md`](docs/FAQ.md).

## Build from source

Requires **JDK 21** (the IDE runs on JBR 21). The Gradle wrapper is included.

```bash
JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew buildPlugin
# → build/distributions/claude-code-native-5.5.0.zip
```

Install it with **Settings → Plugins → ⚙ → Install Plugin from Disk**.

```bash
./gradlew runIde         # sandbox IDE with the plugin loaded
./gradlew test           # unit + headless + integration (JVM)
./gradlew verifyPlugin   # IntelliJ plugin verifier across the declared range
./gradlew checkDrift     # protocol drift vs. the latest SDK + binary
./gradlew koverHtmlReport
npm test                 # frontend suite (vitest + jsdom)
```

`verifyPlugin` can run **fully offline** against locally extracted IDEs:

```bash
./gradlew verifyPlugin -PlocalIdePath=/path/to/idea-A,/path/to/idea-B
```

### Testing

The suite is a real pyramid — **857 JVM tests + 149 frontend**, 0 failures:

- **unit** (pure JVM) — protocol parse/build, diff reconstruction, the exhaustive `PermissionBroker` and `SensitiveGuard` matrices, hunk encode, path-traversal guards, settings enums;
- **headless component** — `BasePlatformTestCase` in-process, for the project services and the settings UI;
- **integration** — a real `ClaudeSession` driven against the deterministic `bin/fake-claude` stand-in with JSONL fixtures;
- **UI end-to-end** — RemoteRobot, gated behind `-PuiTest.enabled=true`;
- **frontend** — vitest + jsdom loading the real inlined `resources/jcef/*.js`, including a JS↔CSS class contract.

## How it works

The plugin speaks **directly with the `claude` binary** over its `stream-json` + control stdio protocol — no Node.js or TS SDK at runtime. One long-lived process per chat session handles streaming input and output; `can_use_tool` control requests are answered by the plugin, so **the binary writes the file** only after your approval.

The TS SDK package (`node_modules/@anthropic-ai/claude-agent-sdk/`) is kept as a **protocol reference only** and is not distributed. `./gradlew checkDrift` updates the SDK and binary to latest and reports any protocol kind the plugin doesn't model yet.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture, protocol details and verified empirical facts about the binary's behaviour.

## Status

**v5.5.0** — every agent gets its own tab. A session running agents under agents used to put all of it in one transcript: consecutive "Thought process" rows belonging to different agents, interleaved, impossible to follow. Now each agent has its own transcript, reachable from a tab bar that shows what you are reading and keeps the whole tree one hover away, and the three dashboard lists became a single **Workloads** diagram of everything that is running, across every chat. A background task keeps its tab and its output after it ends — the binary stops listing a task the moment it finishes, which is precisely when its output is worth reading — and both survive a restart. Your settings moved into the **IDE's password safe**: they used to sit in `.idea/claude-code.xml`, per project and in the clear, which is a file people commit and where an API key in the env block ends up. **It also fixes a plugin that was dead on 2026.2**: the platform moved the embedded browser into a bundled plugin there, and without declaring that dependency no chat could open at all — which is why the minimum IDE is now 2025.3.

**v5.0.0** — the standards-compliance major. Nothing you use changes; the *project* did. The chat UI now speaks to screen readers (a live region announcing when a turn starts, ends, or is waiting on your approval) and every control has a visible focus ring again, including in high-contrast mode. The sensitive-data lock gained a **written** threat model ([ADR 0002](docs/adr/0002-threat-model.md)) that states what it defends against — and admits what it does not: prompt injection is assumed to succeed, not detected, which is why the lock judges the *tool call* and never the model's reasoning. Third-party licence attribution now ships inside the artifact, seven npm-audit findings against never-distributed build tooling are gone (the SDK reference was mis-declared as a runtime dependency), and a released version number is now final.

**v4.4.1** — fixes `/login` always dead-ending on "run this yourself in a terminal": every IDE terminal API the plugin reflected on had been removed after 2025.2, and each lookup failed silently. It now opens a real terminal tab on every supported IDE, with a headless native sign-in as a genuine fallback rather than a dead end.

**v4.4.0** — each rule in the [security lock](#security) is now independently switchable (Settings ▸ Claude Code ▸ Security), all ON by default; disabling one only ever downgrades an automatic block to a permission card, never to a silent allow. Also fixed: several of the CLI's own native tools (background tasks, cron, worktrees, and more) had fallen off the plugin's trusted-tool allowlist as the CLI grew, so they were hard-denied exactly like a blocked third-party MCP call — the allowlist is now current.

Verified **Compatible** on IU-253, IU-261, IU-262 and PY-262, with **zero deprecated or internal API** — both product families, because how a product bundles the platform is exactly where a classloader problem hides. `untilBuild` is declared `263.*` ahead of the 2026.3 EAP; the verifier picks up a real 263 build automatically once one is published.

Recent highlights: the plan-limits panel with per-window reset times (5.0.0–5.1.1); the model picker showing each model's real version (4.3.3); the executed command as a copyable code block plus syntax-highlighted diffs and file output (4.3.2); the [deterministic sensitive-data lock](#security), jump-to-code links and per-write VFS refresh (4.3.1); editable diff review (4.1.0); and the full JCEF UI rebuild (4.0.0).

Full history in [`CHANGELOG.md`](CHANGELOG.md) and [`RELEASE_NOTES.md`](RELEASE_NOTES.md).

## Documentation

Using the plugin is covered above, in [User guide](#user-guide). Everything below is for working *on* it.

| Document | What it covers |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Architecture, protocol, empirical binary behaviour |
| [`PROJECTMAP.md`](PROJECTMAP.md) | Where things live — the "I want to change X → go to Y" index |
| [`AGENTS.md`](AGENTS.md) | Runbook for working on this repo with a coding agent — commands, gates, boundaries |
| [`SECURITY.md`](SECURITY.md) | The sensitive-data lock, triage scope, reporting policy |
| [`docs/adr/`](docs/adr/README.md) | Architecture Decision Records — release process, threat model, i18n deferral |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute |
| [`docs/FAQ.md`](docs/FAQ.md) · [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Common questions and fixes |
| [`docs/BINARY_COMPAT.md`](docs/BINARY_COMPAT.md) · [`docs/DRIFT_DETECTION.md`](docs/DRIFT_DETECTION.md) | Binary compatibility policy and drift detection |
| [`docs/RELEASE_PROCEDURE.md`](docs/RELEASE_PROCEDURE.md) · [`docs/BRANCHING.md`](docs/BRANCHING.md) | Release and branching workflow |
| [`docs/CI_SETUP.md`](docs/CI_SETUP.md) | One-time CI/CD configuration: the deployment environment, its secrets, branch protections |
| [`docs/TELEMETRY.md`](docs/TELEMETRY.md) | What is (and isn't) collected — spoiler: nothing |

## Disclaimer

Unofficial, community-built, open-source plugin. **Not affiliated with, sponsored by, or endorsed by Anthropic or JetBrains.** It requires your own separately-installed `claude` CLI and your own Claude subscription or API key — no credentials are bundled or provided.

"Claude" and "Claude Code" are trademarks of Anthropic; "JetBrains", "IntelliJ", "PyCharm" and related names are trademarks of JetBrains s.r.o. Used here for identification only.

## License

Licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE) for the full text.
