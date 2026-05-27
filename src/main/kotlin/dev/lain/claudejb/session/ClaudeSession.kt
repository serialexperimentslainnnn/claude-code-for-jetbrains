package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.diff.EditSnapshotStore
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable
import dev.lain.claudejb.process.ClaudeProcess
import dev.lain.claudejb.protocol.AccountInfo
import dev.lain.claudejb.protocol.AgentInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.ClaudeJson
import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.InitializeResponse
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.SlashCommand
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.settings.ClaudeSettings
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the long-lived `claude` process for a project and is the single entry point the GUI talks to.
 *
 * Responsibilities:
 *  - launch/stop the binary in stream-json mode and resume by session id;
 *  - host-managed **multiprompt** queue: prompts typed while a turn is running are queued and flushed
 *    one per turn (on `result`), exactly like the CLI's type-ahead;
 *  - accept every slash command (sent verbatim as user content);
 *  - expose and drive every runtime option (model, permission mode, thinking) and metadata
 *    (commands, models, agents, output styles, account) for the GUI menus;
 *  - reconcile streaming deltas into the [TranscriptModel];
 *  - answer `can_use_tool` via the [PermissionBroker] (native diff review).
 *
 * One instance == one chat tab. The project-level [ChatSessionManager] owns the set of live sessions;
 * this class is a plain object (not a service) so several can coexist, mirroring the web UI's tabs.
 */
class ClaudeSession(private val project: Project, @Volatile var title: String) : Disposable {

    private val log = thisLogger()

    val transcript = TranscriptModel()

    /** Pre-write file snapshots keyed by tool_use id, so any Edit/Write/MultiEdit can be re-diffed from the transcript. */
    private val editSnapshots = EditSnapshotStore()

    // --- session/runtime state (read by the GUI) ---
    @Volatile var sessionId: String? = null; internal set
    @Volatile var model: String? = null; private set
    @Volatile var effort: String? = null; private set
    @Volatile var permissionMode: String = "default"; private set
    @Volatile var thinkingTokens: Int? = null; private set
    @Volatile var allowedTools: String = ""; private set
    @Volatile var disallowedTools: String = ""; private set
    @Volatile var settingSources: String = "user,project,local"; private set
    /** Whether to wire JetBrains' own MCP server. Independent of [customMcpServers]. */
    @Volatile var ideMcpEnabled: Boolean = false; private set
    /** JetBrains transport: "sse" / "streamable-http" (localhost at [ideMcpPort]) or "stdio" (synthesized from IDE paths). */
    @Volatile var ideMcpTransport: String = "sse"; private set
    @Volatile var ideMcpPort: Int = DEFAULT_IDE_MCP_PORT; private set
    /** User-defined extra MCP servers, as a JSON object with the same shape as `mcpServers` (name → server). */
    @Volatile var customMcpServers: String = ""; private set
    @Volatile var includePartialMessages: Boolean = true; private set
    @Volatile var outputStyle: String = "default"; private set
    @Volatile var turnActive: Boolean = false; private set
    @Volatile var rateLimit: RateLimitInfo? = null; private set
    @Volatile var liveOutputTokens: Int = 0; private set
    @Volatile var sessionTokens: Int = 0; private set
    @Volatile private var ready = false

    /** Env resolved by sourcing the user's shell script (blocking, ~seconds). Cached so a restart doesn't re-source it;
     *  invalidated in [stop] so a settings change to the source script is picked up on the next start. */
    @Volatile private var cachedEnv: Map<String, String>? = null

    // --- metadata from the initialize handshake (powers the GUI menus) ---
    var commands: List<SlashCommand> = emptyList(); private set
    var models: List<ModelInfo> = emptyList(); private set
    var agents: List<AgentInfo> = emptyList(); private set
    var availableOutputStyles: List<String> = emptyList(); private set
    var account: AccountInfo = AccountInfo(); private set

    @Volatile private var process: ClaudeProcess? = null
    /**
     * Pending-prompt buffer. [ArrayDeque] is NOT thread-safe; **the queue is only ever touched on the EDT**
     * (send / sendSideQuestion / removeQueued / pump wrap their queue access in [edt]). Do not access it from a
     * background thread — confinement, not a concurrent structure, is the invariant here.
     */
    private val queue = ArrayDeque<String>()
    private val pendingControl = ConcurrentHashMap<String, (ClaudeEvent.ControlResult) -> Unit>()
    private val pendingRefresh = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Permission requests awaiting the user's Accept/Reject; EDT-confined, rendered as chat cards. */
    private val pending = LinkedHashMap<String, PendingPermission>()
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    // streaming reconciliation: the assistant text/thinking entry currently being grown by deltas
    private var liveAssistant: TranscriptEntry? = null
    private var liveThinking: TranscriptEntry? = null

    private val broker by lazy {
        PermissionBroker(
            permissionMode = { permissionMode },
            respond = ::write,
            onApprovedWrite = { pendingRefresh.add(it) },
            present = ::presentPermission,
            onAutoReviewed = ::autoOpenDiff,
            projectRoot = project.basePath,
            isRemembered = { toolName, input -> ClaudeSettings.getInstance(project).isToolAlwaysAllowed(toolName, input) },
        )
    }

    fun addListener(listener: SessionListener) = listeners.add(listener)
    fun removeListener(listener: SessionListener) = listeners.remove(listener)

    fun isRunning(): Boolean = process?.isRunning() == true
    fun queuedPrompts(): List<String> = queue.toList()
    fun pendingPermissions(): List<PendingPermission> = pending.values.toList()

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts the binary if it is not already running. Returns false (and notifies) only on a *synchronously*
     * detectable failure (`claude` missing). The actual launch is **asynchronous**: resolving the process env
     * (which sources a login shell with a multi-second timeout) and spawning the process are blocking, so they run
     * on a pooled thread to keep the EDT responsive — `start()` is called on the EDT from both `send()` and the tool
     * window, and doing this work inline froze the IDE for up to the shell timeout.
     *
     * The contract is intentionally "start returns before the process is ready": `pump()` is gated on `ready`/
     * `isRunning()`, so any prompt queued by `send()` before the async launch completes is flushed once `ready`
     * flips true at the end of the pooled-thread → EDT hand-back. A `true` return means "launch dispatched", not
     * "process up".
     */
    fun start(resume: Boolean = sessionId != null): Boolean {
        if (isRunning()) return true
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            notifyMissingBinary()
            return false
        }
        // Persist the auto-detected path so later launches are stable and the user can see/edit it
        // (also refreshes a stale saved path that fell back to auto-detection).
        if (settings.claudePath != binary.absolutePath) settings.state.claudePath = binary.absolutePath

        // Trust-on-open gate: a project-level claude-code.xml can ship a sourceScript or a stdio MCP server
        // (arbitrary command), both of which we'd execute at launch. If that config is present and the project
        // hasn't been trusted for it, ask once before running anything. Declining aborts the launch rather than
        // silently executing code that arrived with an untrusted repo. Runs on the EDT (start()'s contract).
        if (!ensureExecTrust(settings)) return false
        val workDir = project.basePath?.let(::File) ?: File(System.getProperty("user.home"))

        ready = false
        liveAssistant = null
        liveThinking = null

        // Off the EDT: env resolution sources a shell (seconds) and process spawn can block. Hand back to the EDT
        // for the state mutations the GUI observes (ready/fireState/pump) and the queue invariant.
        ApplicationManager.getApplication().executeOnPooledThread {
            val env = cachedEnv ?: settings.resolveEnv().also { cachedEnv = it }
            val proc = ClaudeProcess(
                binary = binary,
                workDir = workDir,
                args = buildArgs(resume),
                nodeOverride = settings.nodePath,
                extraEnv = env,
                onEvent = ::onEvent,
                onTerminated = ::onTerminated,
            )
            process = proc
            // ClaudeProcess.start() may throw if the process fails to spawn — surface it instead of leaving a
            // half-initialized session that never becomes ready.
            val started = runCatching { proc.start() }
            if (started.isFailure) {
                process = null
                log.warn("Failed to start the claude process", started.exceptionOrNull())
                notifyError("Failed to start Claude Code: ${started.exceptionOrNull()?.message ?: "unknown error"}")
                return@executeOnPooledThread
            }

            // Optional handshake → rich command/model/agent metadata for the GUI menus.
            val initId = ControlProtocol.newRequestId()
            val initWatchdog = scheduleControlTimeout(initId)
            pendingControl[initId] = handler@{ res ->
                initWatchdog.cancel(false)
                val payload = res.payload ?: return@handler
                val info = runCatching { ClaudeJson.decodeFromJsonElement(InitializeResponse.serializer(), payload) }
                    .onFailure { log.debug("Failed to decode initialize response", it) }
                    .getOrNull()
                    ?: return@handler
                commands = info.commands
                models = info.models
                agents = info.agents
                availableOutputStyles = info.availableOutputStyles
                account = info.account
                if (info.outputStyle.isNotBlank()) outputStyle = info.outputStyle
                edt { fireMetadata() }
            }
            write(ControlProtocol.initializeRequest(initId))

            // NOTE (claude 2.1.150): the binary accepts prompts on stdin from the start and only emits the
            // `system/init` line *after* the first user turn — not on launch. So we must NOT gate readiness on
            // the Init event (that would deadlock: pump() waits for ready, ready waits for a prompt). We're
            // ready as soon as the process is up; Init, when it later arrives, just back-fills sessionId/model.
            edt {
                ready = true
                transcript.add(Speaker.SYSTEM, "Claude Code ready.")
                fireState()
                pump()
            }
        }
        return true
    }

    /**
     * Per-request watchdog: if the binary leaves a control request hanging (semi-stuck), fail it after a timeout so
     * "Loading…" dialogs don't wait forever. Cancelled by the response handler when the reply arrives in time.
     */
    private fun scheduleControlTimeout(id: String): ScheduledFuture<*> =
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            pendingControl.remove(id)?.invoke(
                ClaudeEvent.ControlResult(requestId = id, success = false, payload = null, error = "control request timed out"),
            )
        }, CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    /** Stops the current process and starts a fresh one, resuming the same session if possible. */
    fun restart(resume: Boolean = true) {
        stop()
        start(resume)
    }

    fun stop() {
        process?.closeStdin()
        process?.destroy()
        process = null
        turnActive = false
        ready = false
        // Drop the cached env so a settings change to the source script is re-sourced on the next start.
        cachedEnv = null
        failPendingControl()
        edt { clearPending(); fireState() }
    }

    /**
     * Resolves every in-flight control request with a failure so their callbacks (get_context_usage / cost /
     * mcp_status / initialize) don't hang forever once the process is gone — the callbacks map a null payload to
     * `onResult(null)` on the EDT, which closes the "Loading…" dialogs. Called on stop / termination / dispose.
     */
    private fun failPendingControl() {
        pendingControl.values.toList().also { pendingControl.clear() }.forEach {
            it(ClaudeEvent.ControlResult(requestId = "", success = false, payload = null, error = "process gone"))
        }
    }

    /**
     * The mode the binary actually runs in. `acceptEdits`/`bypassPermissions` are enforced host-side by the
     * [PermissionBroker]: we keep the binary in `default` so it still routes every edit through
     * `--permission-prompt-tool stdio`. That round-trip is what lets us open the native diff in the IDE before
     * the binary writes — newer binaries auto-approve edits internally in acceptEdits and never prompt, so the
     * diff would otherwise never appear. `default`/`plan` pass through unchanged.
     */
    private fun binaryPermissionMode(): String =
        if (permissionMode == "acceptEdits" || permissionMode == "bypassPermissions") "default" else permissionMode

    private fun buildArgs(resume: Boolean): List<String> {
        val args = mutableListOf(
            "--print",
            "--output-format", "stream-json",
            "--input-format", "stream-json",
            "--verbose",
            "--permission-prompt-tool", "stdio",
            "--permission-mode", binaryPermissionMode(),
        )
        if (includePartialMessages) args += "--include-partial-messages"
        if (settingSources.isNotBlank()) args += listOf("--setting-sources", settingSources)
        model?.let { args += listOf("--model", it) }
        effort?.let { args += listOf("--effort", it) }
        // Extended thinking (adaptive): a launch flag on current models. `--thinking-display summarized` is what
        // actually streams the reasoning blocks; without it no "Thought process" appears. Any non-null budget = on.
        if (thinkingTokens != null) args += listOf("--thinking", "adaptive", "--thinking-display", "summarized")
        allowedTools.trim().ifBlank { null }?.let { args += listOf("--allowedTools", it) }
        disallowedTools.trim().ifBlank { null }?.let { args += listOf("--disallowedTools", it) }
        mcpConfigJson()?.let { args += listOf("--mcp-config", it) }
        if (resume) sessionId?.let { args += listOf("--resume", it) }
        return args
    }

    /**
     * Builds `{"mcpServers": …}` for `--mcp-config` by delegating to the pure [McpConfigBuilder] (testable without
     * the IDE). The only IDE-coupled bit — resolving the stdio command from the running IDE's paths — is computed
     * here as [resolveStdioParams] and handed in; everything else is plain data. The wire format is unchanged.
     */
    private fun mcpConfigJson(): String? =
        McpConfigBuilder.mcpConfigJson(
            ideMcpEnabled = ideMcpEnabled,
            transport = ideMcpTransport,
            port = ideMcpPort,
            customMcpServers = customMcpServers,
            stdioParams = if (ideMcpEnabled && ideMcpTransport == "stdio") resolveStdioParams() else null,
            onCustomParseError = { log.debug("Failed to parse custom MCP servers JSON", it) },
        )

    /**
     * Resolves the IDE-dependent inputs for the stdio transport: the JBR java, the bundled MCP Server plugin's lib
     * dir, the platform lib dir and the port. Returns null if the plugin can't be located (→ stdio isn't registered).
     * Looked up by its stable plugin id via the public [PluginManager] API (not the internal PluginManagerCore).
     */
    private fun resolveStdioParams(): McpConfigBuilder.StdioParams? {
        val javaBin = File(File(System.getProperty("java.home"), "bin"), if (SystemInfo.isWindows) "java.exe" else "java")
        val pluginLib = PluginManager.getInstance()
            .findEnabledPlugin(PluginId.getId("com.intellij.mcpServer"))
            ?.pluginPath?.resolve("lib")?.toFile()
            ?: return null
        return McpConfigBuilder.StdioParams(javaBin, pluginLib, PathManager.getLibPath(), ideMcpPort)
    }


    // -----------------------------------------------------------------------
    // Sending prompts / commands (multiprompt)
    // -----------------------------------------------------------------------

    /**
     * Queues [text] for sending. If idle it is dispatched immediately; if a turn is in progress it waits
     * in the queue and is flushed when the current turn finishes (one prompt per turn).
     * Slash commands are just user content beginning with '/'.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!isRunning()) {
            if (!start()) return
        }
        // Queue access is EDT-confined (the deque isn't thread-safe).
        edt {
            queue.addLast(trimmed)
            fireState()
            pump()
        }
    }

    /**
     * `/btw` — sends a quick side question *immediately*, even mid-turn, without interrupting the active turn.
     * The binary accepts the message in streaming-input and answers it after the current turn finishes
     * (verified empirically against claude 2.1.150). When idle it behaves like a normal send.
     */
    fun sendSideQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!isRunning()) {
            // Cold start: the launch is async (process not up yet), so a direct write would be dropped. Fall back to
            // the queue, which pump() flushes once the process is ready — behaving like a normal send when idle.
            if (!start()) return
            edt {
                queue.addLast(trimmed)
                fireState()
                pump()
            }
            return
        }
        // pump() touches the (EDT-confined) queue, so run the whole body on the EDT.
        edt {
            transcript.add(Speaker.USER, "↪ $trimmed")
            write(ControlProtocol.userMessage(trimmed))
            if (!turnActive) {
                turnActive = true
                fireState()
            }
            // Flush anything still queued from startup; the binary accumulates messages mid-turn.
            pump()
        }
    }

    fun removeQueued(index: Int) = edt {
        // Queue access is EDT-confined (the deque isn't thread-safe).
        if (index in queue.indices) {
            val copy = queue.toMutableList()
            copy.removeAt(index)
            queue.clear()
            queue.addAll(copy)
            fireState()
        }
    }

    /**
     * Flushes the whole queue at once. The binary accepts user messages mid-turn and **accumulates** them into
     * its context, processing them together (verified: 3 messages sent back-to-back are grouped, sharing
     * context). So we send every queued prompt immediately — even while a turn is active — instead of releasing
     * one per `result`. The queue only buffers prompts typed before the process is `ready` (during startup).
     */
    private fun pump() {
        if (!ready || queue.isEmpty() || !isRunning()) return
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            transcript.add(Speaker.USER, next)
            write(ControlProtocol.userMessage(next))
            turnActive = true
        }
        fireState()
    }

    fun interrupt() {
        if (!isRunning()) return
        write(ControlProtocol.interruptRequest(ControlProtocol.newRequestId()))
        systemNotice("Interrupting…")
    }

    // -----------------------------------------------------------------------
    // Permissions — non-modal: requests surface as cards in the chat and the user
    // resolves them with Accept/Reject (no blocking dialogs).
    // -----------------------------------------------------------------------

    /** Broker callback (off-EDT): a tool needs the user's decision. Queue it for the UI to render. */
    private fun presentPermission(request: PendingPermission) = edt {
        pending[request.requestId] = request
        firePermissions()
        fireAttention(AttentionReason.PERMISSION)
    }

    /**
     * Broker callback (off-EDT) for auto-approved edits in acceptEdits/bypassPermissions: there is no card,
     * but the user still wants to see the change. Capture the file's current contents *now* — before the binary
     * writes (which happens right after we answer allow) — and open the diff tab on the EDT with that snapshot.
     */
    private fun autoOpenDiff(toolName: String, input: JsonObject, toolUseId: String) {
        // Capture the pre-write contents now (the binary writes right after we answer allow) and persist them
        // keyed by tool_use id, so the change is re-diffable from the transcript long after this tab closes.
        val snapshot = editSnapshots.capture(toolName, input, toolUseId) ?: return
        edt { DiffPresenter.openDiff(project, toolName, input, snapshot.beforeText) }
    }

    /** The persisted pre-write snapshot for a reviewable tool call, or null if none was captured (e.g. rejected). */
    fun editSnapshot(toolUseId: String): EditSnapshot? = editSnapshots.get(toolUseId)

    // -----------------------------------------------------------------------
    // Persistence — restore + open-tab tracking (SessionHistory)
    // The binary's session file is the source of truth for transcripts; we never persist our own.
    // -----------------------------------------------------------------------

    /**
     * Re-attaches this (not-yet-started) session to a saved one: adopts [savedSessionId] so the next [start]
     * resumes via `--resume`, and rebuilds the transcript from [dtos]. Rows with an unknown speaker are skipped
     * rather than failing the whole restore. Must run before start(); transcript mutation happens on the EDT.
     */
    fun restore(savedSessionId: String, dtos: List<EntryDTO>) {
        sessionId = savedSessionId
        edt {
            transcript.clear()
            for (dto in dtos) {
                val speaker = runCatching { Speaker.valueOf(dto.speaker) }.getOrNull() ?: continue
                transcript.add(
                    speaker,
                    dto.text,
                    meta = dto.meta,
                    toolUseId = dto.toolUseId,
                    parentToolUseId = dto.parentToolUseId,
                )
            }
        }
    }

    /**
     * Off-EDT: resolves the binary's real session title (the one `--resume` shows), relabels the tab if it
     * changed, and records the currently-open tab set so it can be restored on the next startup. No transcript
     * is persisted — the binary's session file is the source of truth and is re-read on restore.
     */
    private fun recordOpenAndTitle(id: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            val resolved = SessionTitleReader.readTitle(id) ?: title
            if (resolved != title) {
                title = resolved
                edt { fireTitleChanged() }
            }
            SessionHistory.getInstance(project)
                .setOpenSessions(ChatSessionManager.getInstance(project).all().mapNotNull { it.sessionId })
        }
    }

    /** Invoked by the chat UI when the user clicks Accept/Reject on a permission card. */
    fun resolvePermission(requestId: String, allow: Boolean, denyMessage: String? = null, overrideInput: JsonObject? = null) {
        val request = pending.remove(requestId) ?: return
        if (allow) {
            if (request.reviewable) {
                // Snapshot/refresh stay on the ORIGINAL input: they describe the real file (before-text + path),
                // independent of any narrowed payload (e.g. hunk-by-hunk partial acceptance) we actually send.
                DiffPresenter.filePathOf(request.input)?.let { pendingRefresh.add(it) }
                // Snapshot before answering allow (the binary writes right after), so "View diff" works from the
                // transcript once the transient approval diff has closed. Synchronous read — small project files.
                request.toolUseId?.let { editSnapshots.capture(request.toolName, request.input, it) }
            }
            // The UI may override the tool input to narrow what the binary writes (partial acceptance).
            val effectiveInput = overrideInput ?: request.input
            write(ControlProtocol.permissionAllow(requestId, effectiveInput))
            systemNotice("Approved ${request.headline}")
        } else {
            write(ControlProtocol.permissionDeny(requestId, denyMessage ?: "User rejected the ${request.toolName} request."))
            systemNotice("Rejected ${request.headline}")
        }
        firePermissions()
    }

    /**
     * Invoked by the chat UI when the user submits answers to an AskUserQuestion card. Replies allow with
     * updatedInput = original input + {"answers": {questionText: chosenLabel}}; the binary echoes the choice
     * back as the tool result (verified against claude 2.1.150).
     */
    fun resolveQuestion(requestId: String, answers: Map<String, String>) {
        val request = pending.remove(requestId) ?: return
        val updated = buildJsonObject {
            request.input.forEach { (k, v) -> put(k, v) }
            put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
        }
        write(ControlProtocol.permissionAllow(requestId, updated))
        systemNotice("Answered Claude's question")
        firePermissions()
    }

    private fun clearPending() {
        if (pending.isEmpty()) return
        pending.clear()
        firePermissions()
    }

    // -----------------------------------------------------------------------
    // Runtime option controls (drive the GUI menus)
    // -----------------------------------------------------------------------

    fun changeModel(value: String?) {
        model = value
        if (isRunning()) write(ControlProtocol.setModelRequest(ControlProtocol.newRequestId(), value))
        fireState()
    }

    fun changePermissionMode(mode: String) {
        permissionMode = mode
        // Persist so new tabs / restarts launch in this mode instead of falling back to "default".
        ClaudeSettings.getInstance(project).getState().permissionMode = mode
        if (isRunning()) write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), binaryPermissionMode()))
        fireState()
    }

    /** Effort is a launch flag; it takes effect on the next (re)start. */
    fun changeEffort(value: String?) {
        effort = value
        fireState()
    }

    /**
     * Extended thinking is a launch flag now (`--thinking`), not a runtime control — the deprecated
     * `set_max_thinking_tokens` no longer surfaces reasoning on current models. So toggling it restarts the
     * session (resuming the same conversation via `--resume`) to re-launch with the new flag. Any non-null
     * value means "on" (adaptive); the exact token count is no longer sent (adaptive lets the model decide).
     */
    fun changeThinkingTokens(tokens: Int?) {
        if (tokens == thinkingTokens) return
        val wasRunning = isRunning()
        thinkingTokens = tokens
        fireState()
        if (wasRunning) {
            systemNotice(if (tokens != null) "Extended thinking on — restarting session." else "Extended thinking off — restarting session.")
            restart(resume = true)
        }
    }

    /** Launch-time options (tool allow/deny lists, setting sources, partial streaming). Take effect on (re)start. */
    fun configureLaunchOptions(
        allowedTools: String,
        disallowedTools: String,
        settingSources: String,
        includePartialMessages: Boolean,
        ideMcpEnabled: Boolean = false,
        ideMcpTransport: String = "sse",
        ideMcpPort: Int = DEFAULT_IDE_MCP_PORT,
        customMcpServers: String = "",
    ) {
        this.allowedTools = allowedTools
        this.disallowedTools = disallowedTools
        this.settingSources = settingSources
        this.includePartialMessages = includePartialMessages
        this.ideMcpEnabled = ideMcpEnabled
        this.ideMcpTransport = ideMcpTransport.ifBlank { "sse" }
        this.ideMcpPort = ideMcpPort.takeIf { it in 1..65535 } ?: DEFAULT_IDE_MCP_PORT
        this.customMcpServers = customMcpServers
        fireState()
    }

    fun cyclePermissionMode() {
        val order = PERMISSION_MODES_CYCLE
        val idx = order.indexOf(permissionMode).let { if (it < 0) 0 else it }
        changePermissionMode(order[(idx + 1) % order.size])
    }

    // -----------------------------------------------------------------------
    // Async queries (results delivered to [onResult] on the EDT)
    // -----------------------------------------------------------------------

    fun requestContextUsage(onResult: (ContextUsage?) -> Unit) =
        query(ControlProtocol::getContextUsageRequest, onResult) { payload ->
            payload?.let { runCatching { ClaudeJson.decodeFromJsonElement(ContextUsage.serializer(), it) }.getOrNull() }
        }

    fun requestSessionCost(onResult: (JsonObject?) -> Unit) =
        query(ControlProtocol::getSessionCostRequest, onResult) { it }

    fun requestMcpStatus(onResult: (JsonObject?) -> Unit) =
        query(ControlProtocol::mcpStatusRequest, onResult) { it }

    /** Shared plumbing: register a pending handler keyed by a fresh id, send the request, map the payload, deliver on EDT. */
    private fun <T> query(buildLine: (requestId: String) -> String, onResult: (T?) -> Unit, map: (JsonObject?) -> T?) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        val id = ControlProtocol.newRequestId()
        // Watchdog: a semi-stuck binary could otherwise leave this callback pending forever (eternal "Loading…").
        val watchdog = scheduleControlTimeout(id)
        pendingControl[id] = { res -> watchdog.cancel(false); val mapped = map(res.payload); edt { onResult(mapped) } }
        write(buildLine(id))
    }

    // -----------------------------------------------------------------------
    // Event handling (called on the process reader thread)
    // -----------------------------------------------------------------------

    private fun onEvent(event: ClaudeEvent) {
        when (event) {
            is ClaudeEvent.Init -> {
                sessionId = event.info.sessionId
                if (model == null && event.info.model.isNotBlank()) model = event.info.model
                if (event.info.outputStyle.isNotBlank()) outputStyle = event.info.outputStyle
                // The plugin is the source of truth for permissionMode. system/init re-arrives every turn and
                // reports the *launch-time* mode ("default"), which used to clobber a user choice (the
                // recurring "reset to default" bug). Never adopt it; if the binary has drifted from our mode,
                // push ours back so it converges instead.
                if (event.info.permissionMode.isNotBlank() && event.info.permissionMode != binaryPermissionMode()) {
                    write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), binaryPermissionMode()))
                }
                ready = true
                edt {
                    systemNotice("Connected · ${event.info.model.ifBlank { "claude" }} · ${event.info.cwd}")
                    fireState()
                    pump()
                }
            }

            is ClaudeEvent.TextDelta -> edt { appendAssistant(event.text) }
            is ClaudeEvent.ThinkingDelta -> edt { appendThinking(event.text) }
            is ClaudeEvent.AssistantText -> edt {
                // Subagent text arrives finalized with a parent id: anchor it under its Agent without touching
                // the top-level live stream. Top-level text keeps the existing streaming reconciliation.
                if (event.parentToolUseId != null) {
                    transcript.add(Speaker.ASSISTANT, event.text, parentToolUseId = event.parentToolUseId)
                } else {
                    finalizeAssistant(event.text)
                }
            }
            is ClaudeEvent.AssistantThinking -> edt { finalizeThinking(event.text) }
            is ClaudeEvent.MessageStart -> edt {
                // A turn can emit several assistant messages (e.g. around tool calls). message_delta usage
                // restarts near 0 per message, so fold the finished message's tokens into the session total
                // before the next one overwrites the live counter — otherwise only the last message counts.
                sessionTokens += liveOutputTokens
                liveOutputTokens = 0
                liveAssistant = null
                liveThinking = null
            }

            is ClaudeEvent.ToolUse -> edt {
                // Only break the top-level live stream for top-level tool calls; a subagent's tool call must not
                // cut a top-level paragraph that may continue after the Agent finishes.
                if (event.parentToolUseId == null) {
                    liveAssistant = null
                    liveThinking = null
                }
                transcript.add(
                    Speaker.TOOL,
                    formatToolUse(event.name, event.input),
                    meta = event.name,
                    toolUseId = event.id,
                    parentToolUseId = event.parentToolUseId,
                )
            }

            is ClaudeEvent.ToolResult -> edt {
                // For a reviewable write we captured the pre-write contents at approval time: render the actual
                // change as an inline unified diff (meta="diff") instead of the binary's "Edited file" blurb, so
                // the output box shows what changed. The diff text is self-contained, so it also survives a
                // session restore (no snapshot needed at render time). Falls back to the binary text otherwise.
                val snap = editSnapshots.get(event.toolUseId)
                val diff = if (snap != null && snap.toolName in DiffPresenter.REVIEWABLE_TOOLS) {
                    DiffPresenter.proposedContent(snap.toolName, snap.input, snap.beforeText)
                        ?.let { DiffPresenter.unifiedDiff(snap.beforeText, it) }
                        ?.takeIf { it.isNotBlank() }
                } else null
                if (diff != null) {
                    transcript.addToolOutput(event.toolUseId, diff, parentToolUseId = event.parentToolUseId, meta = "diff")
                } else {
                    val text = event.content.trim()
                    if (text.isNotBlank()) {
                        transcript.addToolOutput(event.toolUseId, text, parentToolUseId = event.parentToolUseId)
                    }
                }
            }

            is ClaudeEvent.Result -> edt {
                sessionTokens += liveOutputTokens
                liveOutputTokens = 0
                turnActive = false
                liveAssistant = null
                liveThinking = null
                if (event.result.isError) {
                    // error_* results carry no `result` text — the message is in `errors` (sdk.d.ts SDKResultError).
                    // Always surface something so a failed turn never ends silently.
                    val message = event.result.result.ifBlank {
                        event.result.errors.joinToString("\n").ifBlank { "Turn ended with error: ${event.result.subtype}" }
                    }
                    transcript.add(Speaker.ERROR, message)
                }
                refreshTouchedFiles()
                fireState()
                pump()
                // The binary's session file is the source of truth for the transcript; we don't persist our own.
                // Once per turn we just record the open-tab set (for restore on startup) and refresh the tab title
                // from the binary's resolved title. Off-EDT: the sidecar JSONL read is blocking IO.
                sessionId?.let { id -> recordOpenAndTitle(id) }
                fireAttention(if (event.result.isError) AttentionReason.ERROR else AttentionReason.TURN_DONE)
            }

            is ClaudeEvent.LocalCommandOutput -> edt {
                if (event.content.isNotBlank()) transcript.add(Speaker.SYSTEM, event.content)
            }

            is ClaudeEvent.StatusNotice -> systemNotice(event.text)

            // On the EDT like every sibling case, so the token counters are single-threaded (no read-modify-write
            // race with the Result / MessageStart folds). invokeLater preserves arrival order, so the running total stays correct.
            is ClaudeEvent.LiveUsage -> edt { liveOutputTokens = event.outputTokens }
            is ClaudeEvent.RateLimit -> { rateLimit = event.info; edt { fireState() } }

            is ClaudeEvent.PermissionRequest -> broker.handle(event.requestId, event.request)
            is ClaudeEvent.UnsupportedControlRequest -> broker.rejectUnsupported(event.requestId, event.subtype)
            is ClaudeEvent.ControlResult -> pendingControl.remove(event.requestId)?.invoke(event)

            is ClaudeEvent.Other -> log.debug("Ignored ${event.type}/${event.subtype}")
        }
    }

    private fun onTerminated(exitCode: Int) {
        // The process is gone: release any in-flight control callbacks so their dialogs don't hang.
        failPendingControl()
        edt {
            turnActive = false
            ready = false
            clearPending()
            if (exitCode != 0) {
                transcript.add(Speaker.ERROR, "Claude Code exited (code $exitCode).")
                // The user may not have this tab focused; also raise a notification so the failure isn't missed.
                notifyError("Claude Code exited unexpectedly (code $exitCode).")
                fireAttention(AttentionReason.ERROR)
            } else {
                systemNotice("Session ended.")
            }
            fireState()
        }
    }

    // --- streaming reconciliation (EDT) ---

    private fun appendAssistant(delta: String) {
        liveThinking = null
        val entry = liveAssistant
        if (entry == null) liveAssistant = transcript.add(Speaker.ASSISTANT, delta)
        else transcript.append(entry, delta)
    }

    private fun finalizeAssistant(full: String) {
        val entry = liveAssistant
        if (entry != null) transcript.replaceText(entry, full) else transcript.add(Speaker.ASSISTANT, full)
        liveAssistant = null
    }

    private fun appendThinking(delta: String) {
        val entry = liveThinking
        if (entry == null) liveThinking = transcript.add(Speaker.THINKING, delta)
        else transcript.append(entry, delta)
    }

    private fun finalizeThinking(full: String) {
        val entry = liveThinking
        if (entry != null) transcript.replaceText(entry, full) else transcript.add(Speaker.THINKING, full)
        liveThinking = null
    }

    private fun refreshTouchedFiles() {
        val paths = synchronized(pendingRefresh) { pendingRefresh.toList().also { pendingRefresh.clear() } }
        if (paths.isEmpty()) return
        // Async refresh: a synchronous VFS refresh (refreshAndFindFileByPath) requires a write-safe context,
        // which the ModalityState.any() invokeLater we run under is not. refreshIoFiles handles
        // created/modified/deleted paths and, with async=true, is safe to call from here.
        LocalFileSystem.getInstance().refreshIoFiles(paths.map { File(it) }, true, false, null)
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun write(line: String) = process?.writeLine(line)

    private fun systemNotice(message: String) = edt { transcript.add(Speaker.SYSTEM, message) }

    private fun fireState() = listeners.forEach { it.onStateChanged() }
    private fun fireMetadata() = listeners.forEach { it.onMetadataChanged() }
    private fun firePermissions() = listeners.forEach { it.onPermissionsChanged() }
    private fun fireAttention(reason: AttentionReason) = listeners.forEach { it.onAttention(reason) }
    private fun fireTitleChanged() = listeners.forEach { it.onTitleChanged() }

    private fun edt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    private fun notifyError(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Claude Code", content, NotificationType.ERROR)
            .notify(project)
    }

    /**
     * EDT-only. Returns true if the session may launch: either there's no risky exec config (sourceScript / stdio
     * MCP server) or the user has trusted this project for it. Prompts once when trust is required; accepting
     * persists the trust, declining returns false so the caller aborts the launch.
     */
    private fun ensureExecTrust(settings: ClaudeSettings): Boolean {
        if (!settings.requiresTrustPrompt()) return true
        val choice = Messages.showYesNoDialog(
            project,
            "This project is configured to run an environment script and/or a custom MCP server when a Claude " +
                "Code session starts. These execute code on your machine. Only allow this if you trust this " +
                "project's settings (claude-code.xml). Run them?",
            "Trust Claude Code Execution Config?",
            "Trust and run",
            "Cancel",
            Messages.getWarningIcon(),
        )
        return if (choice == Messages.YES) {
            settings.setExecutionTrusted(true)
            true
        } else {
            notifyError("Launch cancelled. Review the source script / custom MCP servers in Settings, then try again.")
            false
        }
    }

    private fun notifyMissingBinary() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "The 'claude' binary was not found on PATH or in a typical location. " +
                    "Install Claude Code (https://claude.com/code), or set the executable path manually.",
                NotificationType.ERROR,
            )
            .addAction(NotificationAction.createSimple("Configure paths…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
            })
            .notify(project)
    }

    override fun dispose() {
        // EOF first (lets the binary exit cleanly) then destroy the tree — same order as stop().
        process?.closeStdin()
        process?.destroy()
        process = null
        // Release any in-flight control callbacks so nothing is left waiting after the tab is gone.
        failPendingControl()
    }

    /** Models for the GUI: those the binary reported (minus the generic "default") plus known aliases
     *  not yet present — so the list is never empty even before the initialize handshake lands. */
    fun modelOptions(): List<ModelInfo> {
        val result = models.filter { it.value != "default" }.toMutableList()
        FALLBACK_MODELS.forEach { fb -> if (result.none { it.value == fb.value }) result += fb }
        return result
    }

    companion object {
        const val NOTIFICATION_GROUP = "Claude Code"

        /** How long to wait for a reply to a host-initiated control request before failing it (watchdog). */
        const val CONTROL_TIMEOUT_SECONDS = 30L

        /** Default model on a fresh install: Opus 4.7. */
        const val DEFAULT_MODEL = "claude-opus-4-7"

        /** Sentinel "extended thinking on" value: adaptive thinking is on/off, so any positive budget means on. */
        const val THINKING_ON = 1

        /** Offered when the binary's initialize handshake hasn't (yet) returned them. */
        val FALLBACK_MODELS = listOf(
            ModelInfo("claude-opus-4-7", "Opus 4.7"),
            ModelInfo("claude-opus-4-5", "Opus 4.5"),
            ModelInfo("opusplan", "Opusplan (auto: Opus/Sonnet by task)"),
        )

        // Allowed values come from the typed enums in ClaudeEnums.kt (single source of truth); exposed as the
        // wire strings so the UI/persistence/protocol callers stay string-based and unchanged.
        /** Shift+Tab cycles through these, like the CLI. */
        val PERMISSION_MODES_CYCLE = PermissionMode.CYCLE.map { it.wire }

        /** Full set of modes for the GUI menu. */
        val PERMISSION_MODES = PermissionMode.entries.map { it.wire }

        val EFFORT_LEVELS = EffortLevel.entries.map { it.wire }

        /** Setting-source scopes (--setting-sources), for the Settings checkboxes. */
        val SETTING_SOURCES = listOf("user", "project", "local")

        /** Default port of JetBrains' MCP Server plugin (used to synthesize the sse/streamable-http endpoint). */
        const val DEFAULT_IDE_MCP_PORT = 64342

        /** Transports JetBrains' MCP server exposes; stdio is synthesized from the running IDE. */
        val IDE_MCP_TRANSPORTS = McpTransport.entries.map { it.wire }

        /** Example shown in the custom-servers text area (the `mcpServers` shape: name → server). */
        val CUSTOM_MCP_SERVERS_HINT = """
            {
              "my-http-server": { "type": "streamable-http", "url": "https://example.com/mcp", "headers": {} },
              "my-stdio-server": { "type": "stdio", "command": "/path/to/server", "args": [] }
            }
        """.trimIndent()

        /** True iff the text is a JSON object (or blank) — used by the settings UI to reject a bad custom paste. */
        fun isValidMcpConfig(text: String): Boolean =
            text.isBlank() || (runCatching { ClaudeJson.parseToJsonElement(text) }.getOrNull() is JsonObject)

        /** Standard built-in tools, for the allow/deny checkboxes in Settings. */
        val BUILTIN_TOOLS = listOf(
            "Bash", "Read", "Edit", "Write", "Glob", "Grep",
            "WebFetch", "WebSearch", "Task", "TodoWrite", "NotebookEdit",
        )

        /** Concise one-line representation of a tool call, mirroring the CLI's "Tool(arg)" bullets. */
        fun formatToolUse(name: String, input: JsonObject): String {
            val arg = when (name) {
                "Bash" -> input.str("command")
                "Read", "Edit", "Write", "MultiEdit", "NotebookEdit" -> input.str("file_path")?.substringAfterLast('/')
                "Glob", "Grep" -> input.str("pattern")
                "Task" -> input.str("description")
                "WebFetch" -> input.str("url")
                "WebSearch" -> input.str("query")
                else -> input.str("file_path")?.substringAfterLast('/') ?: input.str("path")
            }
            return if (!arg.isNullOrBlank()) "$name($arg)" else name
        }
    }
}
