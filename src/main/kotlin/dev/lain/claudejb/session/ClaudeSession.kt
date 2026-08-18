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
import dev.lain.claudejb.permission.ToolInputScanner
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
import dev.lain.claudejb.settings.requiresTrustPrompt
import dev.lain.claudejb.settings.resolveEnv
import dev.lain.claudejb.settings.sensitiveDecision
import dev.lain.claudejb.settings.setExecutionTrusted
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
class ClaudeSession(
    private val project: Project,
    @Volatile var title: String,
    /**
     * This session is the **Git integration's** chat rather than one of the user's own conversations.
     *
     * Immutable, and deliberately so: it decides that every tool call in this chat is put to the user as a
     * card whatever the permission mode says ([PermissionBroker]'s `forceAsk`), and a security-relevant
     * property that any caller could flip afterwards is not a property, it is a suggestion.
     *
     * Its other job is smaller and just as load-bearing: it keeps the tab called *Git*. Titles otherwise
     * fall back to the first thing the user asked ([recordOpenAndTitle]), which for this chat would be
     * whichever git command happened to open it.
     */
    val gitIntegration: Boolean = false,
) : Disposable {

    private val log = thisLogger()

    val transcript = TranscriptModel()

    // --- extracted collaborators (the session delegates to these; see each class for the contract) ---
    private val tokens = TokenAccountant()
    private val taskTracker = TaskTracker()
    private val reconciler = TranscriptReconciler(transcript)
    private val diffs = DiffLifecycleManager(project)
    private val rollback = RollbackManager(project, diffs, reseedReadState = { p, m -> queries.seedReadState(p, m) })
    private val controlClient = SessionControlClient(write = ::write)

    /**
     * Everything the UI ASKS the binary on demand — see [SessionQueries].
     *
     * Exposed rather than wrapped in eleven one-line delegates. Delegating would have kept the call sites
     * untouched and bought nothing: the same eleven verbs would still be part of this class's API, which is
     * the thing being reduced. A caller that wants an answer from the binary asks `session.queries`.
     */
    val queries = SessionQueries(
        controlClient = controlClient,
        isRunning = ::isRunning,
        edt = ::edt,
        write = ::write,
        quota = QuotaWarnings(log, QuotaWarnings.Announce(inTranscript = ::systemNotice, asNotification = ::notifyInfo)),
    )

    /** What the binary SAYS, as opposed to what it does in a turn — see [NoticeNarrator]. */
    private val notices = NoticeNarrator(
        log = log,
        systemNotice = ::systemNotice,
        addRow = { speaker, text, meta -> transcript.add(speaker, text, meta = meta) },
        notifyInfo = ::notifyInfo,
        edt = ::edt,
    )
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
     * Whether this process has already asked the binary to name the conversation.
     *
     * Set BEFORE the request goes out, so a refusal, a timeout or a blank answer costs one attempt and not a
     * request per turn for the rest of the session. The binary persists the title it generates, so the normal
     * case never reaches here twice anyway — this bounds the abnormal one.
     */
    @Volatile private var titleGenerationAsked: Boolean = false

    /**
     * Whether the user has renamed this chat by hand.
     *
     * A generated title is asked for once and arrives whenever it arrives; a rename in that window must not
     * be overwritten by it. What the user typed is never replaced by what a model wrote — the ordering of the
     * two answers is not allowed to decide that.
     */
    @Volatile private var userRenamed: Boolean = false

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

    /**
     * Every background task seen this process — live and finished — with its owner, its card and its output.
     *
     * Kept alongside [backgroundTasks] rather than instead of it: that one is the binary's LEVEL signal and
     * answers "what is running now", which is what it is for. It is also why a finished task used to vanish
     * from the rows, the tabs and the dashboard the instant it ended, taking its output with it. See
     * [BackgroundTaskRegistry].
     */
    val backgroundTaskRegistry = BackgroundTaskRegistry()

    /**
     * Keeps the agent tree and the background tasks in step with what the binary writes to disk — see
     * [AgentScanner]. This class asks for a scan; everything the scan does is over there.
     */
    private val agentScanner = AgentScanner(
        project = project,
        agents = runningAgents,
        tasks = backgroundTaskRegistry,
        sessionId = { sessionId },
        ownerOfTask = ::ownerAgentOfTask,
        ui = object : AgentScanner.Ui {
            override fun labelCards() {
                labelAgentCards()
                // A scan is the only thing that can make an agent settled, which is half the revival gate.
                ensureAgentRevivalPoll()
            }
            override fun onFresh(fresh: List<String>) = fireAgents(fresh)
            override fun onOutputGrew() = fireState()
            override fun edt(block: () -> Unit) = this@ClaudeSession.edt(block)
        },
    )

    /**
     * The agent running background task [taskId], or null when it belongs to the chat's own turn (or when the
     * binary never gave us enough to say).
     *
     * ONE resolution rule, in one place, because there are two sources and they must not be allowed to
     * disagree on screen: the structured tool output ([backgroundTaskRegistry]) is authoritative — it is the
     * call that actually started the task — and the edge stream ([subagentTasks]) is the fallback for a task
     * seen as a subagent bookend. Whatever cannot be resolved is left unclaimed rather than guessed: a wrong
     * ownership chain is worse than an honest gap.
     */
    fun ownerAgentOfTask(taskId: String): String? {
        val fromLink = backgroundTaskRegistry.taskOf(taskId)?.ownerToolUseId
        val fromEdge = subagentTasks[taskId]?.toolUseId
        val tool = fromLink ?: fromEdge ?: return null
        return runningAgents.nodes.values.firstOrNull { it.meta.toolUseId == tool }?.agentId
    }

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
                // No parent id: [bufferStream] admits only main-run deltas, and a run is a concatenation of
                // several frames anyway, so there is no single id left to carry by the time we get here.
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
    // Previously every chat panel ran its own 60s javax.swing.Timer that fired get_session_cost +
    // get_context_usage; N tabs of the same session meant N identical polls. We now run ONE timer per
    // session, cache the results here, and notify the panel(s) via the existing onStateChanged() listener
    // callback — so any number of panels observing this session share a single poll. The timer is an EDT
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
     * Reads a running background task's output file while it runs — see [AgentScanner.tailNow].
     *
     * A separate timer from [quotaPollTimer] and NOT tied to the turn: a task backgrounded near the end of a
     * turn keeps writing after the turn is over, and that is precisely when the user goes to read it. It
     * costs a `size` check per running task per tick and stops itself the moment nothing is tailable, so an
     * idle session runs no timer at all.
     */
    private val outputTailTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollLiveOutput() }.apply { isRepeats = true }

    private fun pollLiveOutput() {
        if (!backgroundTaskRegistry.anyTailable) {
            outputTailTimer.stop()
            return
        }
        agentScanner.tailNow()
    }

    /** Starts the live-output poll if anything is worth tailing. EDT. Idempotent — a running timer is left alone. */
    private fun ensureOutputTail() {
        if (backgroundTaskRegistry.anyTailable && !outputTailTimer.isRunning) outputTailTimer.start()
    }

    /**
     * Re-reads the agent tree while a turn runs, so an agent that is RESUMED stops reading as finished.
     *
     * No event covers this: a settled agent revives by getting more records in its own transcript, and a nested
     * one has no `tool_use_id` at all, so it revives only through its parent. Neither writes anything to the
     * main stream — the growth is visible only by walking the directory again, which is what
     * [AgentRegistry.reopenIfGrown] acts on.
     *
     * A pass re-parses every admitted agent's whole transcript, so it costs far more than [outputTailTimer]'s
     * `size` check and runs at [AGENT_REVIVAL_POLL_MS] rather than [QUOTA_POLL_MS]. Both halves of the gate are
     * necessary conditions for the pass to be able to do anything: only the binary writes those files and it
     * only writes them during a turn, and only a settled agent can be brought back — so a chat with no agents,
     * and an idle chat, run no timer at all.
     */
    private val agentRevivalTimer = javax.swing.Timer(AGENT_REVIVAL_POLL_MS) { pollAgentRevival() }.apply {
        isRepeats = true
    }

    private fun pollAgentRevival() {
        if (!turnActive || !anySettledAgent()) {
            agentRevivalTimer.stop()
            return
        }
        agentScanner.scan()
    }

    /** Starts the revival poll if anything could revive. EDT. Idempotent — a running timer is left alone. */
    private fun ensureAgentRevivalPoll() {
        if (turnActive && anySettledAgent() && !agentRevivalTimer.isRunning) agentRevivalTimer.start()
    }

    /** Whether any agent has stopped running — the only kind a rescan can bring back. */
    private fun anySettledAgent(): Boolean = runningAgents.nodes.values.any { it.status != AgentStatus.RUNNING }

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
        queries.requestSessionCost { cost ->
            if (cost != null) lastSessionCost = cost
            settle()
        }
        queries.requestContextUsage { cu ->
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
            // The Git chat's turns are started by a button in the IDE, so every one of its calls is put to the
            // user — the permission mode and "Always allow" are answers they gave about their own work.
            forceAsk = { gitIntegration },
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
        when (auth.heldCredential(settings)) {
            // Sign-in comes BEFORE the loading screen, not after it. Verifying auth needs no session, and
            // launching one we know is unauthenticated only buys a spawned process, a spinner, and a turn
            // that fails later for a reason the user already knew at click time.
            Credential.NONE -> {
                onLoginNeeded()
                return false
            }

            // Only a process could answer, and this runs on the EDT. Launch nothing and — the point of the
            // third answer — raise NO card: the users who fall through to that branch are the ones whose
            // binary keeps its credentials in an OS store, i.e. the ones already signed in. The boot watcher
            // resolves it off the EDT within a tick and comes back here through [refreshBootState]. `true`
            // because a prompt sent in that window belongs in the queue, which [pump] flushes on ready, not
            // dropped by its caller.
            Credential.UNKNOWN -> return true

            Credential.HELD -> Unit
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
     * Who this session runs as — see [AuthGate]. Public because the dashboard's account card reads the last
     * probe straight off it; wrapping that in a getter here would only be this class carrying a field it has
     * no opinion about.
     */
    val auth = AuthGate(
        project = project,
        signInInProgress = { login.inProgress },
        launchEnv = { effectiveLaunchEnv() },
        onProbed = { loggedIn ->
            when {
                !loggedIn -> onLoginNeeded()

                needsLogin -> {
                    needsLogin = false
                    edt { fireState() }
                }

                // Account card enrichment (email/plan) still wants a push.
                else -> edt { fireState() }
            }
        },
    )

    /**
     * Whether the live process was launched with `--resume`. Read in [onTerminated]: a resumed launch that
     * dies before the handshake is a conversation the binary cannot find, which is a recoverable condition
     * (drop the id, open a fresh one) and not the generic "it exited" failure.
     */
    @Volatile
    private var resumedLaunch = false

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
        auth.absorbExistingLoginOnce()
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath)
        val missing = binary == null
        // ONE hop, and what it buys is that the page can never be handed a state that does not exist. The
        // binary going away under a live session — uninstalled, or a path that stopped resolving — has to stop
        // that session before the install screen says it is not installed; done in two hops, the first pushes
        // `binaryMissing` while the process is still up and the page draws the install card over a chat that is
        // still answering. Stopping, flipping the flag and pushing in one EDT event makes that combination
        // unreachable rather than brief.
        edt {
            if (missing && isRunning()) stop()
            if (missing != binaryMissing) {
                binaryMissing = missing
                fireState()
            }
        }
        if (binary == null) return
        // Persist a freshly-installed binary's path here too, not only in resolveBinary: the install card's
        // "it appeared" path went through a start() that could return before ever writing it down.
        if (settings.claudePath != binary.absolutePath) {
            settings.update { it.claudePath = binary.absolutePath }
        }
        val credentialed = auth.hasCredential(settings)
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
        if (settings.claudePath != binary.absolutePath) {
            settings.update { it.claudePath = binary.absolutePath }
        }
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
        // ([AuthGate.absorbExistingLoginOnce]), and a sign-in files its own credential when it succeeds.
        //
        // The credential reaches the binary through the environment, WHOLE (CredentialsVault.envOverlay), and
        // the binary keeps its own `~/.claude`. Nothing is relocated, symlinked or deleted.
        //
        // Renewal FIRST, and here rather than in start(): it spawns a process, start() runs on the EDT, and
        // the env below has to be built from the credential we are about to hold — not the expired one.
        if (!auth.renew(binary, settings)) {
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
            proc.terminate()
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
            auth.probe()
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
        // EOF then kill, off this thread — `stop()` is reached from the EDT (the Stop button, a settings
        // change that restarts, a tab closing), and neither half may run there. See [ClaudeProcess.terminate].
        process?.terminate()
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
        // Both are per-PROCESS state, exactly like the task set: a restarted binary re-announces whatever is
        // still alive, and keeping a dead task's output would show a finished thing as if it were running.
        backgroundTaskRegistry.clear()
        agentScanner.clearTails()
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
     *
     * The assembly itself — wire text vs. display text, mentions, relativisation — is [PromptComposer]'s: pure,
     * so it doesn't need a session to be tested against.
     */
    fun send(text: String, attachments: List<Attachment>) {
        val composed = PromptComposer.compose(text, attachments, project.basePath) ?: return
        if (!isRunning()) {
            if (!start()) return
        }
        // Queue access is EDT-confined (the deque isn't thread-safe).
        edt {
            queue.addLast(Outgoing(composed.wireText, composed.images, composed.displayText))
            fireState()
            pump()
        }
    }

    /**
     * `/btw` — a quick question answered *alongside* the conversation, even mid-turn, without becoming a turn
     * in it.
     *
     * **Sent as the `side_question` control request, and that is the fix, not a refactor.** It used to be
     * written as an ordinary `user` line and the answer was expected to turn up in the stream — where it is
     * dropped: [TranscriptReconciler.belongsHere] discards every assistant block the binary does not label as
     * the main run, which is the filter that keeps a subagent's output out of this transcript and which a side
     * answer, by construction, is on the wrong side of. Relaxing that filter would re-open the interleaving it
     * exists to prevent; asking through the channel that RETURNS the answer costs nothing and cannot lose it.
     * It is also what `system/control_request_progress` reports the progress of — until now the plugin modelled
     * the progress of a request it never sent.
     *
     * **A side question is not a turn**, so `turnActive` is left alone: the composer does not claim the session
     * is working, no quota poll is armed for it, and a real turn running at the same time keeps its own state.
     * The correlation and the watchdog come from [SessionControlClient] via [queries], so a binary that never
     * answers ends as a stated non-answer rather than as silence.
     */
    fun sendSideQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!isRunning()) {
            // Cold start: the launch is async (process not up yet), so there is nothing to ask yet. Fall back to
            // the queue, which pump() flushes once the process is ready — on an idle chat a side question is
            // just a question, and a normal send is the right thing.
            if (!start()) return
            edt {
                queue.addLast(Outgoing(trimmed, emptyList(), trimmed))
                fireState()
                pump()
            }
            return
        }
        // The transcript is EDT-confined, and so is the queue pump() touches.
        edt {
            transcript.add(Speaker.USER, "↪ $trimmed")
            queries.askSideQuestion(trimmed) { answer ->
                // On the EDT. The SYSTEM speaker and the mirrored arrow are the transcript's existing grammar
                // for "the binary said this, and it is not part of the conversation" — the same row a notice
                // uses. Rendering it as ASSISTANT would put it in the turn it deliberately is not part of.
                transcript.add(Speaker.SYSTEM, answer?.let { "↩ $it" } ?: SIDE_QUESTION_UNANSWERED)
            }
            // Flush anything still queued from startup.
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
            // The other half of the revival gate: agents settled by an earlier turn become revivable again.
            ensureAgentRevivalPoll()
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
        // The agents come back HERE, not when the binary first speaks. They live in files on disk and the
        // index already says which are ours, so nothing about them needs a running process — and waiting for
        // `system/init` meant a restored chat showed no agent rows at all until the user sent a prompt, which
        // is exactly the "they are always lost" report. Off-EDT inside.
        agentScanner.restoreAdmitted(onTasksReplayed = ::fireState)
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
                    messageText = dto.messageText,
                    // …and the same STATE. The default is FINISHED, which drew every restored card green:
                    // a call still in flight looked done, and one that had failed looked fine. The reader
                    // works both out by pairing each `tool_use` with its `tool_result` (see EntryDTO).
                    toolState = when {
                        dto.failed -> ToolState.ERROR

                        // No result AND this transcript comes from disk: the process that would have
                        // returned it is gone, so the call was CANCELLED, not running. Fading it forever
                        // was worse than the green it replaced — a ToolSearch cut off hours ago sat there
                        // pulsing as if the IDE were still waiting for it.
                        dto.inFlight -> ToolState.ERROR

                        // A Task/Agent row is the AGENT, and a Task call returns as soon as it has spawned
                        // one — so its result says nothing about how the agent ended. Restored, the honest
                        // default is stopped: the run that owned it is over. The scan upgrades it to green
                        // (completed) or blue (still running, resumed) when the sidecars say so; starting
                        // green meant a killed subagent stayed green if that scan never reached this row.
                        dto.meta == "Task" || dto.meta == "Agent" -> ToolState.ERROR

                        else -> ToolState.FINISHED
                    },
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
            // The Git chat keeps its name (see [gitIntegration]): the fallback title is the first thing asked,
            // which here is a git command and not what this tab is. It is not offered a generated one either,
            // for the same reason — the tab is called Git because that is what it is.
            if (!gitIntegration) {
                val resolved = SessionTitleReader.read(id)
                if (resolved != null && resolved.text != title) {
                    title = resolved.text
                    edt { fireTitleChanged() }
                }
                // Nothing has NAMED this chat yet — it is showing its opening line. The binary can do better
                // and it is one request away; see [askForGeneratedTitle].
                if (resolved?.authored != true) askForGeneratedTitle(resolved?.prompt)
            }
            // Every chat that HAS a tab. The Git conversation is drawn inside the Git view and has none, so
            // recording it here would make the next startup open one for it — undoing the whole point of it
            // not being a tab. Same exclusion as `ChatSessionManager.persistOpenTabs`, and for one reason.
            SessionHistory.getInstance(project).setOpenSessions(
                ChatSessionManager.getInstance(project).all()
                    .filterNot { it.gitIntegration }
                    .mapNotNull { it.sessionId },
            )
        }
    }

    /**
     * Asks the binary to name this conversation — once, off the critical path, and never at the cost of the
     * name it already has.
     *
     * **Why the binary and not us.** Naming a conversation is a model's job, and the model is already up: the
     * `generate_session_title` control request runs inside the live session, so there is no second process, no
     * credential and no prompt of our own. It persists the answer in its own session file, which is why this
     * costs one request per chat *ever* rather than one per start — the next launch reads it back as an
     * authored title (`SessionTitle.authored`) and never gets here.
     *
     * **When.** At the end of a turn, from [recordOpenAndTitle]. Not earlier: before the first turn there is
     * no session id, no prompt on disk and nothing to summarise. Not later than the first turn either — a tab
     * whose name settles two turns in is a tab the user has already learned to find by position.
     *
     * **What it cannot do:** name a subagent. The request carries no agent id and acts on the session that
     * answers it, so a subtab's title stays what the parent model wrote for it in
     * `subagents/agent-<id>.meta.json` — which is already model-authored text (see [AgentMeta.label]), and is
     * the same kind of text this request takes as input.
     *
     * Fail-safe by construction: [titleGenerationAsked] is set before the request leaves, so a refusal, a
     * watchdog timeout or a blank answer costs one attempt and leaves the fallback standing, silently.
     */
    private fun askForGeneratedTitle(prompt: String?) {
        val description = prompt?.takeIf { it.isNotBlank() } ?: return
        if (titleGenerationAsked) return
        titleGenerationAsked = true
        queries.requestGeneratedTitle(description) { generated ->
            // On the EDT (SessionQueries hops). Cut to tab size by the same rule as the fallback: the length
            // of a title is not the model's to decide.
            val named = generated?.let { SessionTitleReader.asTitle(it) } ?: return@requestGeneratedTitle
            // A rename that landed while this was in flight is the user's word on the matter, and it stands.
            if (userRenamed || named == title) return@requestGeneratedTitle
            title = named
            fireTitleChanged()
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
        // `save()` is explicit since 5.5.0: the settings are the plugin's own file now, so nothing writes
        // them for us and a mutation without it is a setting that silently does not stick.
        ClaudeSettings.getInstance(project).update { it.permissionMode = mode }
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
        settings.update { it.provider = target.id }
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

    /**
     * Refresh the VFS for files the binary changed during a rewind so the editor reflects them.
     *
     * Stays here, unlike the eleven control requests that moved to [queries]: this asks the binary nothing.
     * It is the IDE reacting to what a rewind did, which is this class's own diff lifecycle.
     */
    fun refreshAfterRewind(paths: List<String>) {
        paths.forEach { diffs.markForRefresh(it) }
        diffs.refreshTouched()
    }

    // -----------------------------------------------------------------------
    // File rollback — delegated to [RollbackManager]; reached from a transcript card's Restore
    // -----------------------------------------------------------------------

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

    /**
     * Renames the current session (E5): tells the binary, updates the tab title, notifies listeners.
     *
     * This is the top of the order of authority, and [userRenamed] is what keeps it there: a generated title
     * still in flight when the user types one must not land on top of it.
     */
    fun renameSession(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        if (isRunning()) write(ControlProtocol.renameSessionRequest(ControlProtocol.newRequestId(), trimmed))
        userRenamed = true
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

            is ClaudeEvent.Notice -> notices.onNotice(event)

            // Returned above; the branch exists only because the compiler checks this `when` for exhaustiveness,
            // which is exactly the property we want it to keep checking.
            is ClaudeEvent.Stream -> {}
        }
    }

    /**
     * Buffers a streaming delta on the reader thread; [flushDeltas] lands them on the EDT in one batch.
     *
     * Only the MAIN conversation streams into this transcript — the same rule the finalized blocks are
     * filtered by ([TranscriptReconciler.belongsHere]), asked of the same predicate so the two halves cannot
     * drift apart the way they did when each spelled out its own `== null`.
     *
     * Filtered HERE and not only in the reconciler because the buffer coalesces consecutive same-type deltas
     * into one run: a foreign delta admitted this far would be concatenated into the main run's
     * StringBuilder, and by flush time there would be no seam left to drop it at.
     *
     * NB what actually keeps a subagent's deltas out is the binary, not this check: it emits every
     * `stream_event` frame with a hard-coded `parent_tool_use_id: null`, and it never turns a subagent's
     * partial messages into one — only its assembled `assistant`/`user` messages are forwarded, and those DO
     * carry the id. The check stays because it is the correct rule and costs a comparison, but it must not be
     * read as the thing that stops the interleaving; the finalized-block branches are.
     */
    private fun bufferStream(event: ClaudeEvent.Stream) {
        // A statement `when` over a sealed type is still checked for exhaustiveness, so a new Stream event
        // remains a compile error here rather than a silently dropped frame.
        when (event) {
            is ClaudeEvent.TextDelta ->
                if (TranscriptReconciler.belongsHere(event.parentToolUseId)) {
                    bufferDelta(isThinking = false, text = event.text)
                }

            is ClaudeEvent.ThinkingDelta ->
                if (TranscriptReconciler.belongsHere(event.parentToolUseId)) {
                    bufferDelta(isThinking = true, text = event.text)
                }

            is ClaudeEvent.LiveUsage ->
                bufferUsage(event.inputTokens, event.cacheCreationTokens, event.cacheReadTokens, event.outputTokens)
        }
    }

    /** The conversation proper: session start, assistant output, tool calls, end of turn. */
    private fun onConversation(event: ClaudeEvent.Conversation) {
        when (event) {
            is ClaudeEvent.Init -> onInit(event)

            is ClaudeEvent.ToolUse -> onToolUse(event)

            is ClaudeEvent.ToolResult -> onToolResult(event)

            is ClaudeEvent.Result -> onTurnResult(event)

            // A subagent's reasoning belongs to ITS tab, for exactly the reason its text does — see the
            // AssistantText branch below. This is the frame that carries the label (unlike a `stream_event`,
            // which the binary always emits with `parent_tool_use_id: null`), so this branch is where the
            // separation is actually made: a session running a dozen agents otherwise fills the main
            // transcript with interleaved "Thought process" rows nobody can follow.
            //
            // The id is PASSED rather than branched on: dropping it before the reconciler is reached would
            // leave the rule restated at every call site, and the one that forgets it is silent — a row in
            // the wrong transcript looks like the model rambling, not like a bug.
            is ClaudeEvent.AssistantThinking -> edt {
                reconciler.finalizeThinking(event.text, event.parentToolUseId)
            }

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
                reconciler.finalizeAssistant(event.text, event.parentToolUseId)
            }
        }
    }

    private fun onInit(event: ClaudeEvent.Init) {
        sessionId = event.info.sessionId
        // The id is what locates this session's agent directory, so this is the earliest point a restored
        // session can bring its previously-admitted agents back. Off-EDT, and a no-op when there are none.
        agentScanner.restoreAdmitted(onTasksReplayed = ::fireState)
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
            ToolNaming.formatToolUse(event.name, event.input, workingDir),
            meta = event.name,
            toolUseId = event.id,
            parentToolUseId = event.parentToolUseId,
            toolState = ToolState.LOADING, // just dispatched → light blue, until progress/result arrive
            // Project-relative file for the card's jump-to-code link (null for non-file tools).
            filePath = ToolNaming.toolFilePath(event.name, event.input, workingDir),
            // The raw command/script text, when this call executes one — drives its own copyable code
            // block in the tool card, and is remembered so ToolResult can decide, once the output lands,
            // whether to render it as a copyable code block too. Covers Bash, PowerShell, and any MCP
            // tool that executes a command (detected by input shape, not tool name).
            commandText = ToolInputScanner.commandText(event.input),
            // …and the text it SENDS, when it sends one. Same reasoning, same place: a card that shows only
            // the reply tells you the call worked and never tells you what was said.
            messageText = ToolInputScanner.messageText(event.input),
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
        // A Task call returns the moment it has SPAWNED its agent, not when the agent is done — so marking
        // it FINISHED here painted the card green while its agent was still working, and left it green
        // forever if the agent was later stopped. That card stands for the agent, so its state is the
        // agent's: [labelAgentCards] owns it from here on, and this must not overwrite that.
        if (runningAgents.nodes.values.none { it.meta.toolUseId == event.toolUseId }) {
            transcript.setToolState(
                event.toolUseId,
                if (event.isError) ToolState.ERROR else ToolState.FINISHED,
            )
        }
        // A backgrounded call names its task here and NOWHERE else: this is what gives a background task an
        // owner, a card to jump to and an output to show (see BackgroundTaskLinks). Done for a subagent's
        // result too, which returns below — the task belongs to that agent precisely.
        if (backgroundTaskRegistry.observe(event)) {
            // The tool_result is where a backgrounded command NAMES its output file, and it is the last event
            // that will arrive until it finishes — so the poll starts here or the output is never read live.
            ensureOutputTail()
            fireState()
        }
        val snap = diffs.onToolResult(event.toolUseId)
        // Refresh the VFS NOW, on each successful write — not once at the end of the turn. Until the IDE
        // sees the file on disk it does not exist for it: the editor shows stale contents, and a
        // jump-to-code link on the card resolves to nothing (LocalFileSystem returns null), so clicking it
        // did nothing until the turn finished. Edit/Write refresh exactly the paths they touched; Bash and
        // mutating MCP tools can change anything, so those mark the project tree dirty instead.
        if (!event.isError) {
            diffs.refreshTouched()
            if (ToolNaming.mayHaveWrittenUnknownFiles(transcript.toolNameOf(event.toolUseId))) {
                diffs.refreshProjectTree()
            }
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

    /**
     * End of a turn: fold the counters, retire the quota poll and surface the outcome.
     *
     * A failed turn is not retried and its prompt is not resent automatically — a silent replay re-runs tool
     * calls the user never re-approved. That holds for a renewable access-token expiry too: the row states the
     * turn did not complete and asks for the message to be sent again, instead of raising the sign-in card for
     * an identity that is not missing. Which failures those are is [surfaceAuthFailure]'s decision.
     */
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
            surfaceAuthFailure(message, message)
        } else {
            // A clean turn means we're authenticated; allow a future auth failure to prompt again.
            needsLogin = false
            login.onCleanResult()
            // Count it toward the one-and-only Marketplace review ask. Only successful turns count, so
            // nobody is ever asked to rate a session that was failing on them. See [ReviewPrompt].
            ReviewPrompt.onSuccessfulTurn(project)
        }
        diffs.refreshTouched()
        // The turn edge, which no agent event covers: an agent revived mid-turn and settled again emits
        // nothing on the main stream, and a nested one emits nothing at all. Only re-walking the tree sees it.
        agentScanner.scan()
        fireState()
        pump()
        // The binary's session file is the source of truth for the transcript; we don't persist our own.
        // Once per turn we just record the open-tab set (for restore on startup) and refresh the tab title
        // from the binary's resolved title. Off-EDT: the sidecar JSONL read is blocking IO.
        sessionId?.let { id -> recordOpenAndTitle(id) }
        fireAttention(if (event.result.isError) AttentionReason.ERROR else AttentionReason.TURN_DONE)
    }

    /**
     * The ONE reaction to a failure that might be an authentication failure, shared by both routes that can
     * see one: a failed turn result and an `auth_status` error. Two classifications of the same sentence is
     * what makes a sign-in card appear on one route and not the other for the same event.
     *
     * The decision is [LoginDetection.resolve]'s, and it needs the credential safe to make it: an
     * access-token expiry with a live refresh token is renewed without the user, so it gets an informative
     * row and no card, while the identical wording with nothing left to renew is the end of the identity and
     * must raise it. [AuthGate.canRenewCredential] is a read of the safe and the clock, which is what makes it
     * askable from here — this runs on the EDT.
     *
     * @param failureText what the classification reads: the binary's own message.
     * @param display what the transcript shows when this is a real failure. Never shown for a renewable
     *   expiry, which gets a fixed literal instead: the binary's text can quote a token, and the transcript
     *   carries no credential, ever.
     */
    private fun surfaceAuthFailure(failureText: String, display: String) {
        when (LoginDetection.resolve(failureText, auth::canRenewCredential)) {
            AuthFailure.EXPIRED -> transcript.add(Speaker.SYSTEM, EXPIRED_TOKEN_NOTICE)

            // A missing identity: surface it and raise the sign-in card, since /login cannot run inside the
            // TTY-less stream-json session.
            AuthFailure.NO_IDENTITY -> {
                transcript.add(Speaker.ERROR, display)
                onLoginNeeded()
            }

            AuthFailure.NONE -> transcript.add(Speaker.ERROR, display)
        }
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
                agentScanner.scan()
                if (taskTracker.onStarted(event.info)) fireState()
            }

            is ClaudeEvent.TaskProgress -> edt {
                // Also an admission seed, deliberately: a task_started can be missed (a resumed session
                // reattaches mid-flight), and progress carries the same tool_use_id.
                runningAgents.observeSpawn(event.info.toolUseId)
                // BEFORE the scan, which is what rebuilds the tree from what the registry knows.
                settleFromLifecycle(event.info.toolUseId, event.info.status)
                // The seed alone shows nothing: admission decides what MAY be shown, a scan is what reads the
                // tree. Progress is the only signal a resumed agent emits, so without this its tab never appears.
                agentScanner.scan()
                taskTracker.onProgress(event.info)
                fireState()
            }

            is ClaudeEvent.TaskUpdated -> edt {
                taskTracker.onUpdated(event.info)
                // This message carries no `tool_use_id` — only the task id — so the agent it ends can only be
                // found through what an earlier `task_started`/`task_progress` recorded, which is exactly what
                // the tracker holds. Without this the ONE signal that says `killed` reached the task map and
                // stopped there, and the agent it belonged to stayed running for the rest of the session.
                val ended = settleFromLifecycle(taskTracker.tasks[event.info.taskId]?.toolUseId, event.info.patch.status)
                if (ended) agentScanner.scan()
                fireState()
            }

            is ClaudeEvent.TaskNotification -> edt {
                // The tab KEEPS its transcript and gains a status — reading why an agent failed is the case
                // this feature came from. Only the live task map drops it.
                //
                // Sent on EVERY notification, terminal or not, exactly like the `settle` call below: this
                // signal carries progress as well as endings ([agentStatusOf] maps every live word to
                // RUNNING), and which of the two it is belongs to the registry, not to the caller reading a
                // string. Deciding it here is how the two registries would end up with two rules.
                runningAgents.observeSettled(event.info.toolUseId, agentStatusOf(event.info.status))
                // `output_file` has been modelled since 3.0.0 and never read. It is where the binary writes a
                // background task's output — so with it a task's tab shows what it actually printed, live and
                // after a restart, instead of "this task reported no output".
                backgroundTaskRegistry.observeOutputFile(event.info.taskId, event.info.outputFile)
                // The authoritative ending for a task the level signal never listed — without it the row
                // would stay running for ever, since only the level settles the ones it does list.
                backgroundTaskRegistry.settle(event.info.taskId, event.info.status)
                // One last read BEFORE the poll gives up, because the binary may take the file away once the
                // task has ended: the final chunk is exactly the part the user came to read.
                agentScanner.tailNow()
                agentScanner.scan()
                if (taskTracker.onNotification(event.info)) {
                    // The HEADLINE only. `summary` carries the subagent's entire final answer — headings,
                    // tables, code blocks — and printing it here dumped a whole report into the middle of the
                    // conversation. It is already the last thing in that agent's own transcript, which its
                    // tab reads from the binary's file, so nothing is lost by pointing at it instead.
                    val head = SubagentNotice.headline(event.info.summary)
                    systemNotice("Subagent ${event.info.status}" + (head?.let { ": $it" } ?: ""))
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
                // …and remember it. REPLACE semantics mean a finished task simply stops being listed, which is
                // right for "what is running" and wrong for a tab: its row, its tab and its output all vanished
                // the instant it ended. The registry keeps it, marked finished — the same contract a finished
                // agent's tab already has.
                backgroundTaskRegistry.observeLevel(event.info.tasks)
                ensureOutputTail()
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
            // The same classification a failed turn gets: the binary reports one event through two channels,
            // and a card that appears on one of them only is indistinguishable from a card that is broken.
            edt { surfaceAuthFailure(it, "Authentication error: $it") }
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

    /** Re-reads the agent directory off the EDT and tells the UI — see [AgentScanner]. */
    fun scanAgents() = agentScanner.scan()

    /**
     * Names and states the Agent cards in the transcript, from what the scan found.
     *
     * Stays here rather than in [AgentScanner] because it writes the transcript, which is this class's.
     * Assumes the EDT.
     */
    private fun labelAgentCards() {
        runningAgents.nodes.values.forEach { node ->
            val toolUseId = node.meta.toolUseId ?: return@forEach
            transcript.toolNameOf(toolUseId) ?: return@forEach
            // STATE FIRST, and unconditionally. It used to sit after the description lookup below and share
            // its early return, so an agent the binary never wrote a description for kept whatever state its
            // tool call had — which is exactly how a STOPPED subagent came back green, or fading.
            //
            // The state follows the AGENT, not its tool call: while it works the card fades like any other
            // live call, when it finishes it goes green, and when it was cut off it is red.
            transcript.setToolState(
                toolUseId,
                when (node.status) {
                    AgentStatus.RUNNING -> ToolState.RUNNING
                    AgentStatus.COMPLETED -> ToolState.FINISHED
                    else -> ToolState.ERROR // failed or stopped: it did not finish, and nothing will finish it
                },
            )
            // The card is named after WHAT it is, not after the tool that happened to spawn it: an agent
            // started by another agent reads `Subagent (…)` here exactly as it does in the two diagrams.
            // [AgentNode.kindLabel] is the single place that decides the word.
            val label = node.meta.description?.takeIf { it.isNotBlank() } ?: return@forEach
            transcript.setToolTitle(toolUseId, "${node.kindLabel} ($label)")
        }
    }

    /**
     * A lifecycle status carried by `task_progress` or `task_updated`, applied to the agent ONLY when it ends
     * it. Returns whether it did.
     *
     * Both messages carry the same `status` vocabulary as a notification — `pending | running | completed |
     * failed | killed | paused` — and both were read for the task map and for nothing else. So `killed`, which
     * only ever arrives this way, never reached [AgentRegistry]: the agent it belonged to went on reporting
     * RUNNING, and with it the Task card in the transcript and its row in Workloads, which take their state
     * from the agent.
     *
     * A LIVE status is deliberately not forwarded. [AgentRegistry.observeSettled] treats RUNNING as "unsettle
     * this", so pushing every progress tick through it would undo an ending that had already been observed —
     * and re-opening a genuinely resumed agent is already handled from the evidence that actually says so, its
     * transcript growing past its last finished turn (`AgentRegistry.reopenIfGrown`).
     */
    private fun settleFromLifecycle(toolUseId: String?, status: String?): Boolean {
        if (toolUseId.isNullOrBlank() || status.isNullOrBlank()) return false
        val ending = agentStatusOf(status).takeIf { it != AgentStatus.RUNNING } ?: return false
        runningAgents.observeSettled(toolUseId, ending)
        return true
    }

    /**
     * A task lifecycle status → the agent lifecycle the tab shows.
     *
     * **Both halves are named, and the fallback is the harmful-if-wrong one on purpose.** It first sent
     * everything that was not `completed`/`failed` to STOPPED, which swept up every LIVE word the binary emits
     * — `started`, `running`, `in_progress` — and once STOPPED became red, an agent that had just been
     * launched was drawn as a dead one. Inverting that made the opposite mistake available: with only the
     * endings named and `else -> RUNNING`, a status this build has never heard of reads as live work, and an
     * agent stuck on RUNNING is *invisible* — it never settles, never leaves the Workloads window (which
     * exempts running work by design) and never turns its Task card green or red.
     *
     * So the live words are an allowlist too, and anything outside both lists is [AgentStatus.FAILED]. An
     * unknown status means the binary has stopped saying something we understand about a piece of work; a red
     * row is wrong loudly, and a permanently spinning one is wrong silently.
     *
     * [AgentStatus.RUNNING] is also this function's way of saying "not an ending", and
     * [AgentRegistry.observeSettled] reads it as exactly that: it records the liveness and seals no instant.
     */
    private fun agentStatusOf(status: String): AgentStatus = when (status.lowercase()) {
        "completed", "complete", "done", "finished", "success", "succeeded" -> AgentStatus.COMPLETED

        // Still working, or not started yet — `paused` among them: paused work has not finished and nothing
        // about it failed, and the thing that resumes it is the same binary that paused it.
        "", "running", "in_progress", "in-progress", "started", "starting", "pending", "queued", "paused",
        -> AgentStatus.RUNNING

        // Ended without finishing. Red, like a failure: from the outside the work did not get done and
        // nothing is going to do it.
        "stopped", "cancelled", "canceled", "interrupted", "aborted", "killed" -> AgentStatus.STOPPED

        else -> AgentStatus.FAILED
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
            // Names no file any more. The configuration used to be a per-project `claude-code.xml`, and since
            // 5.5.0 it lives in the IDE's own secret store — but it can still have ARRIVED from a repository,
            // because a committed legacy file is adopted on first run. What the user has to judge is the same
            // either way: code is about to run on their machine because of the project they just opened.
            "This project is configured to run an environment script and/or a custom MCP server when a Claude " +
                "Code session starts. These execute code on your machine. Only allow this if you trust this " +
                "project. Run them?",
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
        // Stop the shared timers so the disposed session leaks no EDT timer.
        quotaPollTimer.stop()
        outputTailTimer.stop()
        agentRevivalTimer.stop()
        // Default-cancel any pending MCP elicitation cards while the process is still alive (mirrors stop()).
        cancelPendingElicitations()
        diffs.clearReviewDiffs()
        // EOF then destroy the tree, and NOT on this thread. `dispose()` is what a closed tab runs
        // ([ChatTabsPanel.close] → [ChatSessionManager.remove]), i.e. the EDT, and killing a process tree
        // there is what made closing a chat freeze the IDE — see [ClaudeProcess.terminate]. Same one door as
        // stop(), so the order can no longer be spelled out twice and drift.
        process?.terminate()
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

        /**
         * What the transcript says when a turn fails on an expired access token that the safe can still renew.
         *
         * **It names the one exit that exists, because the obvious one does not work.** The renewal happens in
         * [launch], before the launch environment is built: the credential reaches the binary as
         * `CLAUDE_CODE_OAUTH_TOKEN` in the process environment, which is fixed at spawn and immutable
         * thereafter. So a running session cannot heal — and the binary will not heal it either, since with a
         * token supplied by the environment it deliberately keeps that one rather than adopting the renewed
         * credential from its own store. Re-sending the message therefore fails again, identically, for as long
         * as the process lives.
         *
         * An earlier wording promised that "Claude Code renews it automatically and the session continues",
         * which was true of no code path and left the user re-sending into the same failure indefinitely.
         *
         * It is a fixed literal rather than the binary's own text on purpose: that text can quote a token, and
         * the transcript carries no credential, ever.
         */
        const val EXPIRED_TOKEN_NOTICE =
            "Your access token expired while this chat was open. The sign-in itself is still valid and is " +
                "renewed when a session starts, but a running one cannot pick up the new token — so this turn " +
                "did not complete, and sending it again will fail the same way. Close this chat and open it " +
                "again to continue."

        /**
         * What a `/btw` gets when the binary refuses it, answers nothing, or never answers at all.
         *
         * A question with no reply under it reads as a question the plugin forgot about, which is precisely the
         * defect this path was rewritten to end. A stated non-answer is a worse answer and a better transcript.
         */
        const val SIDE_QUESTION_UNANSWERED = "↩ The side question was not answered."

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

        /** Interval (ms) of the agent-revival rescan — see [agentRevivalTimer].
         *
         *  Five seconds, not one: a pass re-parses every admitted agent's transcript, and a session that ran
         *  dozens of agents is exactly the one this feature exists for, so the cost scales with the worst case.
         *  A revived agent takes seconds to produce anything a user could read, so five is below the point where
         *  the tab's status could be told apart from instant — and the gate keeps the timer off a chat that is
         *  idle or has nothing settled, which is the majority of a session's wall time. */
        const val AGENT_REVIVAL_POLL_MS = 5_000

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

            // By TIER, not by position. `models.first()` looked harmless and was the bug the user hit: the
            // catalogue's order is the binary's, not a ranking, so a session whose catalogue did not carry
            // the pinned id silently landed on whatever happened to be listed first — Haiku — and the
            // composer then showed a model nobody had chosen. Falling to the strongest tier the binary DOES
            // offer is the only fallback that cannot surprise, and it stays inside what the binary listed.
            else -> TIER_ORDER.firstNotNullOfOrNull { tier ->
                models.firstOrNull { it.value.contains(tier, ignoreCase = true) }?.value
            } ?: models.first().value
        }

        /** Strongest first. Matched against the id the binary lists, never against a hardcoded model id. */
        private val TIER_ORDER = listOf("opus", "sonnet", "haiku")

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

        // How a tool call is NAMED — the label, its linkable path and which tools force a VFS rescan — lives in
        // [ToolNaming]. It is pure and shared with the on-disk restore, so the live stream and a resumed session
        // cannot label the same call differently.
    }
}
