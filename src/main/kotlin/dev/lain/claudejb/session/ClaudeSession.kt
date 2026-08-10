package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.permission.PermissionBroker
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.process.ClaudeBinaryLocator
import dev.lain.claudejb.process.ClaudeProcess
import dev.lain.claudejb.protocol.AccountInfo
import dev.lain.claudejb.protocol.AgentInfo
import dev.lain.claudejb.protocol.AuthStatusInfo
import dev.lain.claudejb.protocol.ClaudeEvent
import dev.lain.claudejb.protocol.ClaudeJson
import dev.lain.claudejb.protocol.ContextUsage
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.DialogResponder
import dev.lain.claudejb.protocol.ElicitationRequest
import dev.lain.claudejb.protocol.InitializeResponse
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.protocol.RateLimitInfo
import dev.lain.claudejb.protocol.SlashCommand
import dev.lain.claudejb.protocol.TaskProgressInfo
import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.protocol.isHiddenUsageWindow
import dev.lain.claudejb.protocol.parseElicitationFields
import dev.lain.claudejb.protocol.parseUsageReport
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable
import dev.lain.claudejb.ui.ReviewPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
    private val login = LoginCoordinator(
        project,
        edt = ::edt,
        notifyInfo = ::notifyInfo,
        notifyError = ::notifyError,
        notifyMissingBinary = ::notifyMissingBinary,
        restartSession = { restart() },
    )

    // --- session/runtime state (read by the GUI) ---
    @Volatile var sessionId: String? = null
        internal set

    @Volatile var model: String? = null
        private set

    @Volatile var effort: String? = null
        private set

    @Volatile var permissionMode: String = "default"
        private set

    @Volatile var thinkingTokens: Int? = null
        private set

    @Volatile var allowedTools: String = ""
        private set

    @Volatile var disallowedTools: String = ""
        private set

    @Volatile var settingSources: String = "user,project,local"
        private set

    /** Whether to wire JetBrains' own MCP server. Independent of [customMcpServers]. */
    @Volatile var ideMcpEnabled: Boolean = false
        private set

    /** JetBrains transport: "sse" / "streamable-http" (localhost at [ideMcpPort]) or "stdio" (synthesized from IDE paths). */
    @Volatile var ideMcpTransport: String = "sse"
        private set

    @Volatile var ideMcpPort: Int = DEFAULT_IDE_MCP_PORT
        private set

    /** User-defined extra MCP servers, as a JSON object with the same shape as `mcpServers` (name → server). */
    @Volatile var customMcpServers: String = ""
        private set

    @Volatile var includePartialMessages: Boolean = true
        private set

    // E6 advanced launch options (null/empty = flag omitted). Captured into the LaunchOptions snapshot per (re)start.
    @Volatile var maxTurns: Int? = null
        private set

    @Volatile var maxBudgetUsd: Double? = null
        private set

    @Volatile var fallbackModel: String? = null
        private set

    @Volatile var addDirs: List<String> = emptyList()
        private set

    @Volatile var betas: String? = null
        private set

    @Volatile var strictMcpConfig: Boolean = false
        private set

    @Volatile var outputStyle: String = "default"
        private set

    @Volatile var turnActive: Boolean = false
        private set

    /** True between an interrupt request and its ack/timeout/turn-end — drives the Stop button's "Interrupting…" label. */
    @Volatile var interrupting: Boolean = false
        private set

    /**
     * The most recent `rate_limit_event`, whichever window it described. Kept for the composer's quota pill,
     * which shows one number; [rateLimits] is the per-window view.
     */
    @Volatile var rateLimit: RateLimitInfo? = null
        private set

    /**
     * The latest event PER WINDOW, keyed by `rateLimitType` (`five_hour`, `seven_day`, `seven_day_opus`…).
     *
     * The single [rateLimit] field above cannot express this: the binary emits a separate event per window, so
     * consecutive events overwrote each other and the plugin could only ever display whichever arrived last.
     * A user on a weekly limit would see their five-hour bar and conclude they had room.
     */
    @Volatile var rateLimits: Map<String, RateLimitInfo> = emptyMap()
        private set

    // --- live state surfaced by the system/* events; read by the GUI / diagnostics / tests ---

    /** Authoritative turn state from session_state_changed (idle | running | requires_action), or null pre-first-event. */
    @Volatile var sessionState: String? = null
        private set

    /** Latest auth_status from the binary (re-auth in progress / output / error), or null when never reported. */
    @Volatile var authStatus: AuthStatusInfo? = null
        private set

    /** Live reasoning-token estimate from thinking_tokens (running total for the current thinking block). */
    @Volatile var liveThinkingTokens: Int = 0
        private set

    /** Predicted next user prompt (prompt_suggestion), or null when none / cleared. Drives the composer chip. */
    @Volatile var promptSuggestion: String? = null
        private set

    /** Observable map of subagent tasks keyed by task_id (task_started/progress/updated/notification). */
    val subagentTasks: Map<String, TaskProgressInfo> get() = taskTracker.tasks

    /**
     * The agents of this chat: their tree, their status and their own transcripts.
     *
     * Fed from the binary's own per-subagent files (see [AgentRegistry]) rather than from the event stream,
     * so a live agent and a restored one are the same thing read the same way. The scan is IO and runs off
     * the EDT ([scanAgents]); listeners hear about it through [SessionListener.onAgentsChanged].
     */
    // NB named `runningAgents`, not `agents`: `agents` is already the CATALOG of agent types the binary
    // offers in its initialize reply. Two different things — what you can spawn, and what is running.
    val runningAgents = AgentRegistry(subagentsDir = { sessionId?.let { SessionStore.subagentsDir(it) } })

    /** Guards against piling scans on top of each other while one is already walking the directory. */
    private val agentScanInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

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

    // [input, cacheCreation, cacheRead, output]; latest snapshot wins (matches TokenAccountant.onLiveUsage)
    private var pendingUsage: IntArray? = null

    /** Buffer a streaming delta (thinking or assistant text), coalescing same-type runs. [deltaLock]-guarded. */
    private fun bufferDelta(isThinking: Boolean, text: String) = synchronized(deltaLock) {
        val last = deltaRuns.lastOrNull()
        if (last != null && last.first == isThinking) {
            last.second.append(text)
        } else {
            deltaRuns.add(isThinking to StringBuilder(text))
        }
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
    var commands: List<SlashCommand> = emptyList()
        private set
    var models: List<ModelInfo> = emptyList()
        private set
    var agents: List<AgentInfo> = emptyList()
        private set
    var availableOutputStyles: List<String> = emptyList()
        private set
    var account: AccountInfo = AccountInfo()
        private set

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

    /**
     * One buffered prompt: the wire [text], its base64 [images] (mediaType→data), and the [displayText] shown
     * in the transcript/queue strip.
     */
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
    @Volatile var lastSessionCost: JsonObject? = null
        private set

    /** Latest `get_context_usage` result (or null until the first poll returns). Read by ChatPanel on the EDT. */
    @Volatile var lastContextUsage: ContextUsage? = null
        private set

    /** Working directory the binary runs in (the project root) — shown synchronously in the session dashboard. */
    val workingDir: String? get() = project.basePath

    /** Cached CLI binary version for the session dashboard; populated lazily by the panel via [requestBinaryVersion]. */
    @Volatile var binaryVersion: String? = null

    /** Client-generated id of the current user turn (tagged on each prompt) — the rewind_files() anchor. */
    @Volatile var currentUserMessageId: String? = null
        private set
    private val toolUseTurn = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The user-turn id an edit ([toolUseId]) belongs to, or null when unknown (then rewind isn't possible). */
    fun userMessageIdFor(toolUseId: String): String? = toolUseTurn[toolUseId]

    /** Whether file-checkpointing is enabled (native rewind requires it). */
    val checkpointingEnabled: Boolean get() = ClaudeSettings.getInstance(project).enableFileCheckpointing

    private val quotaPollTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollQuota() }.apply { isRepeats = true }

    /** Guards [pollQuota] against overlapping round-trips; see the comment there. EDT-confined. */
    private var quotaPollInFlight = false

    /**
     * True once the `initialize` handshake has answered — i.e. the binary is up AND talking, with commands,
     * models and the account in hand. The GUI treats THIS, not process liveness, as "loaded": a spawned
     * process that has not answered yet would hand the user a chat whose menus and dashboard are empty and
     * fill in afterwards. Reset on every launch and teardown.
     */
    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * Fire one session-cost + context-usage poll; results are cached and pushed to panels via [fireState].
     * No-op while the process is not running (the control requests would deliver null and clobber the cached
     * last-good values, blanking the usage meter); and even when running we only overwrite the cache on a
     * non-null result, so a transient null never blanks the panels — the last good values stay until a real one arrives.
     */
    private fun pollQuota() {
        if (!isRunning()) return
        // Never let polls overlap. The control channel is SHARED with `can_use_tool` and the tool-result
        // traffic, so at a one-second cadence a binary busy streaming answers slower than we ask, the
        // requests pile up, and everything queued behind them — tool cards finishing, permissions — waits
        // on two numbers. One poll in flight at a time; a slow answer skips a tick instead of stacking.
        if (quotaPollInFlight) return
        quotaPollInFlight = true
        var pending = 2
        // ONE state push per poll, not one per answer: a full push re-serializes meta + state + dashboard,
        // and doing it twice a second competed with the streaming transcript for no new information.
        val settle = {
            if (--pending == 0) {
                quotaPollInFlight = false
                fireState()
            }
        }
        requestSessionCost { cost ->
            if (cost != null) lastSessionCost = cost
            settle()
        }
        requestContextUsage { cu ->
            if (cu != null) lastContextUsage = cu
            settle()
        }
        // The timer exists to track a turn AS IT RUNS, nothing else. Context and cost cannot move while the
        // session sits idle, so polling forever was a round-trip through the binary for two numbers that
        // provably had not changed — and retiring at turn end is also what makes the 1-second cadence
        // affordable at all. The turn-start, turn-end and process-ready paths each poll directly, so nothing
        // waits on a clock.
        if (!turnActive) edt { quotaPollTimer.stop() }
    }

    /** Begin tracking a running turn: poll now, then keep the meters live until it ends. */
    private fun startQuotaPolling() = edt {
        pollQuota()
        if (!quotaPollTimer.isRunning) quotaPollTimer.start()
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
            // Credentials / private keys / credential-dumping commands: never auto-approved, whatever the mode.
            sensitiveDecision = { toolName, input ->
                ClaudeSettings.getInstance(project).sensitiveDecision(toolName, input, project.basePath)
            },
            onSensitiveDenied = { toolName, reason ->
                edt {
                    // The guard's own words, not a fixed sentence. The previous text asserted "MCP servers and
                    // Skills may not read credentials or private keys" for EVERY denial — so a block on a
                    // dangerous command, or on a path outside your own space, was described as a credential read,
                    // and a first-party tool was told it was an MCP server. Both were visible to users.
                    transcript.add(
                        Speaker.SYSTEM,
                        reason?.let { "Blocked $toolName: it $it." }
                            ?: "Blocked $toolName by the sensitive-data guard. See Settings ▸ Claude Code ▸ Security.",
                    )
                }
                fireState()
            },
        )
    }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
        // Fill this observer's meters NOW rather than starting a timer it would then have to wait out. A panel
        // attaching to an ALREADY-RUNNING session (a second tab, a reopened tool window) used to show empty
        // context and cost for up to a full poll interval for no reason: the data was one control request away
        // the whole time. `pollQuota` no-ops when the process is not up, and the ready path polls again then.
        edt { pollQuota() }
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
        // Stop the shared poll once no panel observes this session anymore (no leaked timer).
        edt { if (listeners.isEmpty() && quotaPollTimer.isRunning) quotaPollTimer.stop() }
    }

    fun isRunning(): Boolean = process?.isRunning() == true

    /**
     * True between [start] dispatching a launch and the process being up (or the launch failing).
     *
     * Exposed because "not running" alone is ambiguous to the UI: a session that is booting and one that never
     * started look identical through [isRunning], and the composer rendered both as "Idle" — which is a claim,
     * and a false one, during the seconds the launch takes (env resolution sources a login shell).
     */
    fun isStarting(): Boolean = starting

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
        val binary = resolveBinary(settings) ?: return false
        if (!passesLaunchGates(settings)) return false
        if (!hasCredential(settings)) {
            // Sign-in comes BEFORE the loading screen, not after it. Verifying auth needs no session, and
            // launching one we know is unauthenticated only buys a spawned process, a spinner, and a turn
            // that fails later for a reason the user already knew at click time.
            onLoginNeeded()
            return false
        }
        val workDir = project.basePath?.let(::File) ?: File(System.getProperty("user.home"))

        ready = false
        initialized = false
        starting = true
        reconciler.onMessageBoundary()
        // Tell the GUI we are booting BEFORE handing off to the pooled thread, so the loading screen is up for
        // the whole launch rather than appearing after the slow part (env resolution) has already finished.
        fireState()
        val launchGen = ++generation // this launch's generation; the process's onTerminated is gated on it

        // Off the EDT: env resolution sources a shell (seconds) and process spawn can block. Hand back to the EDT
        // for the state mutations the GUI observes (ready/fireState/pump) and the queue invariant.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launch(launchGen, settings, binary, workDir, resume)
            } finally {
                // Release the launch guard, but only if we still own the current generation — a newer start()
                // bumped it and is now the owner, so it must keep `starting` set.
                if (launchGen == generation) {
                    starting = false
                    // And tell the GUI, or a launch that FAILED leaves the loading screen up forever: `launch`
                    // only fires state on the paths that succeed. This runs on the pooled thread, hence edt {}.
                    edt { fireState() }
                }
            }
        }
        return true
    }

    /**
     * True when the last launch attempt found no `claude` binary anywhere. Drives the boot screen's
     * "Claude Code was not found" card (install buttons + manual path) instead of a spinner that clears
     * into an empty tab with only a toast to explain itself. Cleared the moment a resolve succeeds.
     */
    @Volatile
    var binaryMissing: Boolean = false
        private set

    /**
     * True when the binary looks unauthenticated — proactively (the `auth status` probe on process ready)
     * or reactively ([LoginDetection] on a failed turn / an auth-status error). Drives the sign-in card
     * (subscription OAuth or API key); cleared by the next clean turn, a positive probe, and
     * [dismissLoginCard]. The card and [LoginCoordinator]'s notification coexist on purpose: the
     * notification reaches a user whose chat tab is hidden, the card reaches the one staring at it.
     */
    @Volatile
    var needsLogin: Boolean = false
        private set

    private fun onLoginNeeded() {
        needsLogin = true
        edt { fireState() }
        login.maybePrompt()
    }

    /**
     * ONCE per session, on the first boot check: if the machine already has a plaintext
     * `~/.claude/.credentials.json` — a login the user made in their terminal, or an orphan from a hard IDE
     * kill — take it into the safe and delete it. That login then counts as ours and the tab starts signed
     * in instead of asking again.
     *
     * Once, and only here. Doing it on every poll deleted the file every few seconds, and `auth login`
     * finishes by writing exactly that file: the browser leg lost its credential the instant it earned it,
     * and the code-paste fallback became the only route that ever completed. A sign-in in flight writes its
     * own credential into the safe when it succeeds ([LoginCoordinator]) — the vault does not need to go
     * looking for it.
     */
    private fun absorbExistingLoginOnce() {
        if (startupHarvestDone) return
        startupHarvestDone = true
        // ORDER IS THE WHOLE POINT, and it is the same order the card's sign-in follows
        // ([LoginCoordinator.completeSignIn]): ask WHO first, take the credential second. Reversed, the
        // question can no longer be answered by anybody.
        captureAccountIdentityOnce()
        dev.lain.claudejb.process.CredentialsVault.harvest()
    }

    /**
     * Captures `claude auth status` — the whole JSON, into the IDE safe — while the binary's own credentials
     * file still exists, because the very next line takes that file away.
     *
     * This is why the dashboard's Email and Organization rows were empty. `auth status` names the account
     * (`email`, `orgId`, `orgName`) only when it authenticates from its OWN store. Handed our credential
     * through the environment it answers `authMethod: oauth_token` and no identity at all, and `system/init`
     * carries the same anonymous account object — which is exactly why Plan and Provider filled in while
     * those two rows stayed blank. Harvest the credential first and there is nothing left to ask: the file was
     * the only thing that could answer.
     *
     * A login made in the user's own terminal is the case this covers; a sign-in through the card is already
     * in the right order. [dev.lain.claudejb.process.AuthCli.status] does the filing, and only for a reply
     * that names the account, so this asks at most once per sign-in — with an answer banked there is nothing
     * to ask. Blocking (it spawns the binary); every caller of this is pooled-thread only.
     */
    private fun captureAccountIdentityOnce() {
        // Never from a test JVM. [dev.lain.claudejb.process.CredentialsVault.credentialsFile] resolves the
        // DEVELOPER's real home there, so this would probe on the strength of their own login and file the
        // answer in a throwaway safe — the same reason the vault refuses to touch a real home under test. It
        // also spawns the stand-in binary, which has no `auth status` to answer with.
        if (ApplicationManager.getApplication()?.isUnitTestMode != false) return
        if (dev.lain.claudejb.process.AuthCli.stored()?.email != null) return
        if (!dev.lain.claudejb.process.CredentialsVault.credentialsFile().isFile) return
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return
        // The RAW settings env: overlaying our own credential is precisely what makes the answer anonymous.
        dev.lain.claudejb.process.AuthCli.status(binary, settings.resolveEnv())
    }

    @Volatile
    private var startupHarvestDone = false

    /**
     * Whether the live process was launched with `--resume`. Read in [onTerminated]: a resumed launch that
     * dies before the handshake is a conversation the binary cannot find, which is a recoverable condition
     * (drop the id, open a fresh one) and not the generic "it exited" failure.
     */
    @Volatile
    private var resumedLaunch = false

    /**
     * Whether this session has an identity to run as — checked BEFORE spawning anything, since that is a
     * question about what we hold, not about what the binary can do.
     *
     * The identity is exclusively: the vaulted subscription login, an API key in its provider slot, or a
     * credential the user wrote by hand into the Settings environment. Nothing held → logged out by
     * definition, and no process is started to re-ask a question we have already answered.
     *
     * A vaulted login whose access token has expired but whose refresh token has not counts as an identity —
     * it is one renewal away from live, and [renewVaultedCredential] performs that renewal off the EDT at
     * launch time. Answering "signed out" here instead is what made every reboot end at the sign-in card.
     *
     * Deliberately does NOT harvest — see [absorbExistingLoginOnce] — and deliberately does not RENEW either:
     * this runs on the EDT from [start], and renewal spawns a process.
     */
    private fun hasCredential(settings: ClaudeSettings): Boolean {
        if (dev.lain.claudejb.process.CredentialsVault.hasUsableToken()) return true
        if (dev.lain.claudejb.process.CredentialsVault.canRenew()) return true
        if (SecretStore.get(SecretStore.OAUTH_TOKEN) != null) return true
        if (settings.getProviderApiKey(settings.provider).isNotBlank()) return true
        val explicit = settings.resolveEnv()
        if (SecretStore.API_KEY in explicit || SecretStore.OAUTH_TOKEN in explicit) return true
        // An explicit Log out outranks the binary's own login: otherwise clearing our safe changes nothing
        // the user can see, because the binary still holds one and the session starts straight back up.
        if (settings.state.signedOut) return false
        return binaryHoldsOwnLogin(settings)
    }

    /**
     * Last resort: does the BINARY hold a login of its own?
     *
     * This is what makes the plugin work off Linux. The vault only ever engages when there is a plaintext
     * `~/.claude/.credentials.json` to take custody of — which is the Linux situation. On macOS the binary
     * keeps its credentials in the **Keychain** and writes no such file, so a vault-only view of the world
     * concludes "signed out" no matter how many times the user signs in: the login card would reappear
     * immediately after every successful sign-in, forever. Windows behaves the same wherever the binary uses
     * a store rather than a file.
     *
     * So when we hold nothing, we ask instead of assuming. A binary with its own valid login is simply left
     * to use it: no vault, no config dir, no environment token — and, because it authenticates from its own
     * store, the dashboard gets the complete account and plan picture there too.
     *
     * Throttled hard ([OWN_LOGIN_TTL_MS]): this spawns a process, and the caller polls every few seconds.
     */
    private fun binaryHoldsOwnLogin(settings: ClaudeSettings): Boolean {
        val now = System.currentTimeMillis()
        ownLoginCheckedAt.takeIf { now - it < OWN_LOGIN_TTL_MS }?.let { return binaryOwnLogin }
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return false
        // The RAW settings env, deliberately: overlaying our own credentials would be asking the binary
        // whether IT is signed in while handing it ours.
        val status = dev.lain.claudejb.process.AuthCli.status(binary, settings.resolveEnv())
        binaryOwnLogin = status?.loggedIn == true
        ownLoginCheckedAt = now
        return binaryOwnLogin
    }

    @Volatile private var binaryOwnLogin = false

    @Volatile private var ownLoginCheckedAt = 0L

    /**
     * Brings the vaulted subscription login back to life when its access token has expired, BEFORE the launch
     * env is built. Blocking (process + network) — pooled thread only, which is why it lives in [launch] and
     * not in [start].
     *
     * This is what makes a login survive a reboot. The access token the OAuth flow issues is good for hours;
     * the refresh token beside it in the safe is good for weeks and is rotated at every renewal. Without this
     * step the plugin held a perfectly persisted credential and still asked the user to sign in every
     * morning — the credential had not been lost, it had merely expired with nothing allowed to spend it.
     *
     * @return whether this launch has an identity to run as. A renewal that fails does not condemn the
     *   launch: the ttl cache is dropped first so the fallback question ("does the BINARY hold its own
     *   login?") is asked again — a renewal can sign the binary in even when we fail to take custody of what
     *   it wrote, which is the normal case wherever it uses an OS store instead of a file.
     */
    private fun renewVaultedCredential(binary: File, settings: ClaudeSettings): Boolean {
        // A sign-in owns `~/.claude/.credentials.json` from the browser leg until it is banked; renewing
        // underneath it would take away the very file the flow is about to write.
        if (login.inProgress) return true
        if (!dev.lain.claudejb.process.CredentialsVault.needsRenewal()) return true
        if (dev.lain.claudejb.process.CredentialsVault.renew(binary, settings.resolveEnv())) {
            dev.lain.claudejb.process.AccountProfile.invalidate()
            return true
        }
        ownLoginCheckedAt = 0
        return hasCredential(settings)
    }

    /**
     * Re-evaluates which screen this tab should be showing, from scratch. Called periodically while no
     * session is running — BLOCKING (it stats the filesystem and reads the PasswordSafe), so pooled thread
     * only; the state changes hop to the EDT themselves.
     *
     * Detection used to happen exactly once, inside [start]. So a tab that opened before Claude Code was
     * installed kept its stale answer forever: installing the binary, or signing in from somewhere else,
     * changed nothing until the tab was closed and reopened. The three states are a function of the world
     * (binary present? credential held?) and the world changes underneath us, so they are re-derived rather
     * than remembered.
     */
    fun refreshBootState() {
        if (starting) return
        // A sign-in is mid-flight: keep out. This poll harvests the credentials file, and `auth login`
        // writes exactly that file to finish — taking it away mid-flow breaks the browser leg.
        if (login.inProgress) return
        absorbExistingLoginOnce()
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath)
        if ((binary == null) != binaryMissing) {
            binaryMissing = binary == null
            edt { fireState() }
        }
        if (binary == null) {
            // The binary went away under a live session — uninstalled, or a path that no longer resolves.
            // Stop it before showing the install screen, or the user reads "not installed" while a process
            // from the vanished copy is still answering.
            edt { if (isRunning()) stop() }
            return
        }
        // Persist a freshly-installed binary's path here too, not only in resolveBinary: the install card's
        // "it appeared" path went through a start() that could return before ever writing it down.
        if (settings.claudePath != binary.absolutePath) settings.state.claudePath = binary.absolutePath
        val credentialed = hasCredential(settings)
        edt {
            if (starting) return@edt
            when {
                // Signed out: an expired token, a Log out, a credential cleared elsewhere. STOP FIRST — a
                // live process still holds the old identity, so leaving it up means the tab says "signed
                // out" while the next turn happily works.
                !credentialed -> {
                    if (isRunning()) stop()
                    // Already asked; do not re-fire, or the notification would repeat every few seconds.
                    if (!needsLogin) onLoginNeeded()
                }

                !isRunning() -> start()
            }
        }
    }

    /** The card's "I'm already signed in": hide it until the next auth failure says otherwise. */
    fun dismissLoginCard() {
        needsLogin = false
        edt { fireState() }
    }

    /**
     * Proactive auth check, off-EDT: `claude auth status --json` with the full launch env — so the answer
     * covers every identity the session can actually run on, in the order the binary itself resolves them:
     * an env credential (the PasswordSafe overlay / explicit Settings vars) first, its own credential store
     * (the full-consent `auth login`, shared with the terminal CLI) second. Not logged in by ANY of those →
     * the sign-in card is the first thing the tab shows, before a turn can fail on it.
     *
     * A probe that cannot run or parse yields a SYNTHETIC logged-out state rather than silence: the account
     * card's button must always exist and say something ("Sign in" that leads to an idempotent login beats
     * a button that omits itself and cannot be found).
     */
    fun probeAuthStatus() {
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val onOurEnv = dev.lain.claudejb.process.AuthCli.status(binary, effectiveLaunchEnv())
                ?: dev.lain.claudejb.process.AuthCli.AuthState(loggedIn = false)
            // WHO the account is takes a second question, and this is why the dashboard's Email and
            // Organization rows were empty: asked with our credential in its environment the binary reports
            // `authMethod: oauth_token` and no identity at all. Asked with the RAW settings env it answers
            // from its own store as `claude.ai` — email, orgId, orgName, plan — which AuthCli.status files in
            // the safe. `loggedIn` stays the first answer's: that one describes the identity this session
            // actually runs on. Skipped entirely once the first answer already named the account.
            // Only worth a second question when somebody IS signed in: an anonymous logged-OUT answer has no
            // identity to go looking for, and asking anyway spawns a second process per probe for nothing.
            val status = if (!onOurEnv.loggedIn || onOurEnv.email != null || onOurEnv.orgName != null) {
                onOurEnv
            } else {
                val identity = dev.lain.claudejb.process.AuthCli.status(binary, settings.resolveEnv())
                    ?.takeIf { it.email != null || it.orgName != null }
                    ?: dev.lain.claudejb.process.AuthCli.stored()
                onOurEnv.copy(
                    email = identity?.email,
                    orgId = identity?.orgId,
                    orgName = identity?.orgName,
                    apiProvider = onOurEnv.apiProvider ?: identity?.apiProvider,
                    subscriptionType = onOurEnv.subscriptionType ?: identity?.subscriptionType,
                )
            }
            authCliStatus = status
            if (!status.loggedIn) {
                onLoginNeeded()
            } else if (needsLogin) {
                needsLogin = false
                edt { fireState() }
            } else {
                edt { fireState() } // account card enrichment (email/plan) still wants a push
            }
        }
    }

    /** Last `auth status` probe result — feeds the dashboard's account card (email, plan, Sign in/Log out). */
    @Volatile
    var authCliStatus: dev.lain.claudejb.process.AuthCli.AuthState? = null
        private set

    /** Locates the binary and persists the resolved path; null (after notifying) when there is none. */
    private fun resolveBinary(settings: ClaudeSettings): File? {
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            binaryMissing = true
            // The state push is what flips the boot screen into the not-found card; without it the flag
            // sits unread until some unrelated event happens to re-push.
            fireState()
            notifyMissingBinary()
            return null
        }
        binaryMissing = false
        // Persist the auto-detected path so later launches are stable and the user can see/edit it
        // (also refreshes a stale saved path that fell back to auto-detection).
        if (settings.claudePath != binary.absolutePath) settings.state.claudePath = binary.absolutePath
        return binary
    }

    /**
     * The two security gates that can refuse a launch outright. Both run on the EDT (start()'s contract).
     *
     * Trust-on-open: a project-level claude-code.xml can ship a sourceScript or a stdio MCP server (arbitrary
     * command), both of which we'd execute at launch. If that config is present and the project hasn't been
     * trusted for it, ask once before running anything. Declining aborts the launch rather than silently
     * executing code that arrived with an untrusted repo.
     *
     * Network-share: refuse to root an autonomous agent — shell, IDE reach, coding/offensive ability — on a
     * remote / network / foreign mount. That is a lateral-movement launchpad, not a project. No override:
     * whoever needs the unrestricted tool has `claude` on the CLI (and, there, Anthropic's own controls). The
     * deliberate friction — you cannot casually relocate a 90 GB network dir to local disk — is the point.
     */
    private fun passesLaunchGates(settings: ClaudeSettings): Boolean {
        if (!ensureExecTrust(settings)) return false
        if (RemoteMounts.isRemote(project.basePath)) {
            refuseRemoteProject(project.basePath)
            return false
        }
        return true
    }

    /**
     * The env the `claude` process is launched with: the settings-resolved base plus the credentials held
     * in the IDE's PasswordSafe ([SecretStore]) — overlaid ONLY where the explicit env doesn't already carry
     * the name, so a hand-written Settings value keeps winning. Credentials travel exclusively through the
     * environment (never argv — /proc/<pid>/cmdline is world-readable, environ is 0400), and through here
     * for every consumer, so the auth probe sees exactly what the session process will.
     */
    internal fun effectiveLaunchEnv(base: Map<String, String>? = null): Map<String, String> {
        val env = base ?: ClaudeSettings.getInstance(project).resolveEnv()
        val settings = ClaudeSettings.getInstance(project)
        // The Anthropic API key, from its own provider slot. Only when the selected provider IS Anthropic:
        // under a third-party provider `resolveEnv` has already put THAT provider's key in, and overwriting
        // it here would send an Anthropic credential to a non-Anthropic endpoint.
        val apiKey = settings.anthropicApiKey
            .takeIf { it.isNotBlank() && settings.provider == Provider.ANTHROPIC && SecretStore.API_KEY !in env }
        val withSecrets = env +
            SecretStore.envOverlay(env.keys) +
            (apiKey?.let { mapOf(SecretStore.API_KEY to it) } ?: emptyMap())
        // The binary runs against its OWN `~/.claude`, untouched. The vaulted subscription login rides here
        // instead — the WHOLE credential, field by field (token, refresh token, scopes, subscription, rate
        // tier, account), which is what lets the session run with NOTHING in ~/.claude/.credentials.json and
        // still report the plan limits. See CredentialsVault.envOverlay: the missing piece was the SCOPES,
        // not a config directory. Last, and keyed on the merged env, so an explicit API key or token wins.
        return withSecrets + dev.lain.claudejb.process.CredentialsVault.envOverlay(withSecrets.keys)
    }

    /**
     * Spawns the process off the EDT and, once it is up, hands back to the EDT to flip [ready].
     *
     * [launchGen] is re-checked at every step that follows a blocking call: a stop()/dispose()/newer start() can
     * race in while the env resolves or the process spawns, and a stale launch must NOT leave an orphan process
     * behind nor mark a session that no longer owns the generation as ready.
     */
    private fun launch(launchGen: Int, settings: ClaudeSettings, binary: File, workDir: File, resume: Boolean) {
        // No harvest here: absorbing an existing plaintext login is a ONCE-per-session act
        // ([absorbExistingLoginOnce]), and a sign-in files its own credential when it succeeds.
        //
        // The credential reaches the binary through the environment, WHOLE (CredentialsVault.envOverlay), and
        // the binary keeps its own `~/.claude`. Nothing is relocated, symlinked or deleted.
        //
        // Renewal FIRST, and here rather than in start(): it spawns a process, start() runs on the EDT, and
        // the env below has to be built from the credential we are about to hold — not the expired one.
        if (!renewVaultedCredential(binary, settings)) {
            edt { onLoginNeeded() }
            return
        }
        val env = effectiveLaunchEnv(cachedEnv ?: settings.resolveEnv().also { cachedEnv = it })
        // A stop()/dispose()/newer start() may have raced in during the (slow) env resolution. If so, this
        // launch is stale — don't spawn an orphan process nothing will ever tear down.
        if (launchGen != generation) return
        resumedLaunch = resume
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
            return
        }
        // If a teardown raced in between the gen-check and now, destroy the freshly-spawned orphan.
        if (launchGen != generation) {
            proc.destroy()
            if (process === proc) process = null
            return
        }
        requestInitialize()
        // NOTE (claude 2.1.150): the binary accepts prompts on stdin from the start and only emits the
        // `system/init` line *after* the first user turn — not on launch. So we must NOT gate readiness on
        // the Init event (that would deadlock: pump() waits for ready, ready waits for a prompt). We're
        // ready as soon as the process is up; Init, when it later arrives, just back-fills sessionId/model.
        edt {
            ready = true
            transcript.add(Speaker.SYSTEM, "Claude Code ready.")
            // Auth check HERE, at launch-time readiness — NOT (only) on the Init event, because per the
            // note above `system/init` doesn't arrive until after the first user turn. Hooked there alone,
            // the sign-in card waited for the user to type a prompt before appearing, which is exactly
            // backwards: with no login the card must be the first thing the tab shows.
            probeAuthStatus()
            fireState()
            // Fill the context and cost meters NOW rather than on the poll timer's first tick.
            //
            // The timer's initial delay equals its interval (a javax.swing.Timer default), so the first poll is
            // a full QUOTA_POLL_MS — one minute — after the panel registered. Worse, that registration happens
            // while the binary is still launching, so `pollQuota` returns early on the not-running guard and the
            // meters stay empty for a SECOND interval. The data is available the moment the process is up; there
            // is no reason to make the user look at an empty readout while we wait for a clock.
            pollQuota()
            pump()
        }
    }

    /** Optional handshake → rich command/model/agent metadata for the GUI menus. */
    private fun requestInitialize() {
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
                log.debug(
                    "CC-TRACE initialize reply: account(email=${info.account.email.isNotBlank()}," +
                        " org=${info.account.organization.isNotBlank()}, plan='${info.account.subscriptionType}'," +
                        " provider='${info.account.apiProvider}') models=${info.models.size}" +
                        " commands=${info.commands.size} agents=${info.agents.size}",
                )
                // The handshake answered: the process is not merely spawned, it is DELIVERING. This is what
                // takes the loading screen down, so the chat's first frame is drawn with the command list,
                // the model catalog and the account already in hand rather than filling in behind it.
                initialized = true
                if (info.outputStyle.isNotBlank()) outputStyle = info.outputStyle
                // Graceful fallback: the pin ([DEFAULT_MODEL]) was chosen before the catalog was known. If this
                // binary doesn't actually offer it, re-resolve against the real catalog and push the correction
                // so we never sit on a model it can't honour. Only touches the pin, never an explicit choice.
                val pinMissing = info.models.isNotEmpty() && info.models.none { it.value == DEFAULT_MODEL }
                if (model == DEFAULT_MODEL && pinMissing) changeModel(preferredDefault(info.models))
                edt { fireMetadata() }
            },
        )
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
        initialized = false
        starting = false // any in-flight launch is now stale (generation bumped above); let a restart proceed
        // Reset per-turn live state so a stale figure/chip doesn't linger into a resumed session (restart path).
        liveThinkingTokens = 0
        promptSuggestion = null
        // Drop the cached env so a settings change to the source script is re-sourced on the next start.
        cachedEnv = null
        controlClient.failAll("process gone")
        taskTracker.clear()
        hookNarrator.clear()
        edt {
            cards.clear()
            diffs.clearReviewDiffs()
            fireState()
        }
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
            nonImage.forEach { add(displayMention(it)) }
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
     *  reuse their prompt text (a selection's fenced snippet, an image marker).
     *
     *  Unlike [wireMention] this does NOT relativise against the project root, and that asymmetry is deliberate:
     *  the model is sent a repo-relative path (portable, and what it should reason about), while the link needs
     *  an ABSOLUTE one to open the file. The visible text is the display name either way, so nothing longer than
     *  a filename is shown. It used to take an unused `root` purely to mirror [wireMention]'s signature. */
    private fun displayMention(a: Attachment): String = when (a) {
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
                startQuotaPolling()
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
            startQuotaPolling()
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
        // An interrupted turn still consumed context and cost, and it is also a turn END — so this both
        // refreshes the meters and lets the poll retire, exactly as a normal result does.
        pollQuota()
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
                    filePath = dto.filePath,
                    // Without this a restored command card fell back to the pre-4.3.2 plain-text rendering:
                    // no code block, nothing to copy. Restore must produce the SAME row a live turn does.
                    commandText = dto.commandText,
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
        if (allow) approvePermission(requestId, request, overrideInput) else rejectPermission(requestId, request, denyMessage)
        firePermissions()
    }

    private fun approvePermission(requestId: String, request: PendingPermission, overrideInput: JsonObject?) {
        if (request.reviewable) {
            // Snapshot/refresh stay on the ORIGINAL input: they describe the real file (before-text + path),
            // independent of any narrowed payload (e.g. an edited review diff) we actually send.
            DiffPresenter.filePathOf(request.input)?.let { diffs.markForRefresh(it) }
            // Snapshot before answering allow (the binary writes right after), so "View diff" works from the
            // transcript once the transient approval diff has closed. Synchronous read — small project files.
            request.toolUseId?.let { diffs.captureForReview(request.toolName, request.input, it) }
        }
        val effectiveInput = overrideInput ?: reviewEditOverride(requestId, request) ?: request.input
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
    }

    /**
     * If an editable review diff was open and the user TWEAKED the proposed content, re-encodes the tool input
     * so the binary writes THEIR version (file_path preserved). Also closes the diff.
     *
     * Fail-safe by construction: no edit (or a read-only viewer) yields null, and the binary then writes its
     * own version — an unreadable document can never turn into a wrong write.
     */
    private fun reviewEditOverride(requestId: String, request: PendingPermission): JsonObject? =
        diffs.takeReviewEdit(requestId)?.let { (currentText, editedText) ->
            dev.lain.claudejb.diff.HunkSelection
                .encodeInput(request.toolName, request.input, currentText, editedText)
        }

    private fun rejectPermission(requestId: String, request: PendingPermission, denyMessage: String?) {
        diffs.closeReviewDiff(requestId) // reject → discard the review diff tab
        val message = denyMessage ?: "User rejected the ${request.toolName} request."
        write(ControlProtocol.permissionDeny(requestId, message))
        systemNotice("Rejected ${request.headline}")
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
            ),
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
        // "default" is no longer a selectable model (the UI pins a concrete tier). Map any legacy/persisted
        // "default" to the preferred concrete model so both the display and what's sent to the binary agree;
        // null stays null (unset — the Init handler fills it from the binary's reported model).
        val resolved = if (value == RECOMMENDED_ALIAS) preferredDefaultModel() else value
        val previous = model
        model = resolved
        if (isRunning()) {
            // Correlated, not fire-and-forget, because "Other models" can offer a model this ACCOUNT cannot
            // run (the list is curated from ids the binary knows — it cannot know what the plan grants). A
            // refusal that only changed the pill would leave the tab pointed at a model every later turn
            // fails on, with nothing saying why.
            controlClient.send({ id -> ControlProtocol.setModelRequest(id, resolved) }) { res ->
                if (!res.success) edt { revertModel(previous, resolved, res.error) }
            }
        }
        fireState()
    }

    /**
     * Puts the previously selected model back after the binary refused a change, and says so in the transcript.
     *
     * EDT-only (it writes session state the UI reads). Silent when a newer selection has raced ahead of this
     * reply — reverting then would undo a choice the user made after the failure.
     *
     * Scope, stated because it is narrower than it looks: this catches a refusal of the `set_model` control
     * request itself. A binary that ACCEPTS the id and only fails later, when the turn reaches the API, is a
     * different signal and arrives as an ordinary turn error.
     */
    private fun revertModel(previous: String?, attempted: String?, error: String?) {
        if (model != attempted) return
        model = previous
        // Labelled from the curated list, NOT from the UI layer: naming a model is not a rendering decision,
        // and reaching into `ui.jcef` from here would invert the dependency this package deliberately keeps.
        val name = attempted?.let { LegacyModels.labelFor(it) ?: it } ?: "That model"
        val kept = previous?.let { LegacyModels.labelFor(it) ?: it } ?: "the previous model"
        val reason = error?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        transcript.add(Speaker.SYSTEM, "$name is not available on this account$reason — kept $kept.")
        fireState()
    }

    fun changePermissionMode(mode: String) {
        permissionMode = mode
        // Persist so new tabs / restarts launch in this mode instead of falling back to "default".
        ClaudeSettings.getInstance(project).getState().permissionMode = mode
        if (isRunning()) {
            val wire = SessionLauncher.binaryPermissionMode(permissionMode)
            write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), wire))
        }
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
            .addAction(
                NotificationAction.createSimple("Configure…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
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
            val state = if (tokens != null) "on" else "off"
            systemNotice("Extended thinking $state — restarting session.")
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
        this.ideMcpPort = ideMcpPort.takeIf { it in VALID_PORTS } ?: DEFAULT_IDE_MCP_PORT
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
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        controlClient.query(
            buildRequest = ControlProtocol::getContextUsageRequest,
            onResult = { mapped: ContextUsage? -> edt { onResult(mapped) } },
            decode = { payload ->
                payload?.let {
                    runCatching { ClaudeJson.decodeFromJsonElement(ContextUsage.serializer(), it) }.getOrNull()
                }
            },
        )
    }

    /**
     * Asks the binary for the FULL usage picture — every rate-limit window plus the extra-credit balance.
     *
     * Preferred over [rateLimits] as the dashboard's source of truth: one round-trip returns every window at
     * once, whereas the event stream only tells you about a window when it happens to move. The events remain
     * the live nudge that something changed and it is worth re-asking.
     */
    fun requestUsage(onResult: (UsageReport?) -> Unit) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        controlClient.query(
            buildRequest = ControlProtocol::getUsageRequest,
            onResult = { report: UsageReport? ->
                edt {
                    report?.windows?.forEach { (key, w) ->
                        // Logged at INFO, and not as noise: when this fired a false "quota at 100%" there was
                        // nothing in idea.log to check it against, because only the EVENT path was traced.
                        // A wrong number the user can see must leave the raw value behind that produced it.
                        w.utilizationPercent()?.let { pct ->
                            log.info("usage window $key: utilization=${w.utilization} -> $pct%")
                            warnOnQuotaCrossing(key, w.title(key), pct)
                        }
                    }
                    onResult(report)
                }
            },
            decode = { payload ->
                logUsageReply(payload)
                parseUsageReport(payload)
            },
        )
    }

    /**
     * Logs what the `get_usage` poll actually came back with, once per poll, at INFO.
     *
     * The derived per-window lines below cannot answer the question that keeps coming up — *is the figure on
     * screen stale, or is the server really still saying that?* — because a window the reply omits leaves no
     * line at all, and a carried-forward one is indistinguishable from a fresh one. This prints the wire:
     * `rate_limits` verbatim, truncated. It is what told us the binary was not caching and that the replies
     * during a two-hour exhausted window were complete rather than the header-seeded fallback.
     *
     * One line every 30 s is the deliberate cost. It is bounded (the payload is a handful of windows) and the
     * alternative is a user reporting a wrong number with nothing in `idea.log` to check it against.
     */
    private fun logUsageReply(payload: JsonObject?) {
        val limits = payload?.get("rate_limits")
        if (limits == null || limits is JsonNull) {
            // NOT the same as an empty object: the binary sends null when plan limits do not apply at all
            // (API key, Bedrock, Vertex) or when its own fetch had nothing to fall back on.
            log.info("get_usage: rate_limits=null (available=${payload?.get("rate_limits_available")})")
            return
        }
        log.info("get_usage: ${limits.toString().take(USAGE_LOG_CHARS)}")
    }

    /**
     * Announces the first time a quota window crosses 65% and again at 85%.
     *
     * Announced ONCE per threshold per window, and only on the way UP: this is checked on every usage refresh,
     * and a warning that repeats every thirty seconds is one the user learns to ignore — which costs exactly
     * the warning that mattered. The record is cleared when the figure falls back below a threshold, so the
     * next billing window warns again.
     *
     * 85% also raises an IDE notification, not just a transcript row. By then the user may be watching the
     * editor rather than the chat, and the point of the second threshold is that the wall is close enough to
     * change what they do next.
     *
     * [window] is the record's key and [label] what the user reads: they diverge for the per-model windows,
     * whose key is synthesised (`model_scoped:Fable`) precisely because the server names them and nothing
     * else does. Titling from the key would announce that synthetic string verbatim.
     */
    private fun warnOnQuotaCrossing(window: String, label: String, pct: Int) {
        val announced = quotaWarned[window] ?: 0
        val crossed = QUOTA_THRESHOLDS.lastOrNull { pct >= it } ?: 0
        if (crossed <= announced) {
            // Dropped below a threshold (the window reset, or the API revised it down): re-arm.
            if (crossed < announced) quotaWarned[window] = crossed
            return
        }
        quotaWarned[window] = crossed
        val message = "$label quota at $pct%."
        systemNotice(message)
        if (crossed >= QUOTA_THRESHOLD_HIGH) notifyInfo(message)
    }

    /** Window → the highest threshold already announced for it. EDT-confined (written from the usage callback). */
    private val quotaWarned = HashMap<String, Int>()

    fun requestSessionCost(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        controlClient.query(ControlProtocol::getSessionCostRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    fun requestMcpStatus(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        controlClient.query(ControlProtocol::mcpStatusRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    /** Effective merged settings + per-source breakdown (E2-UI diagnostics dialog). */
    fun requestSettings(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
        controlClient.query(ControlProtocol::getSettingsRequest, { mapped: JsonObject? -> edt { onResult(mapped) } }, { it })
    }

    /** The responder's CLI binary version (E2-UI diagnostics dialog). */
    fun requestBinaryVersion(onResult: (JsonObject?) -> Unit) {
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
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
        if (!isRunning()) {
            edt { onResult(null) }
            return
        }
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
        if (ok) {
            notifyInfo("Reverted $name to its state before this edit.")
        } else {
            notifyError("Couldn't revert $name (the file may be outside the project, missing, or locked).")
        }
        return ok
    }

    /** Rolls every edited file back to its oldest captured state. Returns the number reverted. EDT-only. Surfaces
     *  a summary notification. */
    fun revertAllEdits(): Int {
        val n = rollback.revertAllEdits()
        if (n > 0) {
            notifyInfo("Rolled back $n file${if (n == 1) "" else "s"} to the state before Claude's edits.")
        } else {
            notifyError("No files were rolled back (nothing reverted).")
        }
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

    /**
     * The binary→host event dispatch, in TWO levels: pick the group, then the variant inside it.
     *
     * It used to be one `when` with 47 arms — 244 lines, cyclomatic complexity 111 — which meant every protocol
     * concern in the plugin met in a single function. The groups are declared on [ClaudeEvent] itself (see the
     * sub-interfaces there), so BOTH levels stay exhaustive: adding a protocol event without handling it is a
     * compile error, not a frame that is silently dropped. That property is the whole point of `checkDrift`, so
     * it was not up for trade against a complexity threshold.
     */
    private fun onEvent(event: ClaudeEvent) {
        // Coalesce streaming deltas: buffer consecutive text/thinking deltas and the live-usage fold (reader
        // thread, no edt{}), and flush them in a single edt{} on the next non-delta event below. Order is
        // preserved because invokeLater is FIFO and the flush is submitted before the event's own edt{}.
        if (event is ClaudeEvent.Stream) {
            bufferStream(event)
            return
        }
        // Any other event: flush the buffered deltas first so they land on the EDT ahead of this event's work.
        flushDeltas()
        when (event) {
            is ClaudeEvent.Conversation -> onConversation(event)

            is ClaudeEvent.Control -> onControl(event)

            is ClaudeEvent.Task -> onTask(event)

            is ClaudeEvent.SessionSignal -> onSessionSignal(event)

            is ClaudeEvent.HookTelemetry -> onHookTelemetry(event)

            is ClaudeEvent.Notice -> onNotice(event)

            // Returned above; the branch exists only because the compiler checks this `when` for exhaustiveness,
            // which is exactly the property we want it to keep checking.
            is ClaudeEvent.Stream -> {}
        }
    }

    /** Buffers a streaming delta on the reader thread; [flushDeltas] lands them on the EDT in one batch. */
    private fun bufferStream(event: ClaudeEvent.Stream) = when (event) {
        is ClaudeEvent.TextDelta -> bufferDelta(isThinking = false, text = event.text)

        is ClaudeEvent.ThinkingDelta -> bufferDelta(isThinking = true, text = event.text)

        is ClaudeEvent.LiveUsage ->
            bufferUsage(event.inputTokens, event.cacheCreationTokens, event.cacheReadTokens, event.outputTokens)
    }

    /** The conversation proper: session start, assistant output, tool calls, end of turn. */
    private fun onConversation(event: ClaudeEvent.Conversation) {
        when (event) {
            is ClaudeEvent.Init -> onInit(event)

            is ClaudeEvent.ToolUse -> onToolUse(event)

            is ClaudeEvent.ToolResult -> onToolResult(event)

            is ClaudeEvent.Result -> onTurnResult(event)

            is ClaudeEvent.AssistantThinking -> edt { reconciler.finalizeThinking(event.text) }

            is ClaudeEvent.MessageStart -> edt {
                // A turn can emit several assistant messages (e.g. around tool calls). message_delta usage
                // restarts near 0 per message, so fold the finished message's tokens into the session total
                // before the next one overwrites the live counter — otherwise only the last message counts.
                tokens.foldIntoSession()
                liveThinkingTokens = 0 // the live reasoning estimate is per thinking block; reset at each boundary
                reconciler.onMessageBoundary()
            }

            is ClaudeEvent.LocalCommandOutput -> edt {
                if (event.content.isNotBlank()) transcript.add(Speaker.SYSTEM, event.content)
            }

            is ClaudeEvent.AssistantText -> edt {
                // A subagent's text belongs to ITS tab, not here. It used to be anchored under the Agent card
                // in this transcript, which is exactly what made a session running agents under agents
                // unreadable: consecutive blocks from different agents, interleaved, with no way to follow
                // any single one. The agent's own transcript is read from the binary's file by AgentRegistry,
                // so nothing is lost by dropping it — and the Agent card links to that tab.
                if (event.parentToolUseId == null) reconciler.finalizeAssistant(event.text)
            }
        }
    }

    private fun onInit(event: ClaudeEvent.Init) {
        sessionId = event.info.sessionId
        // The id is what locates this session's agent directory, so this is the earliest point a restored
        // session can bring its previously-admitted agents back. Off-EDT, and a no-op when there are none.
        restoreAdmittedAgents()
        if (model == null && event.info.model.isNotBlank()) model = event.info.model
        if (event.info.outputStyle.isNotBlank()) outputStyle = event.info.outputStyle
        // The plugin is the source of truth for permissionMode. system/init re-arrives every turn and
        // reports the *launch-time* mode ("default"), which used to clobber a user choice (the
        // recurring "reset to default" bug). Never adopt it; if the binary has drifted from our mode,
        // push ours back so it converges instead.
        val ours = SessionLauncher.binaryPermissionMode(permissionMode)
        if (event.info.permissionMode.isNotBlank() && event.info.permissionMode != ours) {
            write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), ours))
        }
        ready = true
        edt {
            systemNotice("Connected · ${event.info.model.ifBlank { "claude" }} · ${event.info.cwd}")
            fireState()
            pump()
        }
    }

    private fun onToolUse(event: ClaudeEvent.ToolUse) = edt {
        // A subagent's tool call belongs to its own tab, read from the binary's per-agent transcript. Keeping
        // it here is what buried the main conversation under other agents' work. The snapshot capture below
        // still has to happen for it, though: the binary writes the file whoever asked for it, so the diff
        // must be captured for a subagent's Edit exactly as for a top-level one.
        if (event.parentToolUseId != null) {
            if (event.name in DiffPresenter.REVIEWABLE_TOOLS) {
                diffs.captureForReview(event.name, event.input, event.id)
            }
            return@edt
        }
        reconciler.onMessageBoundary()
        transcript.add(
            Speaker.TOOL,
            formatToolUse(event.name, event.input, workingDir),
            meta = event.name,
            toolUseId = event.id,
            parentToolUseId = event.parentToolUseId,
            toolState = ToolState.LOADING, // just dispatched → light blue, until progress/result arrive
            // Project-relative file for the card's jump-to-code link (null for non-file tools).
            filePath = toolFilePath(event.name, event.input, workingDir),
            // The raw command/script text, when this call executes one — drives its own copyable code
            // block in the tool card, and is remembered so ToolResult can decide, once the output lands,
            // whether to render it as a copyable code block too. Covers Bash, PowerShell, and any MCP
            // tool that executes a command (detected by input shape, not tool name).
            commandText = SensitiveGuard.commandText(event.input),
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

    private fun onToolResult(event: ClaudeEvent.ToolResult) = edt {
        // Close the auto-opened diff (if any) now that the binary has finished writing — the inline diff
        // below preserves the change visually in the tool card, and "View diff" can re-open it from the
        // snapshot at any time, so leaving the editor tab pinned just clutters the workspace. The manager
        // closes the tab and hands back the persisted pre-write snapshot for the inline diff below.
        transcript.setToolState(event.toolUseId, if (event.isError) ToolState.ERROR else ToolState.FINISHED)
        val snap = diffs.onToolResult(event.toolUseId)
        // Refresh the VFS NOW, on each successful write — not once at the end of the turn. Until the IDE
        // sees the file on disk it does not exist for it: the editor shows stale contents, and a
        // jump-to-code link on the card resolves to nothing (LocalFileSystem returns null), so clicking it
        // did nothing until the turn finished. Edit/Write refresh exactly the paths they touched; Bash and
        // mutating MCP tools can change anything, so those mark the project tree dirty instead.
        if (!event.isError) {
            diffs.refreshTouched()
            if (mayHaveWrittenUnknownFiles(transcript.toolNameOf(event.toolUseId))) diffs.refreshProjectTree()
        }
        // For a reviewable write we captured the pre-write contents at approval time: render the actual
        // change as an inline unified diff (meta="diff") instead of the binary's "Edited file" blurb, so
        // the output box shows what changed. The diff text is self-contained, so it also survives a
        // session restore (no snapshot needed at render time). Falls back to the binary text otherwise.
        val diff = if (snap != null && snap.toolName in DiffPresenter.REVIEWABLE_TOOLS) {
            DiffPresenter.proposedContent(snap.toolName, snap.input, snap.beforeText)
                ?.let { DiffPresenter.unifiedDiff(snap.beforeText, it) }
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        // A subagent's result goes to its own tab, like its call. Everything above still ran for it — the
        // VFS refresh and the snapshot bookkeeping are about files on disk, not about which transcript
        // shows the row.
        if (event.parentToolUseId != null) return@edt
        if (diff != null) {
            transcript.addToolOutput(event.toolUseId, diff, parentToolUseId = event.parentToolUseId, meta = "diff")
        } else {
            val text = event.content.trim()
            if (text.isNotBlank()) {
                // meta is a space-separated tag set here, not a single value: a command's output can be
                // BOTH "command" (render as a copyable code block) AND "error" (the call failed) at once —
                // e.g. a failing build's stderr is exactly the kind of output you want to copy out.
                val tags = buildList {
                    if (transcript.isCommandCall(event.toolUseId)) add("command")
                    if (event.isError) add("error")
                }
                transcript.addToolOutput(
                    event.toolUseId,
                    text,
                    parentToolUseId = event.parentToolUseId,
                    meta = tags.joinToString(" ").ifBlank { null },
                )
            }
        }
    }

    private fun onTurnResult(event: ClaudeEvent.Result) = edt {
        tokens.foldIntoSession()
        reconciler.onMessageBoundary()
        turnActive = false
        // The turn just moved both numbers; read them once now. Setting turnActive false first means the poll
        // also retires the timer, so an idle session goes quiet instead of ticking forever.
        pollQuota()
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
            // /login can't run inside the TTY-less stream-json session — and raise the login card.
            if (LoginDetection.needsLogin(message)) onLoginNeeded()
        } else {
            // A clean turn means we're authenticated; allow a future auth failure to prompt again.
            needsLogin = false
            login.onCleanResult()
            // Count it toward the one-and-only Marketplace review ask. Only successful turns count, so
            // nobody is ever asked to rate a session that was failing on them. See [ReviewPrompt].
            ReviewPrompt.onSuccessfulTurn(project)
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

    /** Control traffic. Every request here MUST be answered, or the binary blocks on us forever. */
    private fun onControl(event: ClaudeEvent.Control) {
        when (event) {
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
        }
    }

    // --- E1: subagent task lifecycle. [TaskTracker] owns the observable map keyed by task_id (latest
    // progress wins); here we only keep the state and fire so the UI refreshes. ---
    private fun onTask(event: ClaudeEvent.Task) {
        when (event) {
            is ClaudeEvent.TaskStarted -> edt {
                // The admission seed: this Task call is ours, so the agent whose sidecar names it — and
                // everything it spawns below — may be shown. See AgentRegistry's admission rule.
                runningAgents.observeSpawn(event.info.toolUseId)
                scanAgents()
                if (taskTracker.onStarted(event.info)) fireState()
            }

            is ClaudeEvent.TaskProgress -> edt {
                // Also an admission seed, deliberately: a task_started can be missed (a resumed session
                // reattaches mid-flight), and progress carries the same tool_use_id.
                runningAgents.observeSpawn(event.info.toolUseId)
                taskTracker.onProgress(event.info)
                fireState()
            }

            is ClaudeEvent.TaskUpdated -> edt {
                taskTracker.onUpdated(event.info)
                fireState()
            }

            is ClaudeEvent.TaskNotification -> edt {
                // Settled: the tab KEEPS its transcript and gains a status — reading why an agent failed is
                // the case this feature came from. Only the live task map drops it.
                runningAgents.observeSettled(event.info.toolUseId, agentStatusOf(event.info.status))
                scanAgents()
                if (taskTracker.onNotification(event.info)) {
                    val label = event.info.summary.ifBlank { "Subagent ${event.info.status}" }
                    systemNotice("Subagent ${event.info.status}: $label")
                }
                fireState()
            }

            // tool_progress → RUNNING (animated box) + elapsed time (the protocol carries no completion %).
            is ClaudeEvent.ToolProgress -> edt {
                transcript.setToolState(event.info.toolUseId, ToolState.RUNNING, event.info.elapsedTimeSeconds)
            }

            // tool_use_summary → a quiet dim note summarizing the preceding tool calls.
            is ClaudeEvent.ToolUseSummary -> edt {
                if (event.info.summary.isNotBlank()) transcript.add(Speaker.SYSTEM, "↳ ${event.info.summary}")
            }

            is ClaudeEvent.BackgroundTasksChanged -> edt {
                // LEVEL signal: swap the tracked set for the payload. Never paired with the task_* edge stream
                // (the SDK leaves their relative ordering unspecified), so it can't wedge a stale running indicator.
                taskTracker.replaceBackgroundTasks(event.info.tasks)
                fireState()
            }
        }
    }

    /** Session state and metadata: drives the UI chrome (quota, turn state, commands), not transcript text. */
    private fun onSessionSignal(event: ClaudeEvent.SessionSignal) {
        when (event) {
            is ClaudeEvent.RateLimit -> onRateLimit(event)

            is ClaudeEvent.AuthStatus -> onAuthStatus(event)

            is ClaudeEvent.ControlRequestProgress -> onControlRequestProgress(event)

            is ClaudeEvent.SessionStateChanged -> {
                sessionState = event.info.state
                edt { fireState() }
            }

            // thinking_tokens → live reasoning estimate in the composer status line. EDT for single-threaded
            // counter writes; fireState so the status row repaints (it fires far slower than text deltas).
            is ClaudeEvent.ThinkingTokens -> edt {
                liveThinkingTokens = event.info.estimatedTokens
                fireState()
            }

            is ClaudeEvent.ApiRetry -> {
                val of = if (event.info.maxRetries > 0) "/${event.info.maxRetries}" else ""
                systemNotice("Retrying (attempt ${event.info.attempt}$of)…")
            }

            // commands_changed → REPLACE the cached list (supportedCommands() never reflects mid-session changes).
            is ClaudeEvent.CommandsChanged -> edt {
                commands = event.info.commands
                fireMetadata()
            }

            // prompt_suggestion → the predicted next prompt, surfaced as a clickable composer chip.
            is ClaudeEvent.PromptSuggestion -> {
                promptSuggestion = event.info.suggestion.takeIf { it.isNotBlank() }
                edt { fireState() }
            }
        }
    }

    private fun onRateLimit(event: ClaudeEvent.RateLimit) {
        val incoming = event.info
        log.debug(
            "rate_limit_event: window=${incoming.rateLimitType} status=${incoming.status}" +
                " utilization=${incoming.utilization} -> pct=${incoming.utilizationPercent()}",
        )
        val window = incoming.rateLimitType
        // Hidden windows are dropped here rather than at each surface: this is the OTHER door into the same
        // UI (the get_usage report is filtered in parseUsageReport), and it is the one "Nimbus quill 0.0%"
        // kept coming through. Dropped whole — it must not become `rateLimit` either, which drives the
        // single-number quota bar.
        if (isHiddenUsageWindow(window)) return
        // The binary often emits a rate_limit_event without `utilization` (it's optional and only present when
        // the API returns it). Don't lose a previously-known utilization just because a later event omitted
        // it — carry it forward so the quota % stays shown once we've seen it. Carried forward PER WINDOW:
        // filling a five-hour gap with a seven-day percentage would be worse than showing nothing.
        val previous = window?.let { rateLimits[it] } ?: rateLimit.takeIf { it?.rateLimitType == window }
        val merged = if (incoming.utilization == null) {
            incoming.copy(utilization = previous?.utilization)
        } else {
            incoming
        }
        rateLimit = merged
        if (window != null) rateLimits = rateLimits + (window to merged)
        edt { fireState() }
    }

    private fun onAuthStatus(event: ClaudeEvent.AuthStatus) {
        authStatus = event.info
        event.info.error?.takeIf { it.isNotBlank() }?.let {
            edt {
                transcript.add(Speaker.ERROR, "Authentication error: $it")
                if (LoginDetection.needsLogin(it)) onLoginNeeded()
            }
        }
        edt { fireState() }
    }

    private fun onControlRequestProgress(event: ClaudeEvent.ControlRequestProgress) {
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

    /** hook_started/progress/response → one evolving "⚙ Hook …" transcript row per hook. */
    private fun onHookTelemetry(event: ClaudeEvent.HookTelemetry) = edt {
        when (event) {
            is ClaudeEvent.HookStarted -> hookNarrator.onStarted(event.info)
            is ClaudeEvent.HookProgress -> hookNarrator.onProgress(event.info)
            is ClaudeEvent.HookResponse -> hookNarrator.onResponse(event.info)
        }
    }

    /** Informational text surfaced as a transcript row. Fire-and-forget: nothing here answers the binary. */
    private fun onNotice(event: ClaudeEvent.Notice) {
        when (event) {
            is ClaudeEvent.StatusNotice -> systemNotice(event.text)

            is ClaudeEvent.MemoryRecall -> onMemoryRecall(event)

            is ClaudeEvent.FilesPersisted -> onFilesPersisted(event)

            is ClaudeEvent.PluginInstall -> onPluginInstall(event)

            is ClaudeEvent.ModelRefusalFallback -> onModelRefusalFallback(event)

            is ClaudeEvent.ModelRefusalNoFallback -> onModelRefusalNoFallback(event)

            is ClaudeEvent.Informational -> onInformational(event)

            is ClaudeEvent.Notification -> onNotification(event)

            is ClaudeEvent.PermissionDenied -> onPermissionDenied(event)

            // mirror_error → the binary lost transcript data; warn the user (their session file may be incomplete).
            is ClaudeEvent.MirrorError -> {
                log.warn("mirror_error: ${event.info.error}")
                systemNotice("Warning: failed to persist part of the session transcript.")
            }

            // Live-tail only: a resumed session may replay historical instances, so don't tear anything down —
            // just log it. (Reasons like host_exit/remote_control_disabled are host-set, not user input.)
            is ClaudeEvent.WorkerShuttingDown -> log.info("worker_shutting_down: ${event.info.reason}")

            is ClaudeEvent.Other -> log.debug("Ignored ${event.type}/${event.subtype}")
        }
    }

    /** notification → in-transcript notice; high/immediate also raises an IDE notification so it isn't missed. */
    private fun onNotification(event: ClaudeEvent.Notification) {
        val text = event.info.text
        if (text.isBlank()) return
        systemNotice(text)
        if (event.info.priority == "high" || event.info.priority == "immediate") notifyInfo(text)
    }

    /** permission_denied → render the denial (the model only otherwise sees an is_error tool_result). */
    private fun onPermissionDenied(event: ClaudeEvent.PermissionDenied) = edt {
        val i = event.info
        val reason = i.message.ifBlank { i.decisionReason ?: i.decisionReasonType ?: "denied" }
        transcript.add(Speaker.ERROR, "Denied ${i.toolName}: $reason")
    }

    /** memory_recall → a collapsible "Recalled N memories" row listing what context influenced the turn. */
    private fun onMemoryRecall(event: ClaudeEvent.MemoryRecall) {
        if (event.info.memories.isEmpty()) return
        edt {
            transcript.add(
                Speaker.MEMORY,
                MemoryRecallFormatter.body(event.info),
                meta = MemoryRecallFormatter.summary(event.info),
            )
        }
    }

    private fun onFilesPersisted(event: ClaudeEvent.FilesPersisted) {
        val files = event.info.files
        if (files.isNotEmpty()) {
            systemNotice("Uploaded ${files.size} file(s): " + files.joinToString(", ") { it.filename })
        }
        if (event.info.failed.isNotEmpty()) systemNotice("Failed to persist ${event.info.failed.size} file(s)")
    }

    private fun onPluginInstall(event: ClaudeEvent.PluginInstall) {
        val i = event.info
        log.debug("plugin_install status=${i.status} name=${i.name}")
        when (i.status) {
            "installed" -> systemNotice("Plugin installed${i.name?.let { ": $it" } ?: ""}")
            "failed" -> systemNotice("Plugin install failed${i.error?.let { ": $it" } ?: ""}")
        }
    }

    private fun onModelRefusalFallback(event: ClaudeEvent.ModelRefusalFallback) {
        val i = event.info
        val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val to = i.fallbackModel.takeIf { it.isNotBlank() }
            ?.let { " → retried on $it" } ?: " → retried on a fallback model"
        systemNotice("The model declined to respond$cat$to.")
    }

    /**
     * Refusal with no fallback configured → the turn ends in error. Surface it (the content is display prose)
     * so a refused turn never ends silently.
     */
    private fun onModelRefusalNoFallback(event: ClaudeEvent.ModelRefusalNoFallback) = edt {
        val i = event.info
        val cat = i.apiRefusalCategory?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val msg = i.content.ifBlank { "The model declined to respond$cat and no fallback model was configured." }
        transcript.add(Speaker.ERROR, msg)
    }

    /**
     * Generic loop banner. Only the more prominent levels (suggestion/warning) plus any blocking message reach
     * the transcript; info/notice are already implied by the turn state and would just add noise.
     */
    private fun onInformational(event: ClaudeEvent.Informational) {
        val i = event.info
        val text = i.content.trim()
        val prominent = i.level == "warning" || i.level == "suggestion" || i.preventContinuation
        if (text.isNotEmpty() && prominent) {
            systemNotice(if (i.level == "warning") "Warning: $text" else text)
        }
    }

    private fun onTerminated(gen: Int, exitCode: Int) {
        // Ignore a stale termination: if a newer start() has run (restart — e.g. toggling thinking/model), this
        // callback belongs to the old process and must NOT tear down the freshly-started session (which would
        // null `ready`, failAll the new initialize, and print "Session ended"). The current generation wins.
        if (gen != generation) return
        // Read BEFORE the teardown below clears it: a resume that failed is one that died without ever
        // answering the handshake. A process that was up and working and then crashed is an ordinary failure,
        // and its session id is still good.
        val staleResume = resumedLaunch && !initialized
        // Flush any buffered streaming deltas (reader thread) before tearing down so trailing text isn't dropped.
        flushDeltas()
        // The process is gone: release any in-flight control callbacks so their dialogs don't hang.
        controlClient.failAll("process gone")
        edt {
            turnActive = false
            interrupting = false
            ready = false
            initialized = false
            liveThinkingTokens = 0
            promptSuggestion = null
            cards.clear()
            taskTracker.clear()
            hookNarrator.clear()
            if (exitCode != 0 && staleResume) {
                // `--resume <id>` on a conversation the binary doesn't have: it prints "No conversation found
                // with session ID: …" and exits 1, immediately, every time (verified against 2.1.223). The boot
                // watcher then relaunches every few seconds, so this is not one failure but an endless loop of
                // them — a tab stuck on "Loading Claude Code…" behind a stack of identical error toasts.
                //
                // The id is simply stale (a session that never got a turn written, a transcript deleted
                // elsewhere), so it is DROPPED and the tab continues as a new conversation. Restoring history
                // is best-effort; refusing to open a chat over it is not a trade worth making.
                log.info("resume of session $sessionId failed (exit $exitCode) — continuing as a new conversation")
                sessionId = null
                resumedLaunch = false
                systemNotice("That conversation is no longer available — started a new one.")
                fireState()
                start(resume = false)
                return@edt
            }
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
            for (effect in effects) {
                when (effect) {
                    is HookSideEffect.NotifyUser -> notifyInfo(effect.message)

                    is HookSideEffect.RefreshFile -> {
                        diffs.markForRefresh(effect.path)
                        diffs.refreshTouched()
                    }

                    is HookSideEffect.TranscriptNote -> transcript.add(Speaker.SYSTEM, effect.text)

                    is HookSideEffect.Marker -> log.debug("hook marker ${effect.event} ${effect.detail ?: ""}")
                }
            }
        }
    }

    private fun systemNotice(message: String) = edt { transcript.add(Speaker.SYSTEM, message) }

    /**
     * Re-reads the agent directory off the EDT and tells the UI, if anything came of it.
     *
     * Coalesced rather than queued: while a scan is walking the directory, further requests are dropped —
     * a burst of task events on a heavy session (dozens of agents spawning at once, which is the case this
     * feature exists for) would otherwise queue one directory walk per event.
     */
    fun scanAgents() {
        if (!agentScanInFlight.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = runCatching { runningAgents.scan() }.getOrDefault(emptyList())
            // Persist what was admitted, so a later run of the plugin still counts these as ours while a
            // terminal-spawned agent in the same directory never does. Done here rather than at task_started
            // because the tool_use_id → agent id mapping only exists once the binary has written the sidecar.
            sessionId?.let { id ->
                runCatching {
                    val index = PluginAgentIndex.getInstance(project)
                    runningAgents.nodes.keys.forEach { index.admit(id, it) }
                }
            }
            agentScanInFlight.set(false)
            edt { fireAgents(fresh) }
        }
    }

    /**
     * Brings back the agents a previous run of the plugin admitted for this session id, then scans.
     *
     * This is the whole of "restore the agent tabs": the transcripts are the binary's files, still on disk,
     * and the index says which of the agents in that directory were ever ours. An agent spawned from the
     * terminal is in the same directory and is never in the index, so it stays invisible.
     */
    private fun restoreAdmittedAgents() {
        val id = sessionId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { PluginAgentIndex.getInstance(project).admittedAgents(id) }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { runningAgents.preAdmit(it) }
            scanAgents()
        }
    }

    /** `task_notification`'s status string → the agent lifecycle the tab shows. */
    private fun agentStatusOf(status: String): AgentStatus = when (status.lowercase()) {
        "completed" -> AgentStatus.COMPLETED
        "failed" -> AgentStatus.FAILED
        else -> AgentStatus.STOPPED
    }

    private fun fireAgents(fresh: List<String>) = listeners.forEach { it.onAgentsChanged(fresh) }

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

    /** The network-share gate fired: tell the user why, in the transcript and as an error notification. EDT. */
    private fun refuseRemoteProject(root: String?) {
        val where = root ?: "this location"
        val msg = "Claude Code will not run on a network or remote drive ($where). Running an autonomous agent " +
            "rooted on shared storage is a security risk it refuses by design — move the project to a local disk. " +
            "For unrestricted use, run the `claude` CLI directly."
        edt {
            transcript.add(Speaker.ERROR, msg)
            fireState()
        }
        notifyError(msg)
        starting = false
    }

    /** Runs the OAuth sign-in. Delegates to [login]; public so the composer can route a typed `/login` here. */
    fun startLogin(mode: LoginCoordinator.Mode = LoginCoordinator.Mode.SUBSCRIPTION) = login.start(mode)

    // The sign-in card's plumbing, one delegate each — the card lives in the panel, the flow in the
    // coordinator, and the session stays the thin orchestrator between them.
    fun attachLoginUi(ui: LoginCoordinator.LoginUi) = login.attachUi(ui)
    fun detachLoginUi(ui: LoginCoordinator.LoginUi) = login.detachUi(ui)
    fun submitLoginCode(code: String) = login.submitCode(code)
    fun cancelLogin() = login.cancelLogin()

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
            .addAction(
                NotificationAction.createSimple("Configure paths…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
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

    /** The concrete model to select in place of the removed "default" alias — see [preferredDefault]. */
    fun preferredDefaultModel(): String = preferredDefault(models)

    companion object {
        const val NOTIFICATION_GROUP = "Claude Code"

        /** How long to wait for a reply to a host-initiated control request before failing it (watchdog). */
        const val CONTROL_TIMEOUT_SECONDS = 30L

        /** Interval (ms) of the session-scoped quota poll (get_session_cost + get_context_usage), shared by all
         *  ChatPanels observing this session — one timer per session, not one per tab.
         *
         *  One second, and the budget holds because of two multipliers already in place: the timer only runs
         *  WHILE A TURN IS ACTIVE (it retires at turn end — idle sessions poll zero times), and both requests
         *  are local IPC to the `claude` process, which answers from its own counters without a network hop.
         *  At 60s the context meter and cost sat visibly frozen through a whole turn and only told the truth
         *  after it ended, which reads as a broken meter exactly while the user is watching it. */
        const val QUOTA_POLL_MS = 1_000

        /** How long a "the binary has its own login" answer is trusted. It costs a process spawn to get. */
        private const val OWN_LOGIN_TTL_MS = 30_000L

        /**
         * Default model on a fresh install: the concrete Opus tier is **pinned** (not the binary's floating
         * "default" alias), so the choice stays on Opus even if the binary later re-points its recommendation.
         * [preferredDefault] falls back to the binary's own recommended alias if a binary ever ships without this
         * concrete value, so we never select a model it doesn't offer.
         */
        const val DEFAULT_MODEL = "opus[1m]"

        /** The binary's floating "recommended" alias — resolved server-side to whatever tier it currently favours.
         *  We no longer offer it as a selectable option (it duplicated the concrete tier and hid the version). */
        const val RECOMMENDED_ALIAS = "default"

        /**
         * The concrete model to use when the persisted choice is the removed [RECOMMENDED_ALIAS] (legacy installs)
         * or unset: the pinned [DEFAULT_MODEL] when this binary offers it, else the binary's own recommended alias,
         * else whatever it lists first — so the result is always a model the binary actually has. Pure for tests;
         * [models] is empty before the handshake, in which case it returns the pin optimistically.
         */
        fun preferredDefault(models: List<ModelInfo>, pinned: String = DEFAULT_MODEL): String = when {
            models.isEmpty() -> pinned
            models.any { it.value == pinned } -> pinned
            models.any { it.value == RECOMMENDED_ALIAS } -> RECOMMENDED_ALIAS
            else -> models.first().value
        }

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

        /** Listenable TCP port range; anything outside it falls back to [DEFAULT_IDE_MCP_PORT]. */
        private val VALID_PORTS = 1..65_535

        /**
         * Quota levels worth interrupting the user about, ascending. They match the composer dot's colour
         * scale exactly (blue → amber → red), so the warning and the indicator always agree.
         */
        private val QUOTA_THRESHOLDS = listOf(65, 85)
        private const val QUOTA_THRESHOLD_HIGH = 85

        /** Truncation for the `get_usage` reply trace — a bound, since the payload is not ours to size. */
        private const val USAGE_LOG_CHARS = 2000

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

        /** Tools whose `file_path` names a project file the transcript can hyperlink (jump-to-code). */
        val FILE_TOOLS = setOf("Read", "Edit", "Write", "MultiEdit", "NotebookEdit")

        /**
         * A name that reads like a mutation, for tools we do NOT know — i.e. MCP ones (`replace_text_in_file`,
         * `create_new_file`, `apply_patch`, `reformat_file`, `rename_refactoring`…). Applied ONLY to unknown tools:
         * on a built-in it would misfire (`TodoWrite` contains "write" and touches no file at all).
         *
         * Generous on purpose. A false positive costs one async VFS refresh the IDE coalesces away; a false
         * negative means the IDE keeps showing stale files, so we err towards refreshing.
         */
        private val MUTATING_TOOL_NAME = Regex(
            // Mutations AND executors: an MCP `execute_terminal_command` / `run_configuration` can write anything,
            // just like Bash — so it must trigger a project-tree refresh too (a real gap the code review caught).
            "(edit|write|create|delete|remove|move|rename|patch|format|refactor|replace|insert|save|" +
                "exec|execute|run|terminal|shell|command|apply|generate|build|install)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * True when [toolName] may have changed files we cannot name — so the IDE must re-scan the project tree
         * rather than a known list of paths.
         *
         * `Bash` always qualifies (a `mv`, a formatter, a codegen script). The file tools never do: their paths are
         * known and refreshed exactly. Every other built-in reads. Anything else is an MCP tool, judged by name.
         *
         * PURE — no IDE, unit-testable.
         */
        fun mayHaveWrittenUnknownFiles(toolName: String?): Boolean {
            val name = toolName?.takeIf { it.isNotBlank() } ?: return false
            if (name == "Bash") return true
            if (name in FILE_TOOLS || name in BUILTIN_TOOLS) return false
            return MUTATING_TOOL_NAME.containsMatchIn(name)
        }

        /**
         * The tool call's file argument as a path **relative to [projectRoot]**, or null when the tool takes no
         * file / the path escapes the project (an absolute path outside the root stays absolute — we show the
         * truth, and the jump-to-code gate refuses to open it anyway).
         *
         * PURE (no IDE): [projectRoot] is passed in, so this is unit-testable.
         */
        fun toolFilePath(name: String, input: JsonObject, projectRoot: String?): String? {
            if (name !in FILE_TOOLS) return null
            val path = input.str("file_path")?.takeIf { it.isNotBlank() } ?: return null
            return relativizeToRoot(path, projectRoot)
        }

        /** `/abs/root/src/Foo.kt` + root `/abs/root` → `src/Foo.kt`. Leaves anything outside the root untouched. */
        fun relativizeToRoot(path: String, projectRoot: String?): String {
            val root = projectRoot?.takeIf { it.isNotBlank() } ?: return path
            val normRoot = root.trimEnd('/', '\\')
            // Compare with the platform separator normalised, so Windows paths relativise too.
            val p = path.replace('\\', '/')
            val r = normRoot.replace('\\', '/')
            if (!p.startsWith("$r/")) return path
            return p.removePrefix("$r/")
        }

        /**
         * Concise one-line representation of a tool call, mirroring the CLI's "Tool(arg)" bullets. File tools show
         * the path **relative to the project** — `Read(src/main/kotlin/permission/PermissionBroker.kt)` — rather
         * than a bare file name, so the row says *which* file and the frontend can hyperlink it.
         */
        fun formatToolUse(name: String, input: JsonObject, projectRoot: String? = null): String {
            val arg = when (name) {
                "Bash" -> input.str("command")
                in FILE_TOOLS -> toolFilePath(name, input, projectRoot)
                "Glob", "Grep" -> input.str("pattern")
                "Task" -> input.str("description")
                "WebFetch" -> input.str("url")
                "WebSearch" -> input.str("query")
                else -> input.str("file_path")?.let { relativizeToRoot(it, projectRoot) } ?: input.str("path")
            }
            return if (!arg.isNullOrBlank()) "$name($arg)" else name
        }
    }
}
