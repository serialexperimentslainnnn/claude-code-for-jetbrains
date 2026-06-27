# Claude Code Native

A native IntelliJ Platform plugin that integrates [Claude Code](https://claude.ai/code) into JetBrains IDEs — not as a terminal wrapper, but as a first-class GUI client with a modern **web UI** (an embedded Chromium / JCEF chat), native diff review, and full protocol-level access to the `claude` binary.

> **Goal:** surpass AI Assistant and the official plugin (currently just a terminal launcher). Built to present to Anthropic.

## Features

### Chat & transcript
- **Streaming chat** — token-by-token rendering in an embedded web (JCEF) transcript, multi-chat tabs. The transcript, composer, and permission/dashboard cards are an inlined web app (no CDN, strict CSP); diffs stay native via the IDE's `DiffManager`.
- **Collapsible tool calls** — each tool card folds its output via a disclosure triangle; outputs anchor under their own call. Tool cards show live state by colour: sky-blue while in flight (pulsing while working), green when finished, with elapsed time.
- **Nested subagents** — `Task`/Agent activity (its tool calls, outputs and text) nests and indents under the Agent, collapsing hierarchically
- **Multi-prompt queue** — send follow-ups while the agent is still working; queued messages shown in the UI
- **Output follow toggle** — pin to the streaming bottom, or scroll up to read history mid-stream without losing your place
- **Markdown rendering** — bold, inline code, syntax-highlighted code blocks, tables, strikethrough, GFM task lists, nested lists

### Permissions & diff review
- **Permission-gated diff review** — Edit/Write proposals shown as an in-editor diff tab with inline Accept/Reject cards (no modal dialogs)
- **Persistent diff + hunk-by-hunk** — re-open any edit's diff from its transcript card ("View diff"), and accept only selected hunks on the permission card (the binary writes just that subset)
- **"Always allow" per tool** — skip a tool's prompt for the rest of the project (revocable in Settings); reviewable writes stay confined to the project root
- **MCP elicitation cards** — when an MCP server asks for input, it appears as an inline card (never a dialog): a URL flow opens an **http/https-only** link (an untrusted server can't open `file:`/`javascript:` schemes) with Accept/Cancel, and a form renders a labeled input per schema field
- **Diff History tab + rollback** — lists every Edit/Write in the session with a `+a/-b` summary, **View diff** and per-edit **Revert**, plus a **Roll back all changes** action. Reverting a file-creating Write deletes the file; reverting an edit restores the prior contents.

### Editor integration
- **"Explain with Claude"** — editor right-click action sends the selection to chat; `path:line` references in replies are clickable (jump to file/line)
- **Rich IDE attachments** — attach the current file / selection / clipboard image, drag & drop or paste (Ctrl/Cmd+V) images straight into the composer, pick files and directories from a native chooser, or select from your open and recently-opened files. Chips show the real file-type icon and are clickable to open.
- **`jb://open` links** — file paths in replies (with or without line numbers) become clickable links, project-confined

### Sessions
- **Session history** — reads the `claude` binary's own session files (the source of truth): "Open Previous Session…" lists the project's past chats by their real title, and on startup the tabs you had open (or your most recent session) are reopened and re-attached via `--resume`. The plugin stores no transcripts of its own — only which tabs were open (in `workspace.xml`).
- **Session management** — rename, fork, and delete past sessions (binary session files remain the source of truth)
- **Session notifications + tab badge** — a background session needing attention (permission, finished turn, error) notifies you and badges its tab

### Model & runtime controls
- **Full slash-command palette** — every command from the `initialize` handshake, plus client-side `/btw` (Ctrl+K)
- **Model / effort / permission-mode / thinking controls** — live chips in the composer, no restart needed
- **Provider selector (Anthropic / DeepSeek)** — switch between the official Anthropic endpoint (your subscription/login) and DeepSeek's Anthropic-compatible API (`/anthropic`). Each provider's API key is isolated in the IDE password safe and credentials are never reused across providers. Verified tool-call compatible with DeepSeek V4 Pro.
- **Advanced launch options** — max turns, max budget (USD), fallback model, extra `--add-dir` roots, beta flags, strict MCP config
- **Plan mode** — ExitPlanMode plan cards with decision reasons and blocked-path context
- **Native hooks** — `hook_callback` answered host-side (the real tool gate is still `can_use_tool`); each hook the binary runs also shows as a single transcript row that updates from "running…" to ✓/✗
- **Predicted next prompt** — the binary's `prompt_suggestion` appears as a dismissible chip above the composer; click to drop it into the input (you review and send it yourself)

### Usage & diagnostics
- **Session dashboard** — a toggle in the chat view flips the transcript to an overlay dashboard with a context breakdown (segmented by category), usage & cost (in / out / cache, USD when the binary reports it), account (email / org / plan / provider), the active model, the in-flight subagents (with Stop), and MCP server health (status dot + reconnect / enable-disable per server)
- **Usage & cost** — the dashboard shows the authoritative cumulative token breakdown (input / cache write / cache read / output, from the binary's `get_session_cost`) and session cost; a compact context/output readout sits under the composer. _(The standalone quota/reset bar from the Swing UI is not yet re-ported.)_
- **Live token counter** — a live reasoning-token estimate (`thinking_tokens`) and output count surface in the composer readout mid-turn
- **Memory recall** — a collapsible "Recalled N memories" row shows which memories (scope · path · snippet) influenced the turn
- **Subagents** — in-flight `Task` subagents appear in the dashboard with running tokens / tool-uses and a Stop button
- **Account & diagnostics** — Account info, Binary Version, Effective Settings, and an interactive MCP-runtime dialog in the gear menu

### Login & setup
- **Native `/login`** — PTY-based OAuth flow (pty4j): the plugin opens your browser, collects the code in the IDE, and signs you in — no terminal tab needed. Works on the reworked terminal (2025.2+) and classic terminal alike.
- **`AskUserQuestion` support** — multi-select option cards rendered natively, with full-width wrapped labels/descriptions/preview

### Look & feel
- **IDE-themed UI** — surfaces, text, and borders follow the active IDE theme (light/dark)
- **Native visual identity** — custom icons on every tool call (bash, read, edit, search, web, task…), the attach button, and chips, with the Claude coral as the accent
- **Two-row composer** — model · mode · effort · thinking pills (each with its own icon and hover glow) on top; toggles + attach + neon Play/Stop on the bottom. Coral focus ring while you type, editor-font prompt.
- **🌈 Vibe Coder Mode** — opt-in toggle that animates the coral accent through the rainbow and swaps the avatar for a Nyan Cat. Purely for fun; off by default.

## Requirements

- **JetBrains IDE** 2025.2 or newer (build 252+) — IntelliJ IDEA, PyCharm, GoLand, WebStorm, … — with **JCEF enabled** (bundled with the IDE's JBR by default; the chat UI is an embedded web view)
- **`claude` CLI** installed and accessible on `PATH` or a typical location (Linux/macOS: `~/.local/bin`; Windows: npm, scoop, volta, chocolatey, `~\.local\bin`)
  - Install: `npm install -g @anthropic-ai/claude-code` or follow [claude.ai/code](https://claude.ai/code)
  - If it's in a custom location, set the executable path (and, if needed, environment variables) in **Settings → Tools → Claude Code**
- **Auth** reused from the binary (Claude subscription / OAuth or `ANTHROPIC_API_KEY`)

## Installation

**From the JetBrains Marketplace** (recommended):

1. In the IDE: **Settings → Plugins → Marketplace**
2. Search for **"Claude Code Native"**
3. Install and restart

The Marketplace listing tracks the latest release. This GitHub repository is the **source of truth for the code** — releases are published to the Marketplace, not as binary attachments here.

**From source:** see [Build from source](#build-from-source) below.

## Usage

Open the **Claude Code** tool window (right side panel, same area as AI Assistant). Each tab is an independent chat session backed by its own `claude` process.

| Shortcut | Action |
|---|---|
| Enter | Send message |
| Shift+Enter | New line in composer |
| Ctrl+K | Slash-command palette |
| Ctrl+O | Toggle extended thinking |
| Esc | Interrupt running agent |

- **Model / Mode / Effort chips** — click to change at any time
- **Gear icon** — advanced settings (thinking tokens, allowed tools, etc.)
- **New Chat button** — opens a fresh tab

File edit proposals open as a diff tab in the editor; an inline Accept/Reject card in the chat lets you review without leaving the conversation.

### IDE tools (MCP) — optional

Let Claude query the IDE directly (diagnostics, open files, usages, …) via JetBrains' own MCP server. It is **off by default** and takes two steps:

1. **Enable JetBrains' MCP Server plugin.** Install/enable the bundled **MCP Server** plugin (Settings ▸ Plugins) and confirm it is running.
2. **Turn it on here.** Go to **Settings ▸ Claude Code ▸ IDE tools (MCP)**, tick *Enable JetBrains IDE tools (MCP)*, pick the **transport** (`sse` is the default; `streamable-http` is the alternative) and set the **port** if you changed it from `64342`. Apply, then start a **new chat** (the setting is applied when the `claude` process launches).

For the `stdio` transport or a remote server, choose **custom** and paste the server JSON from JetBrains into the box. This also works unchanged on Windows (the pasted config carries the right paths and ports for your install).

> ⚠ **Security:** `sse`/`streamable-http` use JetBrains' localhost endpoint, which any process on your machine can reach; `stdio` launches a helper process instead. Enable only on a machine you trust. Every IDE tool call is still gated by the in-chat permission prompt.

## Build from source

Requires JDK 21. The Gradle wrapper is included.

```bash
JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew buildPlugin
```

Output: `build/distributions/claude-code-native-4.1.0.zip`

```bash
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # IntelliJ plugin verifier
```

## How it works

The plugin speaks **directly with the `claude` binary** over its `stream-json` + control stdio protocol — no Node.js or TS SDK at runtime. One long-lived process per chat session handles streaming input/output. The TS SDK package (`node_modules/@anthropic-ai/claude-agent-sdk/`) is included as a **protocol reference only** and is not distributed.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture, protocol details, and verified empirical facts about the binary's behavior.

## Status

**v4.1.0** — **editable diff review for edits**: when Claude asks to Edit/Write/MultiEdit, the plugin auto-opens an **editable** diff in the IDE (Current | Proposed) on the permission request — not just in acceptEdits/bypass mode. You can **tweak the proposed change right in the editor** before approving; **Accept writes your edited version** (fail-safe to Claude's original proposal when you change nothing), the transcript inline diff and "View diff" reflect the **real** written change, and the diff closes automatically on accept/reject. **v4.0.5** — the permission card for an edit now shows a **read-only colour diff** (red removed / green added) instead of per-line checkboxes: edits are **atomic** (accept/reject the whole change), since applying an incoherent subset of an edit reliably broke code. **v4.0.4** — a broad **bug-fix + UX pass** (protocol re-baselined to `claude` 2.1.193 / SDK 0.3.193): the **interrupt** actually stops the turn now (no more looping "Interrupting…"), **first-open dead chat** self-heals (no reopen-the-tab workaround), **user prompts render verbatim** (never Markdown), the code-block **Copy** button works, duplicate/out-of-order **"Thought process"** fixed, menu **flicker/de-selection** during streaming gone, **adaptive thinking on by default**, a **responsive** composer/find/chips, a faster Vibe Mode, plus latent concurrency/lifecycle fixes (double-spawn guard, `dispose()` generation bump, `can_use_tool` decode can't hang, per-project tool window). **v4.0.3** — **clipboard paste fixed in the composer on native-Wayland Linux**: under `sun.awt.wl.WLToolkit` the embedded CEF browser's clipboard is isolated from the system clipboard, so `Ctrl+V` only pasted things copied inside the chat. The composer now routes paste through the host (which reads via `wl-paste`/`xclip`, the same path the Attach→Image button used), so text and images from any app paste correctly. **v4.0.2** — added the host-side `wl-paste`/`xclip` clipboard *read* fallback for native Wayland (where AWT's clipboard read is broken); the user-visible paste fix landed in 4.0.3. **v4.0.1** — **protocol upgrade to `claude` 2.1.170 / SDK 0.3.170**: the drift detector flagged four new protocol kinds, all reconciled. The new `system/model_refusal_fallback` message is recognised and surfaced as a transcript notice (the primary model declined and the turn was retried on a fallback model — previously dropped silently); the `get_usage` / `register_repo_root` / `reload_skills` host→binary control requests are triaged into the known surface. Backward-compatible with older binaries. **v4.0.0** — **the chat UI was rebuilt on JCEF (embedded Chromium)**: a modern streaming transcript, a web composer (attachments, image drag-drop & paste), native permission / question / elicitation cards, and a session dashboard (context breakdown, cost, account, MCP health, subagents) — all an inlined web app under a strict CSP (no CDN), with diffs still native via the IDE's `DiffManager`. The old Swing chat UI and its tests were removed. **Requires JetBrains 2025.2+ (build 252+) with JCEF.** A follow-up **expert-consensus review** then hardened the new surface: hunk-by-hunk partial accept re-reads disk and falls back to a full accept if the file changed (no stale writes), the hunk cache can't leak, large files skip the EDT hunk-diff, the `sms:` link scheme is restored alongside `data:image/`/`jb:` (with `data:text/html` still blocked), and the rewind-fallback dialog moved off a deprecated API for a zero-deprecation, `verifyPlugin`-Compatible build. **v3.3.0** — **the full binary→host protocol surface, mapped into the UI**: native MCP **elicitation** cards (URL gated to http/https, or a form built from the request's schema) and correct `request_user_dialog` handling (both previously errored), a clickable **predicted-next-prompt** chip, a **live reasoning-token** estimate, evolving **hook-execution** rows, a **memory-recall** row, and tool-use-summary / file-upload notices. Adds an on-demand **protocol drift detector** (`./gradlew checkDrift`) that updates the SDK + binary to latest and reports any unmodeled protocol kind. Protocol surface verified against SDK 0.3.162 / `claude` 2.1.162. **v3.2.1** — provider selector (Anthropic / DeepSeek) with per-provider isolated keys, and a reasoning-toggle persistence fix. **v2.2.2** (test pyramid + gated UI suite) — completes the automated **test pyramid** (unit → headless `BasePlatformTestCase` → integration against a `fake-claude` stand-in → RemoteRobot UI) and the **maintenance workflow**: CI, tag-driven release/publish, nightly UI tests, SDK/binary drift detection, plus `SECURITY.md`/`CONTRIBUTING.md`/docs. No runtime change vs. 2.2.0. **v2.2.0** — Marketplace-publishable again: migrated the bundled MCP plugin lookup off the internal `findEnabledPlugin` API. **Model picker reflects the binary's actual options** by their human label (`Default (recommended)`/`Sonnet`/`Haiku`) and refreshes live when `initialize` lands; default model now `default` (binary chooses recommended tier). **Path:line links work inside backticks** (`` `src/Foo.kt:42` `` becomes a clickable monospaced link, still project-confined). Protocol surface bumped to SDK 0.3.161 / `claude` 2.1.161 (`ModelInfo` carries effort/thinking/fast/auto capabilities, `AccountInfo` carries provider; new `system/*` events tolerated). Built on **v2.1.0**: persistent diff from the transcript ("View diff" on every edit card, in any permission mode), hunk-by-hunk partial acceptance, wrapped AskUserQuestion options, improved Markdown (strikethrough, task lists, nested lists), "Explain with Claude" editor action + jump-to-code links (project-confined), "Always allow" per tool (revocable in Settings), and background-session notifications with tab badges (suppressed only for the chat on screen; "Open" dismisses the notification). **Session history reads the binary's own session files** as the source of truth — real titles (as `--resume`), "Open Previous Session…" lists the project's sessions, and on startup the open tabs (or the most recent session) are restored; the plugin persists no transcripts, only the open-tab ids in `workspace.xml`. Internally, permission-mode/effort/transport are now typed enums. Builds on v2.0.x: reliability & security hardening (EDT-freeze fix, in-flight control resolution + watchdog, project-root write confinement, trust-on-open gate) and opt-in IDE tools over MCP. As of 4.0.0, supported on JetBrains IDEs build 252 (2025.2) through the latest EAP/RC.

See [`RELEASE_NOTES.md`](RELEASE_NOTES.md) for the full changelog.

## Disclaimer

Unofficial, community-built, open-source plugin. **Not affiliated with, sponsored by, or endorsed by Anthropic or JetBrains.** It requires the user's own separately-installed `claude` CLI and their own Claude subscription or API key — no credentials are bundled or provided.

"Claude" and "Claude Code" are trademarks of Anthropic; "JetBrains", "IntelliJ", "PyCharm" and related names are trademarks of JetBrains s.r.o. Used here for identification only.

## License

Licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE) for the full text.
