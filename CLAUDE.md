# Claude Code for JetBrains — native plugin

IntelliJ Platform plugin (Kotlin/Swing) that integrates Claude Code into JetBrains IDEs, with a rich native GUI (streaming chat, permission-gated diff review, plan-mode, sessions, IDE intelligence fed to the agent). Goal: surpass AI Assistant and the official plugin (currently just a terminal launcher). Built to present to Anthropic.

## Core decision
**No Node or TS SDK at runtime.** Speaks Kotlin/JVM **directly with the `claude` binary** via `stream-json` + control over stdio. The TS SDK is just a wrapper that spawns the same binary; we replicate it in Kotlin. `node_modules/@anthropic-ai/claude-agent-sdk/` (`sdk.d.ts`, `sdk-tools.d.ts`, `sdk.mjs`) is kept **as protocol reference only**, not distributed.

Decisions: (1) **Swing**, not JCEF; diffs via the IDE's `DiffManager`. (2) **Preinstalled `claude` binary required** (PATH + `~/.local/bin` detection; if missing → actionable notification and abort). (3) **Auth reused** from the binary (subscription/OAuth or `ANTHROPIC_API_KEY`).

**Behavioral principle:** UX parity with the original Claude Code, consuming the SDK's **structured protocol** (`stream-json`/control) — "using the SDK" means its contract, not the npm package. **Never mirror raw CLI output**: do not dump terminal-formatted text; reconstruct every state/command **natively** from the event's structured fields (e.g. compaction from `status`/`compact_metadata`, cost from `get_session_cost`). `system/local_command_output` is the antipattern to avoid.

## Protocol (stream-json + control)
One process per session, kept alive in streaming-input mode. Key flags (`--print` is **mandatory**):
```
claude --print --output-format stream-json --input-format stream-json --verbose \
       --permission-prompt-tool stdio [--include-partial-messages] [--permission-mode <m>] \
       [--model <m>] [--resume <id>] [--allowedTools …] [--setting-sources user,project,local]
```
- stdin: `{"type":"user","message":{"role":"user","content":"…"},"parent_tool_use_id":null}` (one line = one JSON). `cwd`=project root, `env` inherited.
- stdout: `system/init` (carries `session_id`, `slash_commands`), `assistant` (content blocks), `stream_event` (deltas), `result` (end of turn), `keep_alive` (ignore), control frames.
- **Control** (correlated by `request_id`): the binary emits `control_request{subtype:"can_use_tool",tool_name,input,title}` → host responds `control_response{subtype:"success",response:{behavior:"allow",updatedInput}}` or `{behavior:"deny",message}`. Host→binary: `initialize`, `interrupt`, `set_model`, `set_permission_mode`, `get_context_usage`, `get_session_cost`, `mcp_status`.

**Who writes the file:** on `allow`, **the binary writes** (not the IDE). Therefore, before approving Edit/Write we reconstruct the proposed content and open a **diff in an editor tab** (`SimpleDiffRequest`→`ChainDiffVirtualFile`→`FileEditorManager.openFile`; NOT `DiffEditorTabFilesManager.showDiffFile`, which opens a window). Approval = **inline non-modal card** (Accept/Reject/View diff), never dialogs. After writing, refresh VFS (`VfsUtil.markDirtyAndRefresh`).

## Architecture (`src/main/kotlin/dev/lain/claudejb/`)
- `process/` — `ClaudeBinaryLocator` (locate/validate, cross-platform incl. Windows) + `ClaudeProcess` (GeneralCommandLine + KillableColoredProcessHandler, stdio, graceful kill) + `EnvScriptLoader` (sources a shell script to seed the process env).
- `protocol/` — kotlinx.serialization models + `ProtocolParser` (NDJSON→`ClaudeEvent`) + `ControlProtocol` (output builders). Lenient decoding (ignoreUnknownKeys).
- `session/` — `ClaudeSession` (one per tab: process, `session_id`, queue, observable transcript, permissions, rate-limit, live tokens) + `ChatSessionManager` (`@Service(PROJECT)`, owns the tabs).
- `settings/ClaudeSettings` — `@Service(PROJECT)` `PersistentStateComponent` (`claude-code.xml`): persists model·effort·permissionMode·thinking·allowed/disallowedTools·settingSources·claude/nodePath·envVars·sourceScript. `applyTo(session)` seeds launch options before `start()`.
- `permission/PermissionBroker` — receives `can_use_tool`, **does not block**; auto-approves (bypass/acceptEdits) or hands off a `PendingPermission` to the UI. Actual resolution in `ClaudeSession.resolvePermission`/`resolveQuestion`.
- `diff/DiffPresenter` — `openDiff`/`revealDiff`/`closeDiff` in the editor area (no modals).
- `context/EditorContextProvider` — file/selection/diagnostics for @-mentions.
- `ui/` — `ClaudeToolWindowFactory` (tabs + New Chat) + `ChatPanel` (streaming transcript + composer with model·mode·effort·thinking chips + quota bar + spinner/tokens) + `ChatMessageViews`/`TranscriptView`/`MarkdownRenderer`/`ChatTheme` + `CommandPalette` + `OptionMenus` + `InfoDialogs` + `ClaudeSettingsConfigurable` (settings UI).
- IDE tools (**opt-in**, implemented): no in-house MCP server; we drive **JetBrains' MCP Server plugin** via `--mcp-config`. Two independent settings (`ClaudeSettings.ideMcp*`/`customMcpServers` + `ClaudeSettingsConfigurable`): (1) **Enable JetBrains MCP server** — toggle + transport combo (`sse`/`streamable-http`/`stdio`) + port; sse/streamable-http synthesize the localhost endpoint, **`stdio` is built from the running IDE** (`PluginManagerCore.plugins` → locate bundled `mcpserver` lib, JBR `java` from `java.home`, classpath via the JVM `dir/*` wildcard so jar names aren't hardcoded, `IJ_MCP_SERVER_PORT` env). (2) **Custom MCP servers** — a JSON object (`name → server`) for N arbitrary servers. `ClaudeSession.mcpConfigJson()` merges both under `{"mcpServers": …}` (jetbrains key + custom); flag passed only when there's something to register (invalid custom JSON → `ConfigurationException` on save / skipped at launch). Off by default; settings UI shows **security warnings**. Two-step setup (enable JetBrains' MCP plugin → enable + pick transport here) in README. Tools still gated by `can_use_tool`. We do not depend on it. NB: KDoc must avoid a literal `/*` (Kotlin block comments nest → "unclosed comment").

Threading: I/O and parsing on `Dispatchers.IO`; UI on EDT/`invokeLater`. plugin.xml: `toolWindow id="Claude Code"` anchor=right, `notificationGroup`, `projectService`, `projectConfigurable`.

## Stack & build
IntelliJ Platform Gradle Plugin **2.16.0** (requires Gradle ≥9 → wrapper at **9.5.1**). Kotlin **2.1.20** + serialization, toolchain **JDK 21** (ceiling: the IDE runs on JBR 21). Target `IC 2025.1`, since=243 until=261.*. Runtime: `kotlinx-serialization-json:1.7.3` (stdlib/annotations excluded from the bundle, provided by the platform).
Build: `JAVA_HOME=~/.local/jdks/jdk-21.0.11+10 ./gradlew buildPlugin` → zip in `build/distributions/`. Also `verifyPlugin`, `runIde`. Install: Settings → Plugins → ⚙ → Install Plugin from Disk.

## Status
Package `dev.lain.claudejb`, plugin id `dev.lain.claude-code-for-jetbrains`, name **"Claude Code Native"**, version **2.0.0** (released on Marketplace). MVP + GUI complete and building clean (`verifyPlugin` Compatible with IU-261); first unit-test suite (80 tests, JUnit 5) covering protocol parse/build, diff reconstruction, transcript hierarchy, rate-limit math and env parsing. Implemented: protocol+transport, multi-chat with queue, permissions+native diff, AskUserQuestion, markdown tables, auto-diff on acceptEdits/bypass, multi-line commands, Ctrl+O reasoning, quota bar + spinner/tokens, menus that close on selection, `/btw`, UI rethemed to IDE theme, **Windows support**, **persistent settings** (model/mode/effort/thinking/tools/env via `ClaudeSettings` + settings UI), **plugin is the source of truth for `permissionMode`** (system/init no longer resets the live chip — `ClaudeSession` re-asserts it via `set_permission_mode` when the binary diverges). `claude` 2.1.150 at `~/.local/bin/claude`.

v2.0.0 hardening: EDT-freeze fix on start (env resolution + spawn off-EDT, cached), pendingControl drained on stop/crash, 30s control-request watchdog, start-failure surfaced, auto-writes confined to project root, trust-on-open gate for source script / custom stdio MCP, safe source-script quoting, plaintext-env warning in Settings.

Pending: persistent per-tool "always allow", inline edits, enum refactor for permissionMode/transport/effort (currently free strings).

## Empirically verified protocol facts (2.1.150)
- `--print` required alongside stream-json in/out. `--permission-prompt-tool stdio` confirmed.
- `initialize` (handshake) returns `commands`/`models`/`agents`/`available_output_styles`/`account`. Does **not** include `/btw` (client-side REPL, intercepted by regex) → the plugin adds it to the palette and sends it with `sendSideQuestion`.
- `system/init` arrives **every turn** (after each user message), reporting the current `permissionMode`. Do NOT block the session waiting for it (historic deadlock): `start()` sets `ready=true` at startup.
- **`AskUserQuestion` comes through `can_use_tool`** (not `request_user_dialog`): `input:{questions:[{question,header,options:[{label,description,preview?}],multiSelect}]}`; host responds `allow` with `updatedInput={...input,"answers":{question:label}}` (comma-separated if multiSelect). Without `answers` the response is empty and the model improvises.
- `rate_limit_event` carries `status`/`resetsAt`/`rateLimitType`/`isUsingOverage`; `utilization` (quota %) **only near the limit**. `get_session_cost` → `{text}` with cost in **$** (API), not quota %.
- Live tokens: `stream_event` `message_delta.usage.output_tokens` (accumulated for the message).
- The binary **accumulates** multiple user messages received at once / mid-turn and processes them together (shared context) → the queue drains entirely, not one message per turn.

## References
- Protocol (local truth): `node_modules/@anthropic-ai/claude-agent-sdk/{sdk.d.ts,sdk-tools.d.ts,sdk.mjs}`.
- Docs: https://code.claude.com/docs/en/agent-sdk/overview · IntelliJ Platform SDK https://plugins.jetbrains.com/docs/intellij/ · official plugin https://code.claude.com/docs/en/jetbrains.
