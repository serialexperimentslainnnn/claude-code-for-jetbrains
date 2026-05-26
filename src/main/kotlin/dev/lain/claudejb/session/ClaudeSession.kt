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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.lain.claudejb.diff.DiffPresenter
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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

    // --- session/runtime state (read by the GUI) ---
    @Volatile var sessionId: String? = null; private set
    @Volatile var model: String? = null; private set
    @Volatile var effort: String? = null; private set
    @Volatile var permissionMode: String = "default"; private set
    @Volatile var thinkingTokens: Int? = null; private set
    @Volatile var allowedTools: String = ""; private set
    @Volatile var disallowedTools: String = ""; private set
    @Volatile var settingSources: String = "user,project,local"; private set
    @Volatile var includePartialMessages: Boolean = true; private set
    @Volatile var outputStyle: String = "default"; private set
    @Volatile var turnActive: Boolean = false; private set
    @Volatile var rateLimit: RateLimitInfo? = null; private set
    @Volatile var liveOutputTokens: Int = 0; private set
    @Volatile var sessionTokens: Int = 0; private set
    @Volatile private var ready = false

    // --- metadata from the initialize handshake (powers the GUI menus) ---
    var commands: List<SlashCommand> = emptyList(); private set
    var models: List<ModelInfo> = emptyList(); private set
    var agents: List<AgentInfo> = emptyList(); private set
    var availableOutputStyles: List<String> = emptyList(); private set
    var account: AccountInfo = AccountInfo(); private set

    @Volatile private var process: ClaudeProcess? = null
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

    /** Starts the binary if it is not already running. Returns false (and notifies) if `claude` is missing. */
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
        val workDir = project.basePath?.let(::File) ?: File(System.getProperty("user.home"))

        ready = false
        liveAssistant = null
        liveThinking = null

        val proc = ClaudeProcess(
            binary = binary,
            workDir = workDir,
            args = buildArgs(resume),
            nodeOverride = settings.nodePath,
            extraEnv = settings.resolveEnv(),
            onEvent = ::onEvent,
            onTerminated = ::onTerminated,
        )
        process = proc
        proc.start()

        // Optional handshake → rich command/model/agent metadata for the GUI menus.
        val initId = ControlProtocol.newRequestId()
        pendingControl[initId] = handler@{ res ->
            val payload = res.payload ?: return@handler
            val info = runCatching { ClaudeJson.decodeFromJsonElement(InitializeResponse.serializer(), payload) }.getOrNull()
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
        ready = true
        systemNotice("Claude Code ready.")
        edt { fireState(); pump() }
        return true
    }

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
        edt { clearPending(); fireState() }
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
        allowedTools.trim().ifBlank { null }?.let { args += listOf("--allowedTools", it) }
        disallowedTools.trim().ifBlank { null }?.let { args += listOf("--disallowedTools", it) }
        if (resume) sessionId?.let { args += listOf("--resume", it) }
        return args
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
        queue.addLast(trimmed)
        fireState()
        pump()
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
            if (!start()) return
        }
        transcript.add(Speaker.USER, "↪ $trimmed")
        write(ControlProtocol.userMessage(trimmed))
        if (!turnActive) {
            turnActive = true
            fireState()
        }
        // Flush anything still queued from startup; the binary accumulates messages mid-turn.
        pump()
    }

    fun removeQueued(index: Int) {
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
    }

    /**
     * Broker callback (off-EDT) for auto-approved edits in acceptEdits/bypassPermissions: there is no card,
     * but the user still wants to see the change. Capture the file's current contents *now* — before the binary
     * writes (which happens right after we answer allow) — and open the diff tab on the EDT with that snapshot.
     */
    private fun autoOpenDiff(toolName: String, input: JsonObject) {
        val path = DiffPresenter.filePathOf(input) ?: return
        val file = File(path)
        val snapshot = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        edt { DiffPresenter.openDiff(project, toolName, input, snapshot) }
    }

    /** Invoked by the chat UI when the user clicks Accept/Reject on a permission card. */
    fun resolvePermission(requestId: String, allow: Boolean, denyMessage: String? = null) {
        val request = pending.remove(requestId) ?: return
        if (allow) {
            if (request.reviewable) DiffPresenter.filePathOf(request.input)?.let { pendingRefresh.add(it) }
            write(ControlProtocol.permissionAllow(requestId, request.input))
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

    fun changeThinkingTokens(tokens: Int?) {
        thinkingTokens = tokens
        if (isRunning()) write(ControlProtocol.setMaxThinkingTokensRequest(ControlProtocol.newRequestId(), tokens))
        fireState()
    }

    /** Launch-time options (tool allow/deny lists, setting sources, partial streaming). Take effect on (re)start. */
    fun configureLaunchOptions(
        allowedTools: String,
        disallowedTools: String,
        settingSources: String,
        includePartialMessages: Boolean,
    ) {
        this.allowedTools = allowedTools
        this.disallowedTools = disallowedTools
        this.settingSources = settingSources
        this.includePartialMessages = includePartialMessages
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
        pendingControl[id] = { res -> val mapped = map(res.payload); edt { onResult(mapped) } }
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
                val text = event.content.trim()
                if (text.isNotBlank()) {
                    transcript.addToolOutput(event.toolUseId, text, parentToolUseId = event.parentToolUseId)
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
        edt {
            turnActive = false
            ready = false
            clearPending()
            if (exitCode != 0) transcript.add(Speaker.ERROR, "Claude Code exited (code $exitCode).")
            else systemNotice("Session ended.")
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
        for (path in paths) {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path)?.let {
                VfsUtil.markDirtyAndRefresh(true, false, false, it)
            }
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun write(line: String) = process?.writeLine(line)

    private fun systemNotice(message: String) = edt { transcript.add(Speaker.SYSTEM, message) }

    private fun fireState() = listeners.forEach { it.onStateChanged() }
    private fun fireMetadata() = listeners.forEach { it.onMetadataChanged() }
    private fun firePermissions() = listeners.forEach { it.onPermissionsChanged() }

    private fun edt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    private fun notifyError(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Claude Code", content, NotificationType.ERROR)
            .notify(project)
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
    }

    companion object {
        const val NOTIFICATION_GROUP = "Claude Code"

        /** Shift+Tab cycles through these, like the CLI. */
        val PERMISSION_MODES_CYCLE = listOf("default", "acceptEdits", "plan")

        /** Full set of modes for the GUI menu. */
        val PERMISSION_MODES = listOf("default", "acceptEdits", "plan", "bypassPermissions", "dontAsk", "auto")

        val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

        /** Setting-source scopes (--setting-sources), for the Settings checkboxes. */
        val SETTING_SOURCES = listOf("user", "project", "local")

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
