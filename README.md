# Claude Code Native

A native IntelliJ Platform plugin that integrates [Claude Code](https://claude.ai/code) into JetBrains IDEs — not as a terminal wrapper, but as a first-class GUI client with streaming chat, native diff review, and full protocol-level access to the `claude` binary.

> **Goal:** surpass AI Assistant and the official plugin (currently just a terminal launcher). Built to present to Anthropic.

## Features

- **Streaming chat** — token-by-token rendering in a native Swing transcript, multi-chat tabs
- **Collapsible tool calls** — each tool card folds its output via a disclosure triangle; outputs anchor under their own call
- **Nested subagents** — `Task`/Agent activity (its tool calls, outputs and text) nests and indents under the Agent, collapsing hierarchically
- **Permission-gated diff review** — Edit/Write proposals shown as an in-editor diff tab with inline Accept/Reject cards (no modal dialogs)
- **Full slash-command palette** — every command from the `initialize` handshake, plus client-side `/btw` (Ctrl+K)
- **Model / effort / permission-mode / thinking controls** — live chips in the composer, no restart needed
- **Multi-prompt queue** — send follow-ups while the agent is still working; queued messages shown in the UI
- **`AskUserQuestion` support** — multi-select option cards rendered natively in the transcript
- **Quota bar** — subscription usage % shown when near the usage limit, with reset countdown
- **Live token counter** — per-message output tokens shown in the status line while the agent thinks
- **Markdown rendering** — bold, inline code, code blocks, tables
- **IDE-themed UI** — surfaces, text, and borders follow the active IDE theme (light/dark)

## Requirements

- **JetBrains IDE** 2024.3 – 2025.1.x (IntelliJ IDEA, PyCharm, GoLand, WebStorm, …)
- **`claude` CLI** installed and accessible on `PATH` or a typical location (Linux/macOS: `~/.local/bin`; Windows: npm, scoop, volta, chocolatey, `~\.local\bin`)
  - Install: `npm install -g @anthropic-ai/claude-code` or follow [claude.ai/code](https://claude.ai/code)
  - If it's in a custom location, set the executable path (and, if needed, environment variables) in **Settings → Tools → Claude Code**
- **Auth** reused from the binary (Claude subscription / OAuth or `ANTHROPIC_API_KEY`)

## Installation

**From a pre-built zip:**

1. Download `claude-code-for-jetbrains-1.3.1.zip` from [Releases](../../releases)
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the zip and restart

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

## Build from source

Requires JDK 21. The Gradle wrapper is included.

```bash
JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew buildPlugin
```

Output: `build/distributions/claude-code-for-jetbrains-1.3.1.zip`

```bash
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # IntelliJ plugin verifier
```

## How it works

The plugin speaks **directly with the `claude` binary** over its `stream-json` + control stdio protocol — no Node.js or TS SDK at runtime. One long-lived process per chat session handles streaming input/output. The TS SDK package (`node_modules/@anthropic-ai/claude-agent-sdk/`) is included as a **protocol reference only** and is not distributed.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture, protocol details, and verified empirical facts about the binary's behavior.

## Status

**v1.3.1** — Windows support (binary detection + npm shim handling), configurable executable paths and environment variables; default model Opus 4.7, default effort medium. Verified compatible with IntelliJ IDEA 2024.3 – 2026.1.

See [`RELEASE_NOTES.md`](RELEASE_NOTES.md) for the full changelog.

## Disclaimer

Unofficial, community-built, open-source plugin. **Not affiliated with, sponsored by, or endorsed by Anthropic or JetBrains.** It requires the user's own separately-installed `claude` CLI and their own Claude subscription or API key — no credentials are bundled or provided.

"Claude" and "Claude Code" are trademarks of Anthropic; "JetBrains", "IntelliJ", "PyCharm" and related names are trademarks of JetBrains s.r.o. Used here for identification only.

## License

Licensed under the **GNU General Public License v3.0**. See [`LICENSE`](LICENSE) for the full text.
