package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable
import dev.lain.claudejb.process.ClaudeLoginFlow
import dev.lain.claudejb.process.ClaudeProcess
import dev.lain.claudejb.process.TerminalLauncher
import dev.lain.claudejb.protocol.AccountInfo
import dev.lain.claudejb.protocol.AgentInfo
import dev.lain.claudejb.protocol.AuthStatusInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.ClaudeJson
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.DialogResponder
import dev.lain.claudejb.protocol.ElicitationRequest
import dev.lain.claudejb.protocol.InitializeResponse
import dev.lain.claudejb.protocol.parseElicitationFields
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.SlashCommand
import dev.lain.claudejb.protocol.TaskProgressInfo
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
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

    // --- extracted collaborators (the session delegates to these; see each class for the contract) ---
    private val tokens = TokenAccountant()
    private val taskTracker = TaskTracker()
    private val reconciler = TranscriptReconciler(transcript)
    private val diffs = DiffLifecycleManager(project)
    private val rollback = RollbackManager(project, transcript, diffs, reseedReadState = ::seedReadState)
    private val controlClient = SessionControlClient(write = ::write)
    private val cards = PermissionCardManager(::firePermissions)
    private val hookBroker = HookBroker()
    private val hookNarrator = HookActivityNarrator(transcript)

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
    // E6 advanced launch options (null/empty = flag omitted). Captured into the LaunchOptions snapshot per (re)start.
    @Volatile var maxTurns: Int? = null; private set
    @Volatile var maxBudgetUsd: Double? = null; private set
    @Volatile var fallbackModel: String? = null; private set
    @Volatile var addDirs: List<String> = emptyList(); private set
    @Volatile var betas: String? = null; private set
    @Volatile var strictMcpConfig: Boolean = false; private set
    @Volatile var outputStyle: String = "default"; private set
    @Volatile var turnActive: Boolean = false; private set
    /** True between an interrupt request and its ack/timeout/turn-end — drives the Stop button's "Interrupting…" label. */
    @Volatile var interrupting: Boolean = false; private set
    @Volatile var rateLimit: RateLimitInfo? = null; private set

    // --- live state surfaced by the system/* events; read by the GUI / diagnostics / tests ---
    /** Authoritative turn state from session_state_changed (idle | running | requires_action), or null pre-first-event. */
    @Volatile var sessionState: String? = null; private set
    /** Latest auth_status from the binary (re-auth in progress / output / error), or null when never reported. */
    @Volatile var authStatus: AuthStatusInfo? = null; private set
    /** Live reasoning-token estimate from thinking_tokens (running total for the current thinking block). */
    @Volatile var liveThinkingTokens: Int = 0; private set
    /** Predicted next user prompt (prompt_suggestion), or null when none / cleared. Drives the composer chip. */
    @Volatile var promptSuggestion: String? = null; private set
    /** Observable map of subagent tasks keyed by task_id (task_started/progress/updated/notification). */
    val subagentTasks: Map<String, TaskProgressInfo> get() = taskTracker.tasks
    /**
     * The live background-task set from `system/background_tasks_changed` — a LEVEL signal (REPLACE semantics),
     * deliberately independent of [subagentTasks] (the SDK forbids correlating the level with the edge stream).
     */
    val backgroundTasks: List<dev.lain.claudejb.protocol.BackgroundTaskInfo> get() = taskTracker.backgroundTasks

    // Token counters live in [TokenAccountant]; these getters keep the public field names the UI already reads.
    // Live = the currently-streaming message's running totals (folded into the session counters at message_start
    // and result); session = the accumulated totals. All four components are tracked because
    // cache_creation_input_tokens alone is typically the largest line item, so output_tokens alone under-reports.
    val liveInputTokens get() = tokens.liveInputTokens
    val liveCacheCreationTokens get() = tokens.liveCacheCreationTokens
    val liveCacheReadTokens get() = tokens.liveCacheReadTokens
    val liveOutputTokens get() = tokens.liveOutputTokens

    val sessionInputTokens get() = tokens.sessionInputTokens
    val sessionCacheCreationTokens get() = tokens.sessionCacheCreationTokens
    val sessionCacheReadTokens get() = tokens.sessionCacheReadTokens
    val sessionOutputTokens get() = tokens.sessionOutputTokens

    /** Total tokens for the whole session including the message currently in flight. */
    fun totalTokens(): Int = tokens.totalTokens()

    @Volatile private var ready = false

    // Set once when we've offered the "sign in" prompt for this auth failure, so a retry storm doesn't fire a
    // notification per failed turn. Reset on the next clean (non-error) result, or on a successful login.
    @Volatile private var loginPrompted = false

    // The in-flight native login flow (see startLogin) and the OAuth URL it surfaced, for the code dialog's hint.
    @Volatile private var loginFlow: ClaudeLoginFlow? = null
    @Volatile private var loginAuthUrl: String? = null

    // --- streaming-delta coalescing (perf) ---------------------------------------------------------------
    // The binary emits text_delta/thinking_delta at 20-100Hz during streaming; doing one edt{} (invokeLater)
    // per delta floods the EDT. Instead we accumulate consecutive deltas on the reader thread (onEvent is
    // single-threaded, called only from the process stdout reader) and flush them in a SINGLE edt{} the
    // moment ANY non-delta event arrives, plus on message boundary / finalize / Result / stop / terminate.
    // Because invokeLater preserves submission order, flushing before the triggering event's own edt{} keeps
    // text/thinking strictly ordered with everything else. We also fold LiveUsage into the buffer so the
    // running token total is applied in the same flush hop instead of its own invokeLater per delta.
    //
    // [deltaRuns] is an ordered list of same-type runs (true = thinking, false = assistant text); consecutive
    // deltas of the same type are merged into one run, but a type switch starts a new run so the reconciler
    // still sees the exact interleaving (a text delta ends any live thinking block). Mutated on the reader thread
    // (buffer*) but also drained on the EDT (flushDeltas via stop()/restart() while a turn is still streaming), so
    // all three access points hold [deltaLock] — the buffer is tiny, the lock is uncontended in the common path.
    private val deltaLock = Any()
    private val deltaRuns = ArrayList<Pair<Boolean, StringBuilder>>()
    private var pendingUsage: IntArray? = null  // [input, cacheCreation, cacheRead, output]; latest snapshot wins (matches TokenAccountant.onLiveUsage)

    /** Buffer a streaming delta (thinking or assistant text), coalescing same-type runs. [deltaLock]-guarded. */
    private fun bufferDelta(isThinking: Boolean, text: String) = synchronized(deltaLock) {
        val last = deltaRuns.lastOrNull()
        if (last != null && last.first == isThinking) last.second.append(text)
        else deltaRuns.add(isThinking to StringBuilder(text))
    }

    /** Fold a LiveUsage event into the pending buffer (latest live total wins). [deltaLock]-guarded. */
    private fun bufferUsage(input: Int, cacheCreation: Int, cacheRead: Int, output: Int) = synchronized(deltaLock) {
        pendingUsage = intArrayOf(input, cacheCreation, cacheRead, output)
    }

    /**
     * If any deltas/usage are buffered, apply them in ONE batch so the reconciler applies them (in order) and the
     * token counter is updated, before the caller's own edt{} for the triggering event. No-op when empty.
     * Safe to call from the reader thread (every non-delta branch) or the EDT (stop/restart) — the snapshot+clear
     * is [deltaLock]-guarded so it never races a concurrent buffer*. When already on the EDT (e.g. stop()/dispose()
     * tearing down a still-streaming turn) the drain runs SYNCHRONOUSLY so the final buffered text is never lost to a
     * queued-but-never-run invokeLater; off the EDT it goes through edt{} (invokeLater) as before.
     */
    private fun flushDeltas() {
        val runs: List<Pair<Boolean, String>>
        val usage: IntArray?
        synchronized(deltaLock) {
            if (deltaRuns.isEmpty() && pendingUsage == null) return
            runs = if (deltaRuns.isEmpty()) emptyList() else deltaRuns.map { it.first to it.second.toString() }
            usage = pendingUsage
            deltaRuns.clear()
            pendingUsage = null
        }
        val apply = {
            for ((isThinking, text) in runs) {
                if (isThinking) reconciler.appendThinking(text) else reconciler.appendAssistant(text)
            }
            if (usage != null) tokens.onLiveUsage(usage[0], usage[1], usage[2], usage[3])
        }
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread) apply() else edt { apply() }
    }

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
    // Bumped on every start(); a process's onTerminated carries the generation it was launched under, so a
    // restart's old-process termination callback (which arrives asynchronously, after the new process is up)
    // is ignored instead of tearing down the freshly-started session. See start()/onTerminated().
    @Volatile private var generation = 0
    // True from the moment start() dispatches its launch until that launch publishes the process (or bails). Set
    // synchronously on the EDT in start() so a second send()→start() during the (multi-second) env-resolution
    // window can't spawn a SECOND claude process for the same session. Cleared by the launch's own pooled block
    // (only when it still owns the current generation) and reset by stop()/dispose() so a restart can proceed.
    @Volatile private var starting = false
    /**
     * Pending-prompt buffer. [ArrayDeque] is NOT thread-safe; **the queue is only ever touched on the EDT**
     * (send / sendSideQuestion / removeQueued / pump wrap their queue access in [edt]). Do not access it from a
     * background thread — confinement, not a concurrent structure, is the invariant here.
     */
    private val queue = ArrayDeque<Outgoing>()

    /** One buffered prompt: the wire [text], its base64 [images] (mediaType→data), and the [displayText] shown in the transcript/queue strip. */
    private data class Outgoing(val text: String, val images: List<Pair<String, String>>, val displayText: String)

    private val listeners = CopyOnWriteArrayList<SessionListener>()

    // --- session-scoped quota poll (perf) --------------------------------------------------------------
    // Previously every ChatPanel ran its own 60s javax.swing.Timer that fired get_session_cost +
    // get_context_usage; N tabs of the same session meant N identical polls. We now run ONE timer per
    // session, cache the results here, and notify the panel(s) via the existing onStateChanged() listener
    // callback — so any number of ChatPanels observing this session share a single poll. The timer is an EDT
    // (javax.swing) Timer so its callback and the cached-field writes stay on the EDT, matching the rest of
    // the GUI; it runs only while at least one listener (panel) is attached and is stopped on dispose/last-remove.
    /** Latest `get_session_cost` payload (or null until the first poll returns). Read by ChatPanel on the EDT. */
    @Volatile var lastSessionCost: JsonObject? = null; private set
    /** Latest `get_context_usage` result (or null until the first poll returns). Read by ChatPanel on the EDT. */
    @Volatile var lastContextUsage: ContextUsage? = null; private set

    /** Working directory the binary runs in (the project root) — shown synchronously in the session dashboard. */
    val workingDir: String? get() = project.basePath

    /** Cached CLI binary version for the session dashboard; populated lazily by the panel via [requestBinaryVersion]. */
    @Volatile var binaryVersion: String? = null

    /** Client-generated id of the current user turn (tagged on each prompt) — the rewind_files() anchor. */
    @Volatile var currentUserMessageId: String? = null; private set
    private val toolUseTurn = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The user-turn id an edit ([toolUseId]) belongs to, or null when unknown (then rewind isn't possible). */
    fun userMessageIdFor(toolUseId: String): String? = toolUseTurn[toolUseId]

    /** Whether file-checkpointing is enabled (native rewind requires it). */
    val checkpointingEnabled: Boolean get() = ClaudeSettings.getInstance(project).enableFileCheckpointing

    private val quotaPollTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollQuota() }.apply { isRepeats = true }

    /**
     * Fire one session-cost + context-usage poll; results are cached and pushed to panels via [fireState].
     * No-op while the process is not running (the control requests would deliver null and clobber the cached
     * last-good values, blanking the usage meter); and even when running we only overwrite the cache on a
     * non-null result, so a transient null never blanks the panels — the last good values stay until a real one arrives.
     */
    private fun pollQuota() {
        if (!isRunning()) return
        requestSessionCost { cost -> if (cost != null) { lastSessionCost = cost; fireState() } }
        requestContextUsage { cu -> if (cu != null) { lastContextUsage = cu; fireState() } }
    }

    private val broker by lazy {
        PermissionBroker(
            permissionMode = { permissionMode },
            respond = ::write,
            onApprovedWrite = { diffs.markForRefresh(it) },
            present = ::presentPermission,
            onAutoReviewed = diffs::autoOpenDiff,
            projectRoot = project.basePath,
            isRemembered = { toolName, input -> ClaudeSettings.getInstance(project).isToolAlwaysAllowed(toolName, input) },
        )
    }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
        // Start the shared quota poll on the first observer (a ChatPanel). javax.swing.Timer must be started on
        // the EDT; addListener is called from the GUI (ChatPanel.init, on the EDT) but guard with edt{} so a
        // non-EDT caller can't break the timer's thread affinity. Idempotent: Timer.start() is a no-op if running.
        edt { if (!quotaPollTimer.isRunning) quotaPollTimer.start() }
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
        // Stop the shared poll once no panel observes this session anymore (no leaked timer).
        edt { if (listeners.isEmpty() && quotaPollTimer.isRunning) quotaPollTimer.stop() }
    }

    fun isRunning(): Boolean = process?.isRunning() == true
    fun queuedPrompts(): List<String> = queue.map { it.displayText }
    fun pendingPermissions(): List<PendingPermission> = cards.all()

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
        // `starting` blocks a concurrent launch (the double-spawn bug): two send()→start() calls in the
        // env-resolution window both saw isRunning()==false and each spawned a process. start() runs on the EDT,
        // so this check/set is race-free between start() calls.
        if (isRunning() || starting) return true
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
        starting = true
        reconciler.onMessageBoundary()
        val launchGen = ++generation // this launch's generation; the process's onTerminated is gated on it

        // Off the EDT: env resolution sources a shell (seconds) and process spawn can block. Hand back to the EDT
        // for the state mutations the GUI observes (ready/fireState/pump) and the queue invariant.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val env = cachedEnv ?: settings.resolveEnv().also { cachedEnv = it }
                // A stop()/dispose()/newer start() may have raced in during the (slow) env resolution. If so, this
                // launch is stale — don't spawn an orphan process nothing will ever tear down.
                if (launchGen != generation) return@executeOnPooledThread
                val opts = launchOptions()
                val proc = ClaudeProcess(
                    binary = binary,
                    workDir = workDir,
                    args = SessionLauncher.buildArgs(opts, resume, SessionLauncher.mcpConfigJson(opts)),
                    nodeOverride = settings.nodePath,
                    extraEnv = env,
                    onEvent = ::onEvent,
                    onTerminated = { code -> onTerminated(launchGen, code) },
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
                // If a teardown raced in between the gen-check and now, destroy the freshly-spawned orphan.
                if (launchGen != generation) {
                    proc.destroy()
                    if (process === proc) process = null
                    return@executeOnPooledThread
                }

            // Optional handshake → rich command/model/agent metadata for the GUI menus.
            controlClient.query(
                buildRequest = ControlProtocol::initializeRequest,
                decode = { payload ->
                    payload?.let {
                        runCatching { ClaudeJson.decodeFromJsonElement(InitializeResponse.serializer(), it) }
                            .onFailure { e -> log.debug("Failed to decode initialize response", e) }
                            .getOrNull()
                    }
                },
                onResult = { info: InitializeResponse? ->
                    info ?: return@query
                    commands = info.commands
                    models = info.models
                    agents = info.agents
                    availableOutputStyles = info.availableOutputStyles
                    account = info.account
                    if (info.outputStyle.isNotBlank()) outputStyle = info.outputStyle
                    edt { fireMetadata() }
                },
            )

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
            } finally {
                // Release the launch guard, but only if we still own the current generation — a newer start()
                // bumped it and is now the owner, so it must keep `starting` set.
                if (launchGen == generation) starting = false
            }
        }
        return true
    }

    /** Immutable snapshot of every launch-affecting option, captured once per (re)start for [SessionLauncher]. */
    private fun launchOptions() = SessionLauncher.LaunchOptions(
        model = model,
        effort = effort,
        permissionMode = permissionMode,
        thinkingTokens = thinkingTokens,
        allowedTools = allowedTools,
        disallowedTools = disallowedTools,
        settingSources = settingSources,
        includePartialMessages = includePartialMessages,
        ideMcpEnabled = ideMcpEnabled,
        ideMcpTransport = ideMcpTransport,
        ideMcpPort = ideMcpPort,
        customMcpServers = customMcpServers,
        maxTurns = maxTurns,
        maxBudgetUsd = maxBudgetUsd,
        fallbackModel = fallbackModel,
        addDirs = addDirs,
        betas = betas,
        strictMcpConfig = strictMcpConfig,
        sessionId = sessionId,
    )

    /** Stops the current process and starts a fresh one, resuming the same session if possible. */
    fun restart(resume: Boolean = true) {
        stop()
        start(resume)
    }

    fun stop() {
        // Abandon the current process generation immediately, so its asynchronous onTerminated (fired by the
        // destroy() below) is treated as stale and won't tear down a session that restart() is about to spin up.
        generation++
        // Flush any buffered streaming deltas so partial text isn't lost when the process goes away.
        flushDeltas()
        // Default-cancel any pending MCP elicitation cards while the process is still alive, so the binary isn't
        // left waiting on an ElicitResult when the session is torn down.
        cancelPendingElicitations()
        process?.closeStdin()
        process?.destroy()
        process = null
        turnActive = false
        interrupting = false
        ready = false
        starting = false // any in-flight launch is now stale (generation bumped above); let a restart proceed
        // Reset per-turn live state so a stale figure/chip doesn't linger into a resumed session (restart path).
        liveThinkingTokens = 0
        promptSuggestion = null
        // Drop the cached env so a settings change to the source script is re-sourced on the next start.
        cachedEnv = null
        controlClient.failAll("process gone")
        taskTracker.clear()
        hookNarrator.clear()
        edt { cards.clear(); diffs.clearReviewDiffs(); fireState() }
    }

    // -----------------------------------------------------------------------
    // Sending prompts / commands (multiprompt)
    // -----------------------------------------------------------------------

    /**
     * Queues [text] for sending. If idle it is dispatched immediately; if a turn is in progress it waits
     * in the queue and is flushed when the current turn finishes (one prompt per turn).
     * Slash commands are just user content beginning with '/'.
     */
    fun send(text: String) = send(text, emptyList())

    /**
     * Queues a prompt with rich [attachments]. File/selection attachments fold into the prompt text (an `@path`
     * mention / a fenced `path:line` block); images become base64 content blocks on the wire (see
     * [ControlProtocol.userMessageWithImages]). The combined text + images travel together as one [Outgoing] so the
     * queue/turn semantics are unchanged. A turn with only images (blank text) is still valid.
     */
    fun send(text: String, attachments: List<Attachment>) {
        val root = project.basePath
        val nonImage = attachments.filter { it !is Attachment.Image }
        val trimmed = text.trim().takeIf { it.isNotEmpty() }
        // The wire text (sent to the binary) and the display text (shown in the transcript) DIFFER for file
        // attachments: the binary needs an `@<cwd-relative>` mention it actually expands, while the chat shows a
        // clickable jb://open link. Building them separately keeps the model's input clean (no markdown link
        // syntax) and the bubble navigable.
        val wireParts = buildList {
            trimmed?.let { add(it) }
            nonImage.forEach { add(wireMention(it, root)) }
        }
        val displayParts = buildList {
            trimmed?.let { add(it) }
            nonImage.forEach { add(displayMention(it, root)) }
        }
        val images = attachments.filterIsInstance<Attachment.Image>().map { it.mediaType to it.base64 }
        val combined = wireParts.joinToString("\n\n")
        if (combined.isEmpty() && images.isEmpty()) return
        if (!isRunning()) {
            if (!start()) return
        }
        val displayText = displayParts.joinToString("\n\n").ifEmpty { attachments.joinToString(" ") { it.displayName } }
        // Queue access is EDT-confined (the deque isn't thread-safe).
        edt {
            queue.addLast(Outgoing(combined, images, displayText))
            fireState()
            pump()
        }
    }

    /** Wire form of a non-image attachment for the binary: a FileRef becomes a `@<cwd-relative>` mention the CLI
     *  expands (absolute `@/…` paths aren't recognized); others fall back to their plain prompt text. */
    private fun wireMention(a: Attachment, root: String?): String = when (a) {
        is Attachment.FileRef -> mentionToken(relativizeForMention(root, a.path))
        else -> a.toPromptText()
    }

    /** An `@path` mention, **quoted** when the path contains whitespace so the CLI's whitespace-delimited mention
     *  parser doesn't truncate it at the first space (e.g. `src/My Notes.md` → `@"src/My Notes.md"`). */
    private fun mentionToken(path: String): String =
        if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    /** Display form shown in the user bubble: a FileRef becomes a clickable `jb://open` link to the file; others
     *  reuse their prompt text (a selection's fenced snippet, an image marker). */
    private fun displayMention(a: Attachment, root: String?): String = when (a) {
        is Attachment.FileRef -> {
            val enc = java.net.URLEncoder.encode(a.path, Charsets.UTF_8).replace("+", "%20")
            "[@${a.displayName}](jb://open?file=$enc&line=1)"
        }
        else -> a.toPromptText()
    }

    /** A project-root-relative path for an `@` mention (forward slashes), or the original path when it's outside
     *  the root or can't be relativized (the CLI won't expand that absolute fallback — a known limitation for
     *  out-of-root attachments). Delegates to the shared [dev.lain.claudejb.context.FilePickerHelper.relativeWithinRoot]. */
    private fun relativizeForMention(root: String?, path: String): String =
        dev.lain.claudejb.context.FilePickerHelper.relativeWithinRoot(root, path) ?: path

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
                queue.addLast(Outgoing(trimmed, emptyList(), trimmed))
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
            transcript.add(Speaker.USER, next.displayText)
            // Tag each prompt with a client-generated uuid and remember it as the current turn, so
            // edits that follow can be mapped back to this point for a native rewind_files().
            val msgUuid = java.util.UUID.randomUUID().toString()
            currentUserMessageId = msgUuid
            write(ControlProtocol.userMessageWithImages(next.text, next.images, uuid = msgUuid))
            turnActive = true
        }
        promptSuggestion = null // a new prompt was sent; the previous turn's suggestion is now stale
        fireState()
    }

    /** Clears the predicted next-prompt chip (on send / dismiss). Public so the composer can drive it. */
    fun clearSuggestion() {
        if (promptSuggestion == null) return
        promptSuggestion = null
        edt { fireState() }
    }

    /**
     * Interrupts the active turn. Sent as a **correlated** control request (via [controlClient]) so the binary's
     * `control_response` — or the watchdog timeout — reliably clears the turn state. The previous fire-and-forget
     * write left `turnActive` stuck forever (the ack was discarded) and added a permanent "Interrupting…" transcript
     * row that re-rendered on every state push, so the turn never appeared to stop.
     */
    fun interrupt() {
        if (!isRunning()) return
        edt {
            if (interrupting) return@edt // already interrupting — don't double-send or re-clear the queue
            // Release any pending permission/question/elicitation request BEFORE clearing the cards, so the binary
            // isn't left blocked waiting for a decision after the interrupt (a can_use_tool blocks the turn).
            // Elicitations get an ElicitResult cancel; everything else an explicit deny. No "Rejected" transcript
            // spam — this is teardown, not a user action.
            cancelPendingElicitations()
            cards.all().filter { it.elicitation == null }.forEach {
                write(ControlProtocol.permissionDeny(it.requestId, "Interrupted."))
            }
            // Cancel queued prompts so the interrupt doesn't immediately re-pump a brand-new turn (which read as
            // "it never stops").
            queue.clear()
            cards.clear()
            diffs.clearReviewDiffs()
            interrupting = true
            fireState()
            controlClient.query(
                buildRequest = ControlProtocol::interruptRequest,
                onResult = { _: JsonObject? -> edt { finishInterrupt() } },
                decode = { it },
            )
        }
    }

    /** Clears the interrupt/turn state once the binary acks (or the watchdog times out). Idempotent. */
    private fun finishInterrupt() {
        interrupting = false
        turnActive = false
        liveThinkingTokens = 0
        fireState()
    }

    // -----------------------------------------------------------------------
    // Permissions — non-modal: requests surface as cards in the chat and the user
    // resolves them with Accept/Reject (no blocking dialogs).
    // -----------------------------------------------------------------------

    /** Broker callback (off-EDT): a tool needs the user's decision. Queue it for the UI to render. */
    private fun presentPermission(request: PendingPermission) = edt {
        cards.present(request)
        // Reviewable edits: open an EDITABLE diff in the IDE so the user can review AND tweak the change before
        // accepting (Accept writes whatever they leave in the editor). Auto-approve modes use diffs.autoOpenDiff.
        if (request.reviewable && request.toolName in DiffPresenter.REVIEWABLE_TOOLS) {
            diffs.openReviewDiff(request.requestId, request.toolName, request.input)
        }
        fireAttention(AttentionReason.PERMISSION)
    }

    /** The persisted pre-write snapshot for a reviewable tool call, or null if none was captured (e.g. rejected). */
    fun editSnapshot(toolUseId: String): EditSnapshot? = diffs.snapshot(toolUseId)

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
        // A restored transcript is a different timeline — drop any rewind turn-anchors from before.
        toolUseTurn.clear()
        currentUserMessageId = null
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
        val request = cards.remove(requestId) ?: return
        if (allow) {
            if (request.reviewable) {
                // Snapshot/refresh stay on the ORIGINAL input: they describe the real file (before-text + path),
                // independent of any narrowed payload (e.g. an edited review diff) we actually send.
                DiffPresenter.filePathOf(request.input)?.let { diffs.markForRefresh(it) }
                // Snapshot before answering allow (the binary writes right after), so "View diff" works from the
                // transcript once the transient approval diff has closed. Synchronous read — small project files.
                request.toolUseId?.let { diffs.captureForReview(request.toolName, request.input, it) }
            }
            // If an editable review diff was open and the user TWEAKED the proposed content, write THEIR version:
            // re-encode the tool input so the binary writes the edited text (file_path preserved). Closes the diff.
            // Fail-safe: no edit (or read-only viewer) → reviewOverride is null → the binary writes its own version.
            val reviewOverride = diffs.takeReviewEdit(requestId)?.let { (currentText, editedText) ->
                dev.lain.claudejb.diff.HunkSelection.encodeInput(request.toolName, request.input, currentText, editedText)
            }
            val effectiveInput = overrideInput ?: reviewOverride ?: request.input
            // If the user edited the proposed content (or an override narrowed the write), repoint the captured
            // snapshot at the EFFECTIVE input so the transcript's inline diff + "View diff" show what was actually
            // written — not Claude's original proposal.
            if (request.reviewable && effectiveInput !== request.input) {
                request.toolUseId?.let { diffs.updateSnapshotInput(it, effectiveInput) }
            }
            write(ControlProtocol.permissionAllow(requestId, effectiveInput))
            systemNotice("Approved ${request.headline}")
            // Approving an ExitPlanMode plan leaves plan mode: the plugin is the source of truth for
            // permissionMode, so flip it back to default (and push set_permission_mode) — otherwise the binary
            // proceeds out of plan while the chip stays stuck on "plan".
            if (request.isPlan && permissionMode == PermissionMode.PLAN.wire) {
                changePermissionMode(PermissionMode.DEFAULT.wire)
            }
        } else {
            diffs.closeReviewDiff(requestId) // reject → discard the review diff tab
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
        val request = cards.remove(requestId) ?: return
        val updated = buildJsonObject {
            request.input.forEach { (k, v) -> put(k, v) }
            put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
        }
        write(ControlProtocol.permissionAllow(requestId, updated))
        systemNotice("Answered Claude's question")
        firePermissions()
    }

    /**
     * Surfaces an MCP `elicitation` (binary -> host) as a non-modal card. The user's Accept/Decline/Cancel (via
     * [resolveElicitation]) is what writes the ElicitResult. EDT-confined, like every other card operation.
     */
    private fun presentElicitation(requestId: String, req: ElicitationRequest) = edt {
        cards.present(
            PendingPermission(
                requestId = requestId,
                toolName = "elicitation",
                input = JsonObject(emptyMap()),
                title = req.displayName?.ifBlank { null } ?: req.title?.ifBlank { null } ?: req.mcpServerName,
                summary = "",
                reviewable = false,
                elicitation = ElicitationCard(
                    serverName = req.mcpServerName,
                    message = req.message,
                    description = req.description?.ifBlank { null },
                    mode = req.mode,
                    url = req.url,
                    fields = parseElicitationFields(req.requestedSchema),
                ),
            )
        )
        fireAttention(AttentionReason.PERMISSION)
    }

    /** Invoked by the chat UI when the user resolves an elicitation card. Writes the ElicitResult and clears it. */
    fun resolveElicitation(requestId: String, action: String, content: JsonObject?) {
        cards.remove(requestId) ?: return
        write(ControlProtocol.elicitationResult(requestId, action, content))
        systemNotice("Elicitation: $action")
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
        if (isRunning()) write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), SessionLauncher.binaryPermissionMode(permissionMode)))
        fireState()
    }

    /** Effort is a launch flag; it takes effect on the next (re)start. */
    fun changeEffort(value: String?) {
        effort = value
        fireState()
    }

    /** The active API provider (persisted in settings). Anthropic = native auth; others = own key. */
    val provider: Provider get() = ClaudeSettings.getInstance(project).provider

    /**
     * Switch the API provider. The provider's `ANTHROPIC_BASE_URL`/`ANTHROPIC_API_KEY` are launch env, so the
     * change requires a restart (we invalidate the cached env and resume via `--resume`).
     *
     * SECURITY: a third-party provider needs its OWN isolated key. If none is stored we do NOT switch and do
     * NOT restart — we prompt the user to configure it (Settings → password safe). Restarting into a keyless
     * third-party provider would silently fall back to Anthropic's native auth, which is confusing and not what
     * the user asked for; and we never reuse Anthropic credentials for another provider.
     */
    fun changeProvider(target: Provider) {
        val settings = ClaudeSettings.getInstance(project)
        if (target == settings.provider) return
        if (target.requiresApiKey && settings.getProviderApiKey(target).isBlank()) {
            notifyConfigureProviderKey(target)
            return
        }
        val wasRunning = isRunning()
        settings.getState().provider = target.id
        cachedEnv = null // provider env changed → re-resolve on next start
        fireState()
        if (wasRunning) {
            systemNotice("Provider → ${target.label} — restarting session.")
            restart(resume = true)
        }
    }

    /** Warn that a third-party provider needs its own key and offer to open Settings. No provider switch. */
    private fun notifyConfigureProviderKey(target: Provider) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "${target.label} needs its own API key. Configure it in Settings — the provider isn't switched " +
                    "until a key is set, and your Anthropic credentials are never used for another provider.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimple("Configure…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
            })
            .notify(project)
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
        maxTurns: Int? = null,
        maxBudgetUsd: Double? = null,
        fallbackModel: String? = null,
        addDirs: List<String> = emptyList(),
        betas: String? = null,
        strictMcpConfig: Boolean = false,
    ) {
        this.allowedTools = allowedTools
        this.disallowedTools = disallowedTools
        this.settingSources = settingSources
        this.includePartialMessages = includePartialMessages
        this.ideMcpEnabled = ideMcpEnabled
        this.ideMcpTransport = ideMcpTransport.ifBlank { "sse" }
        this.ideMcpPort = ideMcpPort.takeIf { it in 1..65535 } ?: DEFAULT_IDE_MCP_PORT
        this.customMcpServers = customMcpServers
        this.maxTurns = maxTurns
        this.maxBudgetUsd = maxBudgetUsd
        this.fallbackModel = fallbackModel
        this.addDirs = addDirs
        this.betas = betas
        this.strictMcpConfig = strictMcpConfig
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

    fun requestContextUsage(onResult: (ContextUsage?) -> Unit) {
        if (!isRunning()) { edt { onResult(null) }; return }
        controlClient.query(
            buildRequest = ControlProtocol::getContextUsageRequest,
            onResult = { mapped: ContextUsage? -> edt { onResult(mapped) } },
            decode = { payload -> payload?.let { runCatching { ClaudeJson.decodeFromJsonElement(ContextUsage.serializer(), it) }.getOrNull() } },
        )
    }

    fun requestSessionCost(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) { edt { onResult(null) }; return }
        controlClient.query(ControlProtocol::getSessionCostRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    fun requestMcpStatus(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) { edt { onResult(null) }; return }
        controlClient.query(ControlProtocol::mcpStatusRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    /** Effective merged settings + per-source breakdown (E2-UI diagnostics dialog). */
    fun requestSettings(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) { edt { onResult(null) }; return }
        controlClient.query(ControlProtocol::getSettingsRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    /** The responder's CLI binary version (E2-UI diagnostics dialog). */
    fun requestBinaryVersion(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) { edt { onResult(null) }; return }
        controlClient.query(ControlProtocol::getBinaryVersionRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    /** Refresh the VFS for files the binary changed during a rewind so the editor reflects them. */
    fun refreshAfterRewind(paths: List<String>) {
        paths.forEach { diffs.markForRefresh(it) }
        diffs.refreshTouched()
    }

    /** Result of a `rewind_files` control request. */
    data class RewindResult(val canRewind: Boolean, val error: String?, val filesChanged: List<String>)

    /**
     * Ask the binary to rewind tracked files to the state at [userMessageId] (a turn anchor). With [dryRun]
     * true the binary only reports feasibility (`canRewind`) without touching files. Result on the EDT; null
     * on timeout / not running.
     */
    fun requestRewindFiles(userMessageId: String, dryRun: Boolean, onResult: (RewindResult?) -> Unit) {
        if (!isRunning()) { edt { onResult(null) }; return }
        controlClient.query(
            buildRequest = { id -> ControlProtocol.rewindFilesRequest(id, userMessageId, dryRun) },
            onResult = { mapped: RewindResult? -> edt { onResult(mapped) } },
            decode = { payload ->
                payload?.let {
                    RewindResult(
                        canRewind = (it["canRewind"] ?: it["can_rewind"])?.let { e -> (e as? JsonPrimitive)?.booleanOrNull } ?: false,
                        error = ((it["error"] ?: it["message"]) as? JsonPrimitive)?.contentOrNull,
                        filesChanged = ((it["filesChanged"] ?: it["files_changed"]) as? JsonArray)
                            ?.mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    )
                }
            },
        )
    }

    /** Reconnects a disconnected/failed MCP server; fire-and-forget (the UI re-queries mcp_status after). */
    fun reconnectMcp(name: String) {
        if (isRunning()) write(ControlProtocol.mcpReconnectRequest(ControlProtocol.newRequestId(), name))
    }

    /** Enables/disables an MCP server; fire-and-forget (the UI re-queries mcp_status after). */
    fun toggleMcp(name: String, enabled: Boolean) {
        if (isRunning()) write(ControlProtocol.mcpToggleRequest(ControlProtocol.newRequestId(), name, enabled))
    }

    /** Stops a running background task/subagent by id (E10 tasks panel). */
    fun stopTask(taskId: String) {
        if (isRunning()) write(ControlProtocol.stopTaskRequest(ControlProtocol.newRequestId(), taskId))
    }

    /** Reseeds the binary's read-state for a file (path + mtime) after an IDE-side rollback; no-op when down. */
    private fun seedReadState(path: String, mtime: Long) {
        if (isRunning()) write(ControlProtocol.seedReadStateRequest(ControlProtocol.newRequestId(), path, mtime))
    }

    // -----------------------------------------------------------------------
    // File rollback — delegated to [RollbackManager] (diff history panel)
    // -----------------------------------------------------------------------

    /** Every reviewable file-writing edit in this session that has a captured snapshot, oldest first. */
    fun reviewableEdits(): List<ReviewableEdit> = rollback.reviewableEdits()

    /** IDE-side revert of one edit (restore beforeText, refresh VFS, reseed read-state). EDT-only. Surfaces a
     *  notification either way, so a click is never a silent no-op. */
    fun revertEdit(snapshot: EditSnapshot): Boolean {
        val name = java.io.File(snapshot.filePath).name
        val ok = rollback.revertEdit(snapshot)
        if (ok) notifyInfo("Reverted $name to its state before this edit.")
        else notifyError("Couldn't revert $name (the file may be outside the project, missing, or locked).")
        return ok
    }

    /** Rolls every edited file back to its oldest captured state. Returns the number reverted. EDT-only. Surfaces
     *  a summary notification. */
    fun revertAllEdits(): Int {
        val n = rollback.revertAllEdits()
        if (n > 0) notifyInfo("Rolled back $n file${if (n == 1) "" else "s"} to the state before Claude's edits.")
        else notifyError("No files were rolled back (nothing reverted).")
        return n
    }

    /** Renames the current session (E5): tells the binary, updates the tab title, notifies listeners. */
    fun renameSession(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        if (isRunning()) write(ControlProtocol.renameSessionRequest(ControlProtocol.newRequestId(), trimmed))
        this.title = trimmed
        edt { fireTitleChanged() }
    }

    // -----------------------------------------------------------------------
    // Event handling (called on the process reader thread)
    // -----------------------------------------------------------------------

    /** Test-only seam: feed a synthetic event through the same path the process reader uses, so headless
     *  tests can exercise streaming/token-accounting/transcript reconciliation without spawning the binary. */
    @org.jetbrains.annotations.TestOnly
    fun handleEventForTest(event: ClaudeEvent) {
        onEvent(event)
        // Production coalesces deltas/usage and flushes them on the NEXT non-delta event; the test seam feeds
        // one event at a time and asserts immediately after, so flush here too. The flush itself dispatches via
        // edt{} (invokeLater), so a test still pumps the EDT (dispatchAllInvocationEvents) before asserting —
        // identical reconstruction, just without requiring a trailing boundary event to drain the buffer.
        flushDeltas()
    }

    private fun onEvent(event: ClaudeEvent) {
        // Coalesce streaming deltas: buffer consecutive text/thinking deltas and the live-usage fold (reader
        // thread, no edt{}), and flush them in a single edt{} on the next non-delta event below. Order is
        // preserved because invokeLater is FIFO and the flush is submitted before the event's own edt{}.
        when (event) {
            is ClaudeEvent.TextDelta -> { bufferDelta(isThinking = false, text = event.text); return }
            is ClaudeEvent.ThinkingDelta -> { bufferDelta(isThinking = true, text = event.text); return }
            is ClaudeEvent.LiveUsage -> {
                bufferUsage(event.inputTokens, event.cacheCreationTokens, event.cacheReadTokens, event.outputTokens)
                return
            }
            else -> {}
        }
        // Any other event: flush the buffered deltas first so they land on the EDT ahead of this event's work.
        flushDeltas()
        when (event) {
            is ClaudeEvent.Init -> {
                sessionId = event.info.sessionId
                if (model == null && event.info.model.isNotBlank()) model = event.info.model
                if (event.info.outputStyle.isNotBlank()) outputStyle = event.info.outputStyle
                // The plugin is the source of truth for permissionMode. system/init re-arrives every turn and
                // reports the *launch-time* mode ("default"), which used to clobber a user choice (the
                // recurring "reset to default" bug). Never adopt it; if the binary has drifted from our mode,
                // push ours back so it converges instead.
                if (event.info.permissionMode.isNotBlank() && event.info.permissionMode != SessionLauncher.binaryPermissionMode(permissionMode)) {
                    write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), SessionLauncher.binaryPermissionMode(permissionMode)))
                }
                ready = true
                edt {
                    systemNotice("Connected · ${event.info.model.ifBlank { "claude" }} · ${event.info.cwd}")
                    fireState()
                    pump()
                }
            }

            // TextDelta / ThinkingDelta / LiveUsage are coalesced and handled before this when (see flushDeltas).
            is ClaudeEvent.TextDelta, is ClaudeEvent.ThinkingDelta, is ClaudeEvent.LiveUsage -> {}
            is ClaudeEvent.AssistantText -> edt {
                // Subagent text arrives finalized with a parent id: anchor it under its Agent without touching
                // the top-level live stream. Top-level text keeps the existing streaming reconciliation.
                if (event.parentToolUseId != null) {
                    reconciler.addSubagentText(event.text, event.parentToolUseId)
                } else {
                    reconciler.finalizeAssistant(event.text)
                }
            }
            is ClaudeEvent.AssistantThinking -> edt { reconciler.finalizeThinking(event.text) }
            is ClaudeEvent.MessageStart -> edt {
                // A turn can emit several assistant messages (e.g. around tool calls). message_delta usage
                // restarts near 0 per message, so fold the finished message's tokens into the session total
                // before the next one overwrites the live counter — otherwise only the last message counts.
                tokens.foldIntoSession()
                liveThinkingTokens = 0 // the live reasoning estimate is per thinking block; reset at each boundary
                reconciler.onMessageBoundary()
            }

            is ClaudeEvent.ToolUse -> edt {
                // Only break the top-level live stream for top-level tool calls; a subagent's tool call must not
                // cut a top-level paragraph that may continue after the Agent finishes.
                if (event.parentToolUseId == null) {
                    reconciler.onMessageBoundary()
                }
                transcript.add(
                    Speaker.TOOL,
                    formatToolUse(event.name, event.input),
                    meta = event.name,
                    toolUseId = event.id,
                    parentToolUseId = event.parentToolUseId,
                    toolState = ToolState.LOADING, // just dispatched → light blue, until progress/result arrive
                )
                // Capture the pre-write snapshot HERE (on tool_use, before the binary writes) rather than only at
                // can_use_tool approval — so the inline diff + "View diff" work in EVERY permission mode, including
                // acceptEdits/bypass/auto where the binary auto-executes without asking the host (no approval to
                // hang the snapshot on). Idempotent + cheap (a small file read); a no-op for non-reviewable tools.
                if (event.name in DiffPresenter.REVIEWABLE_TOOLS) {
                    diffs.captureForReview(event.name, event.input, event.id)
                    // Remember which user turn this edit belongs to, for a native rewind_files().
                    currentUserMessageId?.let { toolUseTurn[event.id] = it }
                }
            }

            is ClaudeEvent.ToolResult -> edt {
                // Close the auto-opened diff (if any) now that the binary has finished writing — the inline diff
                // below preserves the change visually in the tool card, and "View diff" can re-open it from the
                // snapshot at any time, so leaving the editor tab pinned just clutters the workspace. The manager
                // closes the tab and hands back the persisted pre-write snapshot for the inline diff below.
                transcript.setToolState(event.toolUseId, if (event.isError) ToolState.ERROR else ToolState.FINISHED)
                val snap = diffs.onToolResult(event.toolUseId)
                // For a reviewable write we captured the pre-write contents at approval time: render the actual
                // change as an inline unified diff (meta="diff") instead of the binary's "Edited file" blurb, so
                // the output box shows what changed. The diff text is self-contained, so it also survives a
                // session restore (no snapshot needed at render time). Falls back to the binary text otherwise.
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
                        transcript.addToolOutput(
                            event.toolUseId, text, parentToolUseId = event.parentToolUseId,
                            meta = if (event.isError) "error" else null,
                        )
                    }
                }
            }

            is ClaudeEvent.Result -> edt {
                tokens.foldIntoSession()
                reconciler.onMessageBoundary()
                turnActive = false
                interrupting = false // the turn ended (possibly via our interrupt) — clear the transient label
                liveThinkingTokens = 0
                if (event.result.isError) {
                    // error_* results carry no `result` text — the message is in `errors` (sdk.d.ts SDKResultError).
                    // Always surface something so a failed turn never ends silently.
                    val message = event.result.result.ifBlank {
                        event.result.errors.joinToString("\n").ifBlank { "Turn ended with error: ${event.result.subtype}" }
                    }
                    transcript.add(Speaker.ERROR, message)
                    // If the failure reads like a login/auth problem, offer to open an interactive terminal —
                    // /login can't run inside the TTY-less stream-json session.
                    if (LoginDetection.needsLogin(message)) maybePromptLogin()
                } else {
                    // A clean turn means we're authenticated; allow a future auth failure to prompt again.
                    loginPrompted = false
                }
                diffs.refreshTouched()
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

            is ClaudeEvent.RateLimit -> {
                // The binary often emits a rate_limit_event without `utilization` (it's optional and only present
                // when the API returns it). Don't lose a previously-known utilization just because a later event
                // omitted it — carry it forward so the quota % stays shown once we've seen it.
                val incoming = event.info
                rateLimit = if (incoming.utilization == null) {
                    incoming.copy(utilization = rateLimit?.utilization)
                } else incoming
                edt { fireState() }
            }

            is ClaudeEvent.PermissionRequest -> broker.handle(event.requestId, event.request)
            is ClaudeEvent.HookCallback -> handleHookCallback(event.requestId, event.request)
            // request_user_dialog: we render no custom dialog kinds — cancel (the CLI applies the dialog's default)
            // and leave a transparency note so the user sees the agent asked for one.
            is ClaudeEvent.UserDialogRequest -> {
                write(DialogResponder.response(event.requestId))
                systemNotice(DialogResponder.notice(event.dialogKind))
            }
            // elicitation: an MCP server wants user input — surface a non-modal card; the user's choice replies.
            is ClaudeEvent.Elicitation -> presentElicitation(event.requestId, event.request)
            is ClaudeEvent.UnsupportedControlRequest -> broker.rejectUnsupported(event.requestId, event.subtype)
            is ClaudeEvent.ControlResult -> controlClient.onControlResult(event)

            // --- E1: subagent task lifecycle. [TaskTracker] owns the observable map keyed by task_id (latest
            // progress wins); the rich panel lands in E10 — here we only keep the state and fire so the UI refreshes. ---
            is ClaudeEvent.TaskStarted -> edt { if (taskTracker.onStarted(event.info)) fireState() }
            is ClaudeEvent.TaskProgress -> edt { taskTracker.onProgress(event.info); fireState() }
            is ClaudeEvent.TaskUpdated -> edt { taskTracker.onUpdated(event.info); fireState() }
            is ClaudeEvent.TaskNotification -> edt {
                // Settled: drop from the live map (TaskTracker); surface a discreet notice unless asked to skip it.
                if (taskTracker.onNotification(event.info)) {
                    val label = event.info.summary.ifBlank { "Subagent ${event.info.status}" }
                    systemNotice("Subagent ${event.info.status}: $label")
                }
                fireState()
            }

            // notification → in-transcript notice; high/immediate also raises an IDE notification so it isn't missed.
            is ClaudeEvent.Notification -> {
                val text = event.info.text
                if (text.isNotBlank()) {
                    systemNotice(text)
                    if (event.info.priority == "high" || event.info.priority == "immediate") notifyInfo(text)
                }
            }

            // permission_denied → render the denial (the model only otherwise sees an is_error tool_result).
            is ClaudeEvent.PermissionDenied -> edt {
                val reason = event.info.message.ifBlank { event.info.decisionReason ?: event.info.decisionReasonType ?: "denied" }
                transcript.add(Speaker.ERROR, "Denied ${event.info.toolName}: $reason")
            }

            is ClaudeEvent.SessionStateChanged -> { sessionState = event.info.state; edt { fireState() } }
            is ClaudeEvent.AuthStatus -> {
                authStatus = event.info
                event.info.error?.takeIf { it.isNotBlank() }?.let {
                    edt {
                        transcript.add(Speaker.ERROR, "Authentication error: $it")
                        if (LoginDetection.needsLogin(it)) maybePromptLogin()
                    }
                }
                edt { fireState() }
            }

            // thinking_tokens → live reasoning estimate shown in the composer status line. EDT for single-threaded
            // counter writes; fireState so the status row repaints (thinking_tokens fires far slower than text deltas).
            is ClaudeEvent.ThinkingTokens -> edt { liveThinkingTokens = event.info.estimatedTokens; fireState() }

            is ClaudeEvent.ApiRetry -> {
                val of = if (event.info.maxRetries > 0) "/${event.info.maxRetries}" else ""
                systemNotice("Retrying (attempt ${event.info.attempt}$of)…")
            }

            // commands_changed → REPLACE the cached command list (supportedCommands() never reflects mid-session changes).
            is ClaudeEvent.CommandsChanged -> edt { commands = event.info.commands; fireMetadata() }

            // memory_recall → a collapsible "Recalled N memories" row listing what context influenced the turn.
            is ClaudeEvent.MemoryRecall -> {
                if (event.info.memories.isNotEmpty()) edt {
                    transcript.add(
                        Speaker.MEMORY,
                        MemoryRecallFormatter.body(event.info),
                        meta = MemoryRecallFormatter.summary(event.info),
                    )
                }
            }
            // prompt_suggestion → the predicted next prompt, surfaced as a clickable composer chip (see SuggestionStripPanel).
            is ClaudeEvent.PromptSuggestion -> {
                promptSuggestion = event.info.suggestion.takeIf { it.isNotBlank() }
                edt { fireState() }
            }
            is ClaudeEvent.FilesPersisted -> {
                if (event.info.files.isNotEmpty()) {
                    systemNotice("Uploaded ${event.info.files.size} file(s): " + event.info.files.joinToString(", ") { it.filename })
                }
                if (event.info.failed.isNotEmpty()) systemNotice("Failed to persist ${event.info.failed.size} file(s)")
            }
            is ClaudeEvent.PluginInstall -> {
                log.debug("plugin_install status=${event.info.status} name=${event.info.name}")
                when (event.info.status) {
                    "installed" -> systemNotice("Plugin installed${event.info.name?.let { ": $it" } ?: ""}")
                    "failed" -> systemNotice("Plugin install failed${event.info.error?.let { ": $it" } ?: ""}")
                }
            }
            // hook_started/progress/response → one evolving "⚙ Hook …" transcript row per hook (HookActivityNarrator).
            is ClaudeEvent.HookStarted -> edt { hookNarrator.onStarted(event.info) }
            is ClaudeEvent.HookProgress -> edt { hookNarrator.onProgress(event.info) }
            is ClaudeEvent.HookResponse -> edt { hookNarrator.onResponse(event.info) }
            // tool_progress → RUNNING (animated box) + elapsed time (the protocol carries no completion %).
            is ClaudeEvent.ToolProgress -> edt {
                transcript.setToolState(event.info.toolUseId, ToolState.RUNNING, event.info.elapsedTimeSeconds)
            }
            // tool_use_summary → a quiet dim note summarizing the preceding tool calls.
            is ClaudeEvent.ToolUseSummary -> edt {
                if (event.info.summary.isNotBlank()) transcript.add(Speaker.SYSTEM, "↳ ${event.info.summary}")
            }
            // mirror_error → the binary lost transcript data; warn the user (their session file may be incomplete).
            is ClaudeEvent.MirrorError -> {
                log.warn("mirror_error: ${event.info.error}")
                systemNotice("Warning: failed to persist part of the session transcript.")
            }

            is ClaudeEvent.ModelRefusalFallback -> {
                val i = event.info
                val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                val to = i.fallbackModel.takeIf { it.isNotBlank() }?.let { " → retried on $it" } ?: " → retried on a fallback model"
                systemNotice("The model declined to respond$cat$to.")
            }

            is ClaudeEvent.ModelRefusalNoFallback -> edt {
                // Refusal with no fallback configured → the turn ends in error. Surface it (the content is display
                // prose) so a refused turn never ends silently.
                val i = event.info
                val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                val msg = i.content.ifBlank { "The model declined to respond$cat and no fallback model was configured." }
                transcript.add(Speaker.ERROR, msg)
            }

            is ClaudeEvent.Informational -> {
                // Generic loop banner. Only surface the more prominent levels (suggestion/warning) plus any blocking
                // message; info/notice are already implied by the turn state and would just add noise.
                val i = event.info
                val text = i.content.trim()
                if (text.isNotEmpty() && (i.level == "warning" || i.level == "suggestion" || i.preventContinuation)) {
                    systemNotice(if (i.level == "warning") "Warning: $text" else text)
                }
            }

            is ClaudeEvent.WorkerShuttingDown -> {
                // Live-tail only: a resumed session may replay historical instances, so don't tear anything down —
                // just log it. (Reasons like host_exit/remote_control_disabled are host-set, not user input.)
                log.info("worker_shutting_down: ${event.info.reason}")
            }

            is ClaudeEvent.BackgroundTasksChanged -> edt {
                // LEVEL signal: swap the tracked set for the payload. Never paired with the task_* edge stream
                // (the SDK leaves their relative ordering unspecified), so it can't wedge a stale running indicator.
                taskTracker.replaceBackgroundTasks(event.info.tasks)
                fireState()
            }

            is ClaudeEvent.ControlRequestProgress -> {
                // Progress for one of OUR long-running control requests (currently only side_question, i.e. /btw).
                // `started` just means the worker accepted it — the transcript already shows the question. An
                // `api_retry` carries the same counters as system/api_retry, so surface it the same way.
                val i = event.info
                if (i.status == "api_retry") {
                    val of = (i.maxRetries ?: 0).takeIf { it > 0 }?.let { "/$it" } ?: ""
                    systemNotice("Retrying (attempt ${i.attempt ?: 1}$of)…")
                } else {
                    log.debug("control_request_progress: ${i.status} for ${i.requestId}")
                }
            }

            is ClaudeEvent.Other -> log.debug("Ignored ${event.type}/${event.subtype}")
        }
    }

    private fun onTerminated(gen: Int, exitCode: Int) {
        // Ignore a stale termination: if a newer start() has run (restart — e.g. toggling thinking/model), this
        // callback belongs to the old process and must NOT tear down the freshly-started session (which would
        // null `ready`, failAll the new initialize, and print "Session ended"). The current generation wins.
        if (gen != generation) return
        // Flush any buffered streaming deltas (reader thread) before tearing down so trailing text isn't dropped.
        flushDeltas()
        // The process is gone: release any in-flight control callbacks so their dialogs don't hang.
        controlClient.failAll("process gone")
        edt {
            turnActive = false
            interrupting = false
            ready = false
            liveThinkingTokens = 0
            promptSuggestion = null
            cards.clear()
            taskTracker.clear()
            hookNarrator.clear()
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

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun write(line: String) = process?.writeLine(line)

    /** Answers any pending elicitation cards with {action:"cancel"} (called during teardown, process still alive). */
    private fun cancelPendingElicitations() {
        runCatching {
            cards.all().filter { it.elicitation != null }.forEach {
                write(ControlProtocol.elicitationResult(it.requestId, "cancel"))
            }
        }
    }

    /**
     * Answers a `hook_callback` control_request: [HookBroker] (pure) parses the frame, decides, and builds the exact
     * `HookJSONOutput` reply; we write the control_response and apply the broker's IDE side effects on the EDT. The
     * binary blocks on this reply, so a malformed frame still gets an error response rather than hanging the turn.
     */
    private fun handleHookCallback(requestId: String, request: JsonObject) {
        val ctx = hookBroker.parse(request)
        if (ctx == null) {
            write(ControlProtocol.error(requestId, "Malformed hook_callback (missing input/hook_event_name)"))
            return
        }
        val decision = hookBroker.decide(ctx)
        write(ControlProtocol.success(requestId, hookBroker.buildResponse(ctx.callbackId, decision, ctx.hookEventName)))
        val effects = hookBroker.sideEffects(ctx, decision)
        if (effects.isEmpty()) return
        edt {
            for (effect in effects) when (effect) {
                is HookSideEffect.NotifyUser -> notifyInfo(effect.message)
                is HookSideEffect.RefreshFile -> { diffs.markForRefresh(effect.path); diffs.refreshTouched() }
                is HookSideEffect.TranscriptNote -> transcript.add(Speaker.SYSTEM, effect.text)
                is HookSideEffect.Marker -> log.debug("hook marker ${effect.event} ${effect.detail ?: ""}")
            }
        }
    }

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

    private fun notifyInfo(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Claude Code", content, NotificationType.INFORMATION)
            .notify(project)
    }

    /** Offer the sign-in once per auth-failure streak (see [loginPrompted]). EDT-confined. */
    private fun maybePromptLogin() {
        // Only the Anthropic provider uses OAuth login. On a third-party provider an auth failure means a
        // wrong/missing API key, not a missing login — don't offer the Anthropic sign-in there.
        if (ClaudeSettings.getInstance(project).provider != Provider.ANTHROPIC) return
        if (loginPrompted) return
        loginPrompted = true
        notifyLoginNeeded()
    }

    /** A warning notification whose action runs the native `claude login` flow. */
    private fun notifyLoginNeeded() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "You don't seem to be logged in. Sign in to Claude to continue.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimple("Sign in") { startLogin() })
            .notify(project)
    }

    /**
     * Runs the OAuth login **natively**: spawns `claude auth login` under a PTY ([ClaudeLoginFlow]) so the binary
     * can drive its interactive flow (the `--print` chat session can't host it). The binary opens the browser and
     * prints the authorize URL; the plugin opens the URL too (reliable inside the IDE), then collects the code the
     * callback page shows via a native input dialog and hands it back to the binary's stdin. On success the
     * session is restarted so it picks up the new credentials. Falls back to the IDE terminal if the PTY can't
     * start. Public so the composer can route a typed `/login` here.
     */
    fun startLogin() {
        val settings = ClaudeSettings.getInstance(project)
        // /login is the Anthropic OAuth flow — only meaningful for the official Anthropic provider. For a
        // third-party provider, auth is its own API key (configured in Settings), not an OAuth login.
        if (settings.provider != Provider.ANTHROPIC) {
            notifyInfo("Sign-in is only for the Anthropic provider. You're on ${settings.provider.label} — set its API key in Settings instead.")
            return
        }
        // Run `claude auth login` in the IDE terminal (instead of a modal code popup): the binary opens
        // the browser and captures the auth AUTOMATICALLY via its localhost callback when the browser can
        // reach it (no code to paste); if not, it prompts for the code in the terminal itself. Nicer and
        // more capable than scraping a PTY + asking for the code in a dialog.
        openLoginTerminal()
    }

    /** EDT-only. Asks for the authorization code and feeds it to the running [ClaudeLoginFlow] (or cancels it). */
    private fun promptForLoginCode(flow: ClaudeLoginFlow) {
        val urlHint = loginAuthUrl?.let { "\n\nIf the browser didn't open, visit:\n$it" }.orEmpty()
        val code = Messages.showInputDialog(
            project,
            "Approve access in your browser, then paste the authorization code here.$urlHint",
            "Sign in to Claude",
            null,
        )
        if (code.isNullOrBlank()) {
            flow.cancel()
            loginFlow = null
            notifyInfo("Login canceled.")
        } else {
            flow.submitCode(code.trim())
        }
    }

    /**
     * Opens an IDE terminal running `claude login` — kept only as the fallback for [startLogin] when a PTY can't
     * be allocated. Always uses the binary's absolute path so a GUI IDE that didn't inherit the user's login
     * `$PATH` still launches the right binary; falls back to a notice carrying the exact command if the IDE
     * Terminal plugin is unavailable.
     */
    private fun openLoginTerminal() {
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run { notifyMissingBinary(); return }
        val command = TerminalLauncher.loginCommand(binary.absolutePath)
        edt {
            if (TerminalLauncher.openAndRun(project, command, "claude login")) {
                // We can't observe the terminal's completion, so offer a one-click restart to pick up the
                // new auth once the user finishes signing in there.
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        "Claude Code",
                        "Finish signing in in the terminal — the browser opens automatically. When it confirms you're logged in, restart the chat to use it.",
                        NotificationType.INFORMATION,
                    )
                    .addAction(NotificationAction.createSimple("Restart chat") { restart() })
                    .notify(project)
            } else {
                notifyError("Couldn't open the IDE terminal. Run this in a terminal, then restart the chat:\n$command")
            }
        }
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
        // Abandon the current process generation (like stop() does) so the destroy() below — and any launch still
        // in flight — is treated as stale: its async onTerminated must NOT run the "exited unexpectedly" error
        // path / ERROR attention for a tab the user deliberately closed, and a mid-launch pooled block must not
        // publish an orphan process.
        generation++
        starting = false
        // Stop the shared quota-poll timer so the disposed session leaks no EDT timer.
        quotaPollTimer.stop()
        // Default-cancel any pending MCP elicitation cards while the process is still alive (mirrors stop()).
        cancelPendingElicitations()
        diffs.clearReviewDiffs()
        // EOF first (lets the binary exit cleanly) then destroy the tree — same order as stop().
        process?.closeStdin()
        process?.destroy()
        process = null
        // Release any in-flight control callbacks so nothing is left waiting after the tab is gone.
        controlClient.failAll("process gone")
    }

    /** Models for the GUI: exactly those the binary reported in `initialize` (no hand-maintained fallback list,
     *  which duplicated entries the binary already lists). Empty until the handshake lands; the Settings combo is
     *  editable so a custom id can still be typed before then. */
    fun modelOptions(): List<ModelInfo> = models

    companion object {
        const val NOTIFICATION_GROUP = "Claude Code"

        /** How long to wait for a reply to a host-initiated control request before failing it (watchdog). */
        const val CONTROL_TIMEOUT_SECONDS = 30L

        /** Interval (ms) of the session-scoped quota poll (get_session_cost + get_context_usage), shared by all
         *  ChatPanels observing this session — one timer per session, not one per tab. */
        const val QUOTA_POLL_MS = 60_000

        /** Default model on a fresh install: the binary's "default" alias (currently the recommended Opus tier). */
        const val DEFAULT_MODEL = "default"

        /** Sentinel "extended thinking on" value: adaptive thinking is on/off, so any positive budget means on. */
        const val THINKING_ON = 1

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
