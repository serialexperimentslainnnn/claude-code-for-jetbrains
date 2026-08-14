# Map of `session/`

> Part of the distributed map. **Root: [`../../../../../../../PROJECTMAP.md`](../../../../../../../PROJECTMAP.md)**
> — repository-wide commands, invariants and the index of every other directory map live there.

## What lives here

A running conversation. One `ClaudeSession` per chat tab owns the process, the session id, the send queue, the
observable transcript and the listeners — and **delegates everything else**. This is the largest package in
the repository and the one most likely to be read whole by accident; the groups below exist so you do not
have to.

`ClaudeSession` is a **thin orchestrator**. Its `onEvent` is a dispatch table that routes each `ClaudeEvent`
to a collaborator. Adding behaviour here means adding it to the right collaborator, or writing a new one.

## Files, by job

**Orchestration and the turn**

| File | What it decides |
|---|---|
| `ClaudeSession.kt` | The orchestrator: process, queue, transcript, listeners, the `edt {}` dispatcher, and the `onEvent` dispatch. |
| `SessionLauncher.kt` | The argv: `buildArgs` from an immutable `LaunchOptions` snapshot, plus the MCP config JSON. |
| `SessionControlClient.kt` | Correlation by `request_id`, the watchdog, and failing every pending request on teardown. |
| `ControlAsks.kt` | **The catalogue of questions asked of the binary.** One `Ask` per request: what to send, how to read the reply. |
| `SessionQueries.kt` | The read-only surface the UI asks a session, and the plumbing every `Ask` rides on. |
| `ChatSessionManager.kt` | Project service that owns the tabs and creates sessions, the Git chat included. |
| `SessionListener.kt` | The listener contract and `AttentionReason`. |
| `ClaudeEnums.kt` | `PermissionMode`, `EffortLevel`, `McpTransport` — one source of truth for the GUI lists and the wire strings. |
| `McpConfigBuilder.kt` | The merged `mcpServers` document: the JetBrains server plus any custom ones. |
| `RemoteMounts.kt` | Which roots are remote, and the startup gate that refuses a remote-mounted project. |

**The transcript**

| File | What it decides |
|---|---|
| `TranscriptModel.kt` | The rows themselves, the cap, and the tool-use index. Assumes EDT. |
| `TranscriptReconciler.kt` | Streaming: appending and finalizing assistant text and thinking, message boundaries, subagent text. |
| `NoticeNarrator.kt` · `SubagentNotice.kt` | Transcript notices for the binary's own signals. |
| `HookActivityNarrator.kt` | The binary's hook **telemetry** as one evolving row per hook id. |
| `MemoryRecallFormatter.kt` · `StatusLineFormatter.kt` | Pure formatters: a memory recall, and the live thinking-token status suffix. |
| `SyntheticUserText.kt` | Which `user` lines are the binary's own bookkeeping rather than the user speaking. |
| `ToolNaming.kt` | The tool's label, its file path, and its command text. |

**Identity**

| File | What it decides |
|---|---|
| `AuthGate.kt` | Whether this session has a credential, whether it needs login, and the boot state derived from that. |
| `LoginCoordinator.kt` | The whole OAuth sign-in subsystem, which has nothing to do with running a turn. |
| `LoginDetection.kt` | Reactive detection: a failed turn or an auth error that means "signed out". |

**The agent tree**

| File | What it decides |
|---|---|
| `AgentRegistry.kt` | Admission — which agents are ours — and the tree itself. |
| `AgentScanner.kt` | When the on-disk agent tree is re-read. |
| `AgentMeta.kt` | The binary's sidecar (`agentType`, `description`, `toolUseId`, `parentAgentId`, `spawnDepth`). |
| `AgentEnding.kt` | A transcript's records → a settled status. |
| `PluginAgentIndex.kt` | The plugin's own record of which agents it has seen, outside `.idea/`. |
| `WorkloadWindow.kt` | Which workloads the dashboard's diagram shows. |

**Background tasks**

| File | What it decides |
|---|---|
| `BackgroundTaskRegistry.kt` | The plugin's OWN record of tasks. The binary's signal marks liveness and **never creates**. |
| `TaskTracker.kt` | Subagent tasks, derived from `task_started`/`progress`/`updated`/`notification` edges. |
| `TaskOutputFile.kt` · `LiveOutputTail.kt` | Where a backgrounded task's output file is, and tailing it by offset. |
| `BackgroundTaskReplay.kt` | Rebuilding tasks from the session JSONL after a restart, since nothing survives in memory. |

**Session history (read-only)**

| File | What it decides |
|---|---|
| `SessionStore.kt` | Paths into the binary's own files, behind a UUID-shaped traversal guard. **Reads only; deletes nothing.** |
| `SessionTranscriptReader.kt` | JSONL → transcript entries. **The only JSONL parser in the repository.** |
| `SessionTitleReader.kt` | What a chat is called, in one place so the live title and the restored tabs cannot disagree. |
| `SessionHistory.kt` · `LegacySessionHistory.kt` · `LegacyModels.kt` | The ordered open-tab ids, and the legacy shapes still adopted. |

**Diffs, rollback and everything else**

| File | What it decides |
|---|---|
| `DiffLifecycleManager.kt` | Capturing for review, auto-opening, snapshotting, and refreshing the VFS for exactly the paths touched. |
| `RollbackManager.kt` | IDE-side undo of one edit. |
| `WorkspaceDiffReview.kt` | The whole session's changes: hunks applied **backwards** to reconstruct the base side. |
| `PermissionCardManager.kt` | The EDT-confined queue of pending permission cards. |
| `HookBroker.kt` | Host-side hook decisions and their side effects. |
| `TokenAccountant.kt` | The token counters and how a turn's usage folds into the session's. |
| `QuotaWarnings.kt` | The once-per-threshold plan-limit announcements. |

<!-- MAP:GENERATED BEGIN -->
<!-- Generated by scripts/gen-projectmap.py. Everything between these markers is overwritten on the
     next run; the prose outside them is not. `./gradlew checkProjectMap` fails when they disagree. -->

## Symbols — go to the line, the code is the documentation

Top-level declarations and the members of top-level `object`s. `private` and `override` are not
indexed, and neither are extensions: they are called on their receiver, not on their owner.

| Symbol | Kind | Where | Owns |
|---|---|---|---|
| `AgentEnding` | object | `AgentEnding.kt:27` | What an agent's own transcript says about whether it is over — the only evidence there is about an agent the plugin … |
| `AgentEnding.of` | fun | `AgentEnding.kt:47` | `null` when there is nothing to judge (no transcript yet, or nothing parseable). |
| `AgentMeta` | class | `AgentMeta.kt:24` | What the binary itself records about one subagent, read from `subagents/agent-<id>.meta.json`. |
| `AgentStatus` | class | `AgentRegistry.kt:8` | Lifecycle of one agent, as far as the plugin can honestly tell. |
| `AgentNode` | class | `AgentRegistry.kt:14` | One agent as the UI needs it: what the binary says about it ([meta]), how it ended ([status]), its reconstructed … |
| `AgentRegistry` | class | `AgentRegistry.kt:85` | The agents of one chat: which ones may be shown, their tree, their status and their transcripts. |
| `AgentScanner` | class | `AgentScanner.kt:20` | Keeping the agent tree and the background tasks in step with what is on disk. |
| `Credential` | class | `AuthGate.kt:23` | What is known about this session's identity, as opposed to what could be found out by asking the binary. |
| `AuthGate` | class | `AuthGate.kt:49` | Who this session runs as: whether we hold an identity at all, whose it is, and keeping it alive. |
| `BackgroundTaskRegistry` | class | `BackgroundTaskRegistry.kt:47` | Every background task this session has seen: what it is, who started it, and whatever output came back. |
| `BackgroundTaskReplay` | object | `BackgroundTaskReplay.kt:31` | Rebuilds a session's background tasks — and their output — from the binary's own transcript file. |
| `BackgroundTaskReplay.parse` | fun | `BackgroundTaskReplay.kt:62` | Every background task named anywhere in [lines], in the order they first appear. |
| `ChatSessionManager` | class | `ChatSessionManager.kt:18` | Project-level owner of the open chat tabs. |
| `PermissionMode` | class | `ClaudeEnums.kt:12` | Typed vocabularies for the three settings that used to be free strings (permission mode, effort, MCP transport). |
| `EffortLevel` | class | `ClaudeEnums.kt:32` |  |
| `McpTransport` | class | `ClaudeEnums.kt:45` |  |
| `ClaudeSession` | class | `ClaudeSession.kt:78` | Owns the long-lived `claude` process for a project and is the single entry point the GUI talks to. |
| `RewindResult` | class | `ControlAsks.kt:21` | Result of a `rewind_files` control request. |
| `PlanInfo` | class | `ControlAsks.kt:32` | The session's plan-mode plan, as `get_plan` returns it. |
| `WorkspaceDiff` | class | `ControlAsks.kt:56` | The whole session's workspace diff, as `get_workspace_diff` returns it — one round-trip for the question "show me … |
| `Ask` | class | `ControlAsks.kt:140` | ONE control request, declared: what to ask for, what to send with it, and how to read the answer. |
| `Asks` | object | `ControlAsks.kt:155` | The control requests the plugin sends, in one place. |
| `Asks.CONTEXT_USAGE` | val | `ControlAsks.kt:169` | How much of the window the conversation is using, by category. |
| `Asks.USAGE` | val | `ControlAsks.kt:176` | Every rate-limit window plus the extra-credit balance, in one round-trip. |
| `Asks.SESSION_COST` | val | `ControlAsks.kt:179` | What this session has spent. |
| `Asks.MCP_STATUS` | val | `ControlAsks.kt:182` | MCP servers and their health. |
| `Asks.SETTINGS` | val | `ControlAsks.kt:185` | Effective merged settings + per-source breakdown (diagnostics dialog). |
| `Asks.WORKSPACE_DIFF` | val | `ControlAsks.kt:196` | Everything this session changed on disk, in one round-trip — the question the per-edit diffs cannot answer, and the … |
| `Asks.PLAN` | val | `ControlAsks.kt:210` | The session's plan-mode plan, on demand — in the transcript the plan is one card you scroll past. |
| `Asks.BINARY_VERSION` | val | `ControlAsks.kt:217` | The responder's CLI binary version (diagnostics dialog). |
| `Asks.rewind` | fun | `ControlAsks.kt:226` | Rewind tracked files to a turn anchor. |
| `DiffLifecycleManager` | class | `DiffLifecycleManager.kt:32` | Owns the full diff lifecycle of one [ClaudeSession]: capturing the pre-write snapshot of a reviewable … |
| `HookActivityNarrator` | class | `HookActivityNarrator.kt:17` | Turns the binary's native hook telemetry (system/hook_started → hook_progress → hook_response) into ONE evolving … |
| `HookBroker` | class | `HookBroker.kt:26` | Host-side decision engine for **hook callbacks** the `claude` binary invokes over the control channel. |
| `HookContext` | class | `HookBroker.kt:222` | Parsed, IDE-agnostic view of a single `hook_callback` request. |
| `HookDecision` | interface | `HookBroker.kt:241` | What the host wants the binary to do with this hook. |
| `interface` | fun | `HookBroker.kt:256` | A per-event handler. |
| `HookSideEffect` | interface | `HookBroker.kt:266` | IDE work the broker wants done, as data, so [ClaudeSession] applies it on the EDT. |
| `LegacyModels` | object | `LegacyModels.kt:31` | Previous-generation models, offered under "Other models" in the model picker. |
| `LegacyModels.ALL` | val | `LegacyModels.kt:40` | Newest first, grouped by family — the order they are shown in. |
| `LegacyModels.labelFor` | fun | `LegacyModels.kt:56` | The label for [value], or null when it is not one of ours — so callers can fall back to their own rule. |
| `LegacyModels.offeredAlongside` | fun | `LegacyModels.kt:65` | The entries worth offering given what the binary already lists, so a model can never appear twice. |
| `LegacySessionHistory` | class | `LegacySessionHistory.kt:27` | Reads the open-chat list where it used to live: `workspace.xml`, under the component name `ClaudeCodeSessionHistory`. |
| `LiveOutputTail` | class | `LiveOutputTail.kt:23` | Reads the part of a progress file that has appeared since the last read. |
| `LoginCoordinator` | class | `LoginCoordinator.kt:41` | Owns the OAuth sign-in flow, which is a whole subsystem in its own right and has nothing to do with running a chat … |
| `AuthFailure` | class | `LoginDetection.kt:9` | What an authentication failure from the binary means for the caller. |
| `LoginDetection` | object | `LoginDetection.kt:56` | Pure classifier over an error text from the binary (a failed `result`, or an `auth_status` error): is this an … |
| `LoginDetection.classify` | fun | `LoginDetection.kt:107` | Which kind of authentication failure [text] describes. |
| `LoginDetection.resolve` | fun | `LoginDetection.kt:128` | What the GUI must actually do about [text] — the answer every caller wants, and the only one that may decide whether … |
| `McpConfigBuilder` | object | `McpConfigBuilder.kt:21` | Pure (IDE-free) construction of the `--mcp-config` JSON, extracted out of [ClaudeSession] so the wire format can be … |
| `McpConfigBuilder.mcpConfigJson` | fun | `McpConfigBuilder.kt:39` | Builds `{"mcpServers": …}`, merging (when enabled) JetBrains' own server under the `jetbrains` key with the user's … |
| `McpConfigBuilder.jetbrainsMcpServer` | fun | `McpConfigBuilder.kt:56` | The JetBrains server object for the selected transport. |
| `McpConfigBuilder.httpMcpServer` | fun | `McpConfigBuilder.kt:62` |  |
| `McpConfigBuilder.stdioMcpServer` | fun | `McpConfigBuilder.kt:73` | Synthesizes the stdio server config from pre-resolved IDE paths: the JBR java, the bundled "mcpserver" plugin libs … |
| `McpConfigBuilder.customMcpServersObject` | fun | `McpConfigBuilder.kt:93` | Parses [customMcpServers] as a `name → server` JSON object; null if blank or not a valid object. |
| `MemoryRecallFormatter` | object | `MemoryRecallFormatter.kt:9` | Pure formatting of a memory_recall event into a short header summary and a markdown body listing each recalled memory … |
| `MemoryRecallFormatter.summary` | fun | `MemoryRecallFormatter.kt:14` | One-line summary for the row header, e.g. |
| `MemoryRecallFormatter.body` | fun | `MemoryRecallFormatter.kt:22` | Markdown bullet list of the recalled memories (one per line: scope, path, truncated snippet). |
| `NoticeNarrator` | class | `NoticeNarrator.kt:16` | Everything the binary says that is not part of a turn: refusals, uploads, plugin installs, denials, recalled memories, … |
| `PermissionCardManager` | class | `PermissionCardManager.kt:20` | Holds the queue of permission requests awaiting the user's Accept/Reject (rendered as inline chat cards), extracted … |
| `PluginAgentIndex` | class | `PluginAgentIndex.kt:51` | What belongs to a **plugin** session: every agent, subagent and background task it started, each with its parent and … |
| `QuotaWarnings` | class | `QuotaWarnings.kt:17` | Telling the user their quota is running out, once per threshold. |
| `RemoteMounts` | object | `RemoteMounts.kt:30` | Answers one question that turns out to be load-bearing: **is this path on a network / removable / foreign … |
| `RemoteMounts.snapshot` | fun | `RemoteMounts.kt:54` | The host snapshot, computed once. |
| `RemoteMounts.isRemote` | fun | `RemoteMounts.kt:62` | True when [path] lives on a network / removable / foreign filesystem — the check `ClaudeSession.start` gates on, and … |
| `RemoteMounts.parseMounts` | fun | `RemoteMounts.kt:97` | PURE: `/proc/mounts` text → the mount points and their fstypes. |
| `RemoteMounts.isUnc` | fun | `RemoteMounts.kt:123` |  |
| `RollbackManager` | class | `RollbackManager.kt:26` | IDE-side undo of ONE applied Edit/Write/MultiEdit call — what a transcript card's **Restore** falls back to when the … |
| `SessionControlClient` | class | `SessionControlClient.kt:37` | Owns the correlation of **host-initiated control requests** with the binary's `control_response` replies. |
| `SessionHistory` | class | `SessionHistory.kt:31` | Which chats were open, so they can be reopened on the next start. |
| `SessionLauncher` | object | `SessionLauncher.kt:22` | Pure(-ish) assembly of the `claude` process launch: the CLI argument vector and the `--mcp-config` JSON, lifted … |
| `SessionLauncher.binaryPermissionMode` | fun | `SessionLauncher.kt:63` | The mode the binary actually runs in. |
| `SessionLauncher.buildArgs` | fun | `SessionLauncher.kt:72` | Builds the CLI argument vector. |
| `SessionLauncher.appendSystemPromptFlags` | fun | `SessionLauncher.kt:121` | `--append-system-prompt <text>`: what the agent is told about the IDE it is running in (see [PluginContextPrompt]). |
| `SessionLauncher.mcpConfigJson` | fun | `SessionLauncher.kt:139` | Builds `{"mcpServers": …}` for `--mcp-config` by delegating to the pure [McpConfigBuilder] (testable without the IDE). |
| `SessionLauncher.resolveStdioParams` | fun | `SessionLauncher.kt:159` | Resolves the IDE-dependent inputs for the stdio transport: the JBR java, the bundled MCP Server plugin's lib dir, the … |
| `SessionLauncher.findMcpServerLib` | fun | `SessionLauncher.kt:167` | Searches the standard plugin roots for the MCP server's `lib/` directory; returns null if none found. |
| `AttentionReason` | class | `SessionListener.kt:4` | Why a background session is asking for the user's attention. |
| `SessionListener` | interface | `SessionListener.kt:7` | UI observer for session state and metadata changes. |
| `SessionQueries` | class | `SessionQueries.kt:24` | Everything the UI ASKS the binary on demand. |
| `SessionStore` | object | `SessionStore.kt:24` | Read-only access to the `claude` binary's own session transcripts — the single source of truth for past conversations. |
| `SessionStore.projectDir` | fun | `SessionStore.kt:33` | The binary's transcript directory for a project at [basePath]. |
| `SessionStore.encodePath` | fun | `SessionStore.kt:40` | The binary's folder-name encoding of an absolute cwd: every non-alphanumeric char → `-`. |
| `SessionStore.locate` | fun | `SessionStore.kt:46` | Locates `<sessionId>.jsonl` under any project dir (by its unique UUID — no cwd encoding needed). |
| `SessionStore.exists` | fun | `SessionStore.kt:60` | Whether the binary still has a transcript for [sessionId]. |
| `SessionStore.readLines` | fun | `SessionStore.kt:63` | Raw JSONL lines for [sessionId], or null if the file is absent/unreadable. |
| `SessionStore.sessionDir` | fun | `SessionStore.kt:74` | The binary's per-session sidecar directory, `<sessionId>/` next to `<sessionId>.jsonl`. |
| `SessionStore.subagentsDir` | fun | `SessionStore.kt:87` | `<sessionId>/subagents/`, the directory holding one transcript + metadata pair per subagent. |
| `SessionStore.listFiles` | fun | `SessionStore.kt:94` | Session transcript files for the project at [basePath], newest-first. |
| `SessionTitleReader` | object | `SessionTitleReader.kt:15` | Reads the human-readable session title the `claude` binary generates (the one shown by `--resume`) from its sidecar … |
| `SessionTitleReader.readTitle` | fun | `SessionTitleReader.kt:27` | Returns the binary's title for [sessionId], or null if no sidecar / no title line is found. |
| `SessionTitleReader.pickTitle` | fun | `SessionTitleReader.kt:42` | Picks the session title from raw JSONL lines, in order of authority: the last non-blank `customTitle` (the user's own … |
| `EntryDTO` | class | `SessionTranscriptReader.kt:21` | A flat transcript entry, decoded from the binary's own JSONL. |
| `SessionRef` | class | `SessionTranscriptReader.kt:57` | Lightweight handle to a past session: its id, the binary-issued title, the file mtime (newest-first sort key), and … |
| `SessionTranscriptReader` | object | `SessionTranscriptReader.kt:72` | Read-only reconstruction of a past conversation from the `claude` binary's transcript (the single source of truth — … |
| `SessionTranscriptReader.DEFAULT_RESTORE_CAP` | val | `SessionTranscriptReader.kt:85` | Conservative default cap for the restore path: reconstruct only the last this-many transcript entries of a very large … |
| `SessionTranscriptReader.readEntries` | fun | `SessionTranscriptReader.kt:92` | Decoded transcript for [sessionId], or empty if the sidecar is absent/unreadable. |
| `SessionTranscriptReader.parseEntries` | fun | `SessionTranscriptReader.kt:104` | Maps raw JSONL [lines] to the plugin's transcript model. |
| `SessionTranscriptReader.listSessions` | fun | `SessionTranscriptReader.kt:293` | Past sessions for [project], newest-first, capped at [MAX_LISTED_SESSIONS]. |
| `SessionTranscriptReader.parseMetadata` | fun | `SessionTranscriptReader.kt:313` | Scans raw JSONL [lines] for the first user prompt, the git branch and the earliest timestamp. |
| `StatusLineFormatter` | object | `StatusLineFormatter.kt:7` | Pure formatting for the composer status line. |
| `StatusLineFormatter.thinkingSuffix` | fun | `StatusLineFormatter.kt:20` | A compact suffix for the live reasoning-token estimate (system/thinking_tokens), or "" when there's nothing to show. |
| `SubagentNotice` | object | `SubagentNotice.kt:14` | The one line a finished subagent gets in the MAIN transcript. |
| `SubagentNotice.headline` | fun | `SubagentNotice.kt:26` | The first non-blank line of [summary], stripped of markdown ornament and capped at [MAX] characters on a word … |
| `SyntheticUserText` | object | `SyntheticUserText.kt:30` | Tells the user's own words apart from the binary's, inside a `user` line of a session transcript. |
| `SyntheticUserText.classify` | fun | `SyntheticUserText.kt:52` | Classifies one `text` block. |
| `TaskOutputFile` | object | `TaskOutputFile.kt:30` | Where a backgrounded command's output actually lives — for the moments the STRUCTURED field is not there. |
| `TaskOutputFile.parse` | fun | `TaskOutputFile.kt:39` | The output file [text] names, or null when it names none. |
| `TaskTracker` | class | `TaskTracker.kt:21` | Autonomous (no-IDE) state holder for subagent (Task tool) lifecycle events. |
| `TokenAccountant` | class | `TokenAccountant.kt:19` | Pure (no-IDE) token bookkeeping for a single chat session, extracted from [ClaudeSession]. |
| `ToolNaming` | object | `ToolNaming.kt:14` | How a tool call is named in the transcript, and what the IDE has to refresh afterwards. |
| `ToolNaming.BUILTIN_TOOLS` | val | `ToolNaming.kt:17` | Standard built-in tools, for the allow/deny checkboxes in Settings. |
| `ToolNaming.FILE_TOOLS` | val | `ToolNaming.kt:23` | Tools whose `file_path` names a project file the transcript can hyperlink (jump-to-code). |
| `ToolNaming.mayHaveWrittenUnknownFiles` | fun | `ToolNaming.kt:48` | True when [toolName] may have changed files we cannot name — so the IDE must re-scan the project tree rather than a … |
| `ToolNaming.toolFilePath` | fun | `ToolNaming.kt:60` | The tool call's file argument as a path **relative to [projectRoot]**, or null when the tool takes no file / the path … |
| `ToolNaming.relativizeToRoot` | fun | `ToolNaming.kt:67` | `/abs/root/src/Foo.kt` + root `/abs/root` → `src/Foo.kt`. |
| `ToolNaming.formatToolUse` | fun | `ToolNaming.kt:81` | Concise one-line representation of a tool call, mirroring the CLI's "Tool(arg)" bullets. |
| `Speaker` | class | `TranscriptModel.kt:6` | Who produced a transcript entry; drives styling in the chat panel. |
| `ToolState` | class | `TranscriptModel.kt:13` | Lifecycle of a tool call, reflected on its box: [LOADING] just dispatched (light blue), [RUNNING] actively executing — … |
| `TranscriptEntry` | class | `TranscriptModel.kt:16` | One renderable line of the conversation. |
| `TranscriptModel` | class | `TranscriptModel.kt:84` | Observable list of [TranscriptEntry]. |
| `TranscriptReconciler` | class | `TranscriptReconciler.kt:28` | Streaming reconciliation for a single session's top-level assistant output. |
| `WorkloadWindow` | object | `WorkloadWindow.kt:11` | The one place the "Show workloads completed in the last X minutes" visibility rule lives, so the tab bar and the … |
| `WorkloadWindow.ALL` | val | `WorkloadWindow.kt:14` | The "All" sentinel for [WINDOW_MINUTES]: no age ever hides a workload. |
| `WorkloadWindow.WINDOW_MINUTES` | val | `WorkloadWindow.kt:22` |  |
| `WorkloadWindow.DEFAULT_MINUTES` | val | `WorkloadWindow.kt:25` | The window the settings layer starts a user on, kept here so it is stated once and referenced elsewhere. |
| `WorkloadWindow.label` | fun | `WorkloadWindow.kt:36` | How a window reads in a menu: `15 Minutes`, `2 Hours`, `All`. |
| `WorkloadWindow.RUN_STARTED_AT` | val | `WorkloadWindow.kt:54` | When this run of the plugin began watching workloads — one instant, captured once, shared by everything. |
| `WorkloadWindow.isVisible` | fun | `WorkloadWindow.kt:69` | Whether a workload belongs in the view under the given window. |
| `WorkloadWindow.visible` | fun | `WorkloadWindow.kt:116` | Which workloads belong in the view, judged BOTTOM-UP so that what is emitted is a tree that holds together. |
| `WorkspaceDiffReview` | object | `WorkspaceDiffReview.kt:20` | Turns the binary's `get_workspace_diff` reply into the two SIDES a native diff needs. |
| `WorkspaceDiffReview.sides` | fun | `WorkspaceDiffReview.kt:53` | Rebuilds every file's two sides. |
| `WorkspaceDiffReview.baseOf` | fun | `WorkspaceDiffReview.kt:78` | The file as it was, from the file as it is plus the hunks that changed it. |
| `WorkspaceDiffReview.baseLabel` | fun | `WorkspaceDiffReview.kt:94` | What the left-hand pane is called, so a missing base says WHY instead of looking empty. |

<!-- MAP:GENERATED END -->

## Conventions here

- **Never re-grow `ClaudeSession`.** New behaviour goes to the collaborator that owns the subject, or to a new
  collaborator. The decomposition exists so that two people can work on two features without editing one file.
- **A new question for the binary is one `Ask` in `ControlAsks.kt`** — and a caller, in the same change. The
  declared form exists because the plumbing (the not-running answer, the EDT hop, the correlation id, the
  watchdog) is `SessionQueries.ask`'s business, and hand-rolled requests are where those go missing.
- Transcript mutation assumes the EDT. Go through the session's `edt {}` dispatcher.
- `SessionStore` is **read-only**. The plugin does not delete a session, and a contract test enforces it.
- The binary's level signals (`background_tasks_changed`) mark liveness. They never create an entry, because a
  finished task stops being listed and its row must not vanish with it.

## Minefields here

- **Give `ClaudeSession` a class-body `init` block and every property declared below it is null while it
  runs.** Kotlin runs initializers and `init` blocks in declaration order, and the compiler only reports the
  *direct* reference — a read that happens inside a function the `init` calls is invisible to it. That is why
  `InitOrderContractTest` is a **source scan over all of `src/main/kotlin`**, not a runtime test, and why its
  verdict is coarse: it flags any class-body property declared after any class-body `init`, whether or not the
  init can actually reach it, and it keys on a four-space indent, so the same defect one level in (a nested
  class's own `init`) is not pinned at all. The incident it was written for is in `ui/JcefChatPanel.kt`, not
  here — look there for what it costs, and note that the loud version (an NPE that took the whole tab) is the
  lucky one: a nullable or a primitive reads as null or zero and stays silent.
- **`AgentRegistry.observeSettled` is terminal, and an ending must also FORGET the growth baseline.** Only an
  ending settles: a live `task_notification` (`started`/`running`/`in_progress` all arrive as RUNNING) drops
  the stop instant instead of writing one, and an ending's instant is written **once**, so a repeated
  notification cannot rejuvenate an agent that stopped minutes ago. The part that is not obvious: the records
  the agent wrote on its way to that ending land in `agent-<id>.jsonl` *after* the scan that last counted
  them, so unless the ending deletes the baseline, the very next scan reads them as growth and `reopenIfGrown`
  deletes the settle written a moment earlier. The agent then reads RUNNING with no instant **forever** —
  later passes see no further growth, so nothing settles it again, and the revival poll only runs while
  something is settled. An absent baseline reopens nobody; that is the whole fix, and the two functions only
  make sense read together.
- **The bare agent id is the identity.** The file is `agent-<id>.jsonl` but the sidecar says
  `parentAgentId: "<id>"` unprefixed. Taking the filename as the identity collapses the whole tree into one
  level, and it looks like a rendering bug rather than a parsing one.
- **A nested subagent has no `toolUseId`**, so nothing can settle it on its own; it inherits its parent's
  ending because it cannot outlive the turn that spawned it. Anything that "fixes" a stuck RUNNING state by
  inventing an id will resurrect this.
- **`SessionTranscriptReader.parseEntries` is the only JSONL parser and must stay so.** `AgentRegistry` reuses
  it deliberately: a second parser is exactly how the duplicated-thinking defect of 4.0.4 happened.
- **An agent is not a background task.** `task_notification` fires for both; only a `tool_result` carrying a
  `backgroundTaskId` makes a task ours. Creating an entry from the notification produced a second,
  description-less row per agent whose "output" was pages of raw JSONL.
- **`WorkspaceDiffReview` refuses rather than guesses.** A review pane whose base side was fabricated is worse
  than no review, because nothing on screen tells them apart. The refusals are tested; do not soften them into
  best-effort reconstruction.
- **`LoginCoordinator` and the credential vault write the same file.** Harvesting the credential while a login
  is in flight deletes it under the binary and breaks the browser leg of the flow.
- The transcript cap and the tool-use index have to stay consistent: trimming a row for a re-emitted
  `tool_use_id` must not release an index entry that now points at a live row.

## Neighbours

- The events this package consumes → [`../protocol/`](../protocol/PROJECTMAP.md)
- The process it drives → [`../process/`](../process/PROJECTMAP.md)
- Who decides a tool may run → [`../permission/`](../permission/PROJECTMAP.md)
- Where the transcript is drawn → [`../ui/jcef/`](../ui/jcef/PROJECTMAP.md) and
  [`../../../../../resources/jcef/`](../../../../../resources/jcef/PROJECTMAP.md)
- Launch options and the env → [`../settings/`](../settings/PROJECTMAP.md)
