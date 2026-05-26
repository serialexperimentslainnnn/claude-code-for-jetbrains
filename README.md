# Claude Code for JetBrains

A native IntelliJ Platform plugin that integrates [Claude Code](https://code.claude.com) into JetBrains IDEs — not as a terminal wrapper, but as a first-class GUI client with streaming chat, native diff review, and full protocol-level access to the `claude` binary.

> **Goal:** surpass AI Assistant and the official plugin (currently just a terminal launcher). Built to present to Anthropic.

## Features

- **Streaming chat** — token-by-token rendering in a native Swing transcript, multi-chat tabs
- **Permission-gated diff review** — Edit/Write proposals shown as an in-editor diff tab with inline Accept/Reject cards (no modal dialogs)
- **Full slash-command palette** — every command from the `initialize` handshake, plus client-side `/btw`
- **Model / effort / permission-mode / thinking controls** — live chips and gear menu, no restart needed
- **Multi-prompt queue** — send follow-ups while the agent is still working
- **Quota bar + live tokens** — real-time subscription usage and per-message token counter
- **`AskUserQuestion` support** — multi-select option cards rendered natively in the transcript
- **Markdown rendering** — bold, code blocks, tables
- **IDE-themed UI** — surfaces, text, and borders follow the active IDE theme (light/dark)

## Requirements

- **JetBrains IDE** 2024.3+ (IntelliJ IDEA, PyCharm, GoLand, WebStorm, …)
- **`claude` CLI** installed and accessible — the plugin requires the binary at startup
  - Install: `npm install -g @anthropic-ai/claude-code` or follow [claude.ai/code](https://code.claude.com)
  - The plugin looks in `PATH` and `~/.local/bin`
- **Auth** is reused from the binary (Claude subscription / OAuth or `ANTHROPIC_API_KEY`)

## Installation

**From a pre-built zip (recommended):**

1. Download the latest zip from [Releases](../../releases)
2. In the IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the zip and restart

**From source:** see [Build](#build-from-source) below.

## Usage

Open the **Claude Code** tool window (right side panel, same area as AI Assistant). Each tab is an independent chat session backed by its own `claude` process.

- **New Chat** — opens a fresh tab
- **Send** — submits the composer (Enter key, or Shift+Enter for newlines)
- **Ctrl+/** — slash-command palette
- **Ctrl+O** — toggle extended thinking
- **Gear icon** — model, effort, permission mode, thinking settings
- **Stop** — interrupts the running agent

File edit proposals appear as a diff tab in the editor; an inline card lets you Accept or Reject without leaving the chat.

## Build from source

Requirements: JDK 21, Gradle (the wrapper is included).

```bash
./gradlew buildPlugin
```

The plugin zip is written to `build/distributions/`. Load it via **Settings → Plugins → ⚙ → Install Plugin from Disk…**.

```bash
./gradlew runIde        # launch a sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # run the IntelliJ plugin verifier
```

## How it works

The plugin speaks **directly with the `claude` binary** over its `stream-json` + control stdio protocol — no Node.js or TS SDK at runtime. One long-lived process per chat session handles streaming input/output. The TS SDK package (`node_modules/@anthropic-ai/claude-agent-sdk/`) is included only as a protocol reference and is not bundled.

See [`CLAUDE.md`](CLAUDE.md) for the full architecture, protocol details, and verified empirical facts about the binary's behavior.

## Status

**v0.1.1 — MVP complete.** Core protocol transport, multi-chat, permissions + native diff, all UI controls, quota display, and markdown rendering are working. Verified compatible with IntelliJ IDEA 2024.3 – 2026.1.

**Pending:** fix permission-mode reset on `system/init`, persist chip state, IDE tools MCP server, persistent "always allow", inline edits.
