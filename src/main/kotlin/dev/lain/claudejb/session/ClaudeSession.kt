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
import dev.lain.claudejb.permission.SecurityRule
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
import dev.lain.claudejb.settings.GuardAlert
import dev.lain.claudejb.settings.GuardAlertLog
import dev.lain.claudejb.settings.GuardCommandApprovals
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.guardSuspended
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

class ClaudeSession(
    private val project: Project,
    @Volatile var title: String,
    val gitIntegration: Boolean = false,
) : Disposable {

    private val log = thisLogger()

    val transcript = TranscriptModel()

    private val tokens = TokenAccountant()
    private val taskTracker = TaskTracker()
    private val reconciler = TranscriptReconciler(transcript)

    internal val diffs = DiffLifecycleManager(project)
    private val rollback = RollbackManager(project, diffs, reseedReadState = { p, m -> queries.seedReadState(p, m) })
    internal val controlClient = SessionControlClient(write = ::write)

    val settings = SessionLiveSettings(
        session = this,
        project = project,
        edt = ::edt,
        fireState = ::fireState,
        write = ::write,
    )

    val queries = SessionQueries(
        controlClient = controlClient,
        isRunning = ::isRunning,
        edt = ::edt,
        write = ::write,
        quota = QuotaWarnings(log, QuotaWarnings.Announce(inTranscript = ::systemNotice, asNotification = ::notifyInfo)),
    )

    private val titling = SessionTitling(
        currentTitle = { title },
        setTitle = { title = it },
        fireTitleChanged = { edt { fireTitleChanged() } },
        requestGeneratedTitle = queries::requestGeneratedTitle,
    )

    private val notices = NoticeNarrator(
        log = log,
        systemNotice = ::systemNotice,
        addRow = { speaker, text, meta -> transcript.add(speaker, text, meta = meta) },
        notifyInfo = ::notifyInfo,
        edt = ::edt,
    )

    internal val cardManager = PermissionCardManager(::firePermissions)

    val cards = SessionCards(
        session = this,
        edt = ::edt,
        write = ::write,
        firePermissions = ::firePermissions,
        fireAttention = ::fireAttention,
    )
    private val hookBroker = HookBroker()
    private val hookNarrator = HookActivityNarrator(transcript)

    val login = LoginCoordinator(
        project,
        edt = ::edt,
        notifyInfo = ::notifyInfo,
        notifyError = ::notifyError,
        notifyMissingBinary = ::notifyMissingBinary,
        restartSession = { restart() },
    )

    @Volatile var sessionId: String? = null
        internal set

    @Volatile var model: String? = null
        internal set

    @Volatile var effort: String? = null
        internal set

    @Volatile var permissionMode: String = "default"
        internal set

    @Volatile var thinkingTokens: Int? = null
        internal set

    @Volatile var allowedTools: String = ""
        internal set

    @Volatile var disallowedTools: String = ""
        internal set

    @Volatile var settingSources: String = "user,project,local"
        internal set

    @Volatile var ideMcpEnabled: Boolean = false
        internal set

    @Volatile var ideMcpTransport: String = "sse"
        internal set

    @Volatile var ideMcpPort: Int = DEFAULT_IDE_MCP_PORT
        internal set

    @Volatile var customMcpServers: String = ""
        internal set

    @Volatile var includePartialMessages: Boolean = true
        internal set

    @Volatile var maxTurns: Int? = null
        internal set

    @Volatile var maxBudgetUsd: Double? = null
        internal set

    @Volatile var fallbackModel: String? = null
        internal set

    @Volatile var addDirs: List<String> = emptyList()
        internal set

    @Volatile var betas: String? = null
        internal set

    @Volatile var strictMcpConfig: Boolean = false
        internal set

    @Volatile var outputStyle: String = "default"
        private set

    @Volatile var turnActive: Boolean = false
        private set

    @Volatile var interrupting: Boolean = false
        private set

    @Volatile var rateLimit: RateLimitInfo? = null
        private set

    @Volatile var rateLimits: Map<String, RateLimitInfo> = emptyMap()
        private set

    @Volatile var sessionState: String? = null
        private set

    @Volatile var authStatus: AuthStatusInfo? = null
        private set

    @Volatile var liveThinkingTokens: Int = 0
        private set

    @Volatile var promptSuggestion: String? = null
        private set

    val subagentTasks: Map<String, TaskProgressInfo> get() = taskTracker.tasks

    val runningAgents = AgentRegistry(subagentsDir = { sessionId?.let { SessionStore.subagentsDir(it) } })

    val backgroundTaskRegistry = BackgroundTaskRegistry()

    private val agentScanner: AgentScanner = AgentScanner(
        project = project,
        agents = runningAgents,
        tasks = backgroundTaskRegistry,
        sessionId = { sessionId },
        ownerOfTask = ::ownerAgentOfTask,
        ui = object : AgentScanner.Ui {
            override fun labelCards() {
                labelAgentCards()
                poll.ensureAgentRevivalPoll()
            }
            override fun onFresh(fresh: List<String>) = fireAgents(fresh)
            override fun onOutputGrew() = fireState()
            override fun edt(block: () -> Unit) = this@ClaudeSession.edt(block)
        },
    )

    fun ownerAgentOfTask(taskId: String): String? {
        val fromLink = backgroundTaskRegistry.taskOf(taskId)?.ownerToolUseId
        val fromEdge = subagentTasks[taskId]?.toolUseId
        val tool = fromLink ?: fromEdge ?: return null
        return runningAgents.nodes.values.firstOrNull { it.meta.toolUseId == tool }?.agentId
    }

    val backgroundTasks: List<dev.lain.claudejb.protocol.BackgroundTaskInfo> get() = taskTracker.backgroundTasks

    val liveInputTokens get() = tokens.liveInputTokens
    val liveCacheCreationTokens get() = tokens.liveCacheCreationTokens
    val liveCacheReadTokens get() = tokens.liveCacheReadTokens
    val liveOutputTokens get() = tokens.liveOutputTokens

    val sessionInputTokens get() = tokens.sessionInputTokens
    val sessionCacheCreationTokens get() = tokens.sessionCacheCreationTokens
    val sessionCacheReadTokens get() = tokens.sessionCacheReadTokens
    val sessionOutputTokens get() = tokens.sessionOutputTokens

    fun totalTokens(): Int = tokens.totalTokens()

    @Volatile private var ready = false

    private val deltaLock = Any()
    private val deltaRuns = ArrayList<Pair<Boolean, StringBuilder>>()

    private var pendingUsage: IntArray? = null

    private fun bufferDelta(isThinking: Boolean, text: String) = synchronized(deltaLock) {
        val last = deltaRuns.lastOrNull()
        if (last != null && last.first == isThinking) {
            last.second.append(text)
        } else {
            deltaRuns.add(isThinking to StringBuilder(text))
        }
    }

    private fun bufferUsage(input: Int, cacheCreation: Int, cacheRead: Int, output: Int) = synchronized(deltaLock) {
        pendingUsage = intArrayOf(input, cacheCreation, cacheRead, output)
    }

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

    @Volatile internal var cachedEnv: Map<String, String>? = null

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
    var remoteControlEnabled: Boolean = false
        private set

    @Volatile private var process: ClaudeProcess? = null

    @Volatile private var generation = 0

    @Volatile private var starting = false

    private val queue = ArrayDeque<Outgoing>()

    private data class Outgoing(val text: String, val images: List<Pair<String, String>>, val displayText: String)

    private val listeners = CopyOnWriteArrayList<SessionListener>()

    @Volatile var lastSessionCost: JsonObject? = null
        private set

    @Volatile var lastContextUsage: ContextUsage? = null
        private set

    val workingDir: String? get() = project.basePath

    @Volatile var binaryVersion: String? = null

    @Volatile var currentUserMessageId: String? = null
        private set
    private val toolUseTurn = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun userMessageIdFor(toolUseId: String): String? = toolUseTurn[toolUseId]

    val checkpointingEnabled: Boolean get() = ClaudeSettings.getInstance(project).enableFileCheckpointing

    val guardEnforced: Boolean get() = !ClaudeSettings.getInstance(project).guardSuspended()

    private val poll = PollSchedule(
        isRunning = ::isRunning,
        turnActive = { turnActive },
        effects = PollSchedule.SessionEffects(edt = ::edt, fireState = ::fireState),
        quota = PollSchedule.QuotaSource(
            requestSessionCost = queries::requestSessionCost,
            requestContextUsage = queries::requestContextUsage,
            onSessionCost = { lastSessionCost = it },
            onContextUsage = { lastContextUsage = it },
        ),
        outputTail = PollSchedule.OutputTailSource(
            anyTailable = { backgroundTaskRegistry.anyTailable },
            tailNow = { agentScanner.tailNow() },
        ),
        agentRevival = PollSchedule.AgentRevivalSource(
            anySettledAgent = { runningAgents.nodes.values.any { it.status != AgentStatus.RUNNING } },
            anyRunningAgent = { runningAgents.nodes.values.any { it.status == AgentStatus.RUNNING } },
            scanAgents = { agentScanner.scan() },
        ),
    )

    @Volatile
    var initialized: Boolean = false
        private set

    val guardApprovals = GuardCommandApprovals()

    val guardLog = GuardLogTally()

    private val guardAlerts = java.util.concurrent.CopyOnWriteArrayList<GuardAlert>()

    private val broker by lazy {
        PermissionBroker(
            permissionMode = { permissionMode },
            respond = ::write,
            onApprovedWrite = { diffs.markForRefresh(it) },
            present = ::presentPermission,
            onAutoReviewed = diffs::autoOpenDiff,
            projectRoot = project.basePath,
            isRemembered = { toolName, input -> ClaudeSettings.getInstance(project).isToolAlwaysAllowed(toolName, input) },
            forceAsk = { gitIntegration },
            sensitiveDecision = { input ->
                ClaudeSettings.getInstance(project).sensitiveDecision(input, project.basePath)
            },
            isGuardCommandApproved = { rule, command -> guardApprovals.isApproved(rule, command) },
            onSensitiveDenied = { denial ->
                edt {
                    val landing = guardLandingOf(denial.toolUseId)
                    if (landing == AttentionLanding.Chat) {
                        transcript.add(
                            Speaker.SYSTEM,
                            denial.reason?.let { "Blocked ${denial.toolName}: it $it." }
                                ?: "Blocked ${denial.toolName} by the sensitive-data guard. " +
                                "See Settings ▸ Claude Code Security.",
                            commandText = denial.command?.takeIf { it.isNotBlank() },
                            blockedRule = denial.rule?.name,
                        )
                    }
                    fireAttention(AttentionReason.GUARD_BLOCKED, landing)
                    recordAlert(
                        GuardAlert.DENIED,
                        denial.rule,
                        denial.toolName,
                        command = denial.command,
                        toolUseId = denial.toolUseId,
                        detail = denial.detail,
                        inAgent = landing != AttentionLanding.Chat,
                    )
                    fireState()
                }
            },
            onSensitiveBypassed = { bypass ->
                val offer = bypass.action ?: if (ClaudeSettings.getInstance(project).guardSuspended()) {
                    PermissionBroker.ENABLE_GUARD
                } else {
                    PermissionBroker.REMOVE_FROM_WHITELIST
                }
                guardNotice(
                    bypass.toolName,
                    bypass.reason ?: "${bypass.rule.label} matched, and a bypass is in force",
                    bypass.rule,
                    offer,
                    bypass.command,
                    bypass.toolUseId,
                )
                edt {
                    recordAlert(
                        GuardAlert.ALLOWED,
                        bypass.rule,
                        bypass.toolName,
                        via = offer,
                        command = bypass.command,
                        toolUseId = bypass.toolUseId,
                        detail = bypass.detail,
                        inAgent = guardLandingOf(bypass.toolUseId) != AttentionLanding.Chat,
                    )
                }
            },
        )
    }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
        edt { poll.pollQuota() }
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
        edt { if (listeners.isEmpty() && poll.quotaRunning) poll.stopQuota() }
    }

    fun isRunning(): Boolean = process?.isRunning() == true

    fun isStarting(): Boolean = starting

    fun queuedPrompts(): List<String> = queue.map { it.displayText }

    fun start(resume: Boolean = sessionId != null): Boolean {
        if (isRunning() || starting) return true
        val settings = ClaudeSettings.getInstance(project)
        val binary = resolveBinary(settings) ?: return false
        if (!passesLaunchGates(settings)) return false
        when (auth.heldCredential(settings)) {
            Credential.NONE -> {
                onLoginNeeded()
                return false
            }

            Credential.UNKNOWN -> return true

            Credential.HELD -> Unit
        }
        val workDir = project.basePath?.let(::File) ?: File(System.getProperty("user.home"))

        ready = false
        initialized = false
        starting = true
        reconciler.onMessageBoundary()
        fireState()
        val launchGen = ++generation

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launch(launchGen, settings, binary, workDir, resume)
            } finally {
                if (launchGen == generation) {
                    starting = false
                    edt { fireState() }
                }
            }
        }
        return true
    }

    @Volatile
    var binaryMissing: Boolean = false
        private set

    @Volatile
    var needsLogin: Boolean = false
        private set

    private fun onLoginNeeded() {
        needsLogin = true
        edt { fireState() }
        login.maybePrompt()
    }

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

                else -> edt { fireState() }
            }
        },
    )

    @Volatile
    private var resumedLaunch = false

    fun refreshBootState() {
        if (starting) return
        if (login.inProgress) return
        auth.absorbExistingLoginOnce()
        val settings = ClaudeSettings.getInstance(project)
        val binary = ClaudeBinaryLocator.locate(settings.claudePath)
        val missing = binary == null
        edt {
            if (missing && isRunning()) stop()
            if (missing != binaryMissing) {
                binaryMissing = missing
                fireState()
            }
        }
        if (binary == null) return
        if (settings.claudePath != binary.absolutePath) {
            settings.update { it.claudePath = binary.absolutePath }
        }
        val credentialed = auth.hasCredential(settings)
        edt {
            if (starting) return@edt
            when {
                !credentialed -> {
                    if (isRunning()) stop()
                    if (!needsLogin) onLoginNeeded()
                }

                !isRunning() -> start()
            }
        }
    }

    fun dismissLoginCard() {
        needsLogin = false
        edt { fireState() }
    }

    private fun resolveBinary(settings: ClaudeSettings): File? {
        val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: run {
            binaryMissing = true
            fireState()
            notifyMissingBinary()
            return null
        }
        binaryMissing = false
        if (settings.claudePath != binary.absolutePath) {
            settings.update { it.claudePath = binary.absolutePath }
        }
        return binary
    }

    private fun passesLaunchGates(settings: ClaudeSettings): Boolean {
        if (!ensureExecTrust(settings)) return false
        if (RemoteMounts.isRemote(project.basePath)) {
            refuseRemoteProject(project.basePath)
            return false
        }
        return true
    }

    internal fun effectiveLaunchEnv(base: Map<String, String>? = null): Map<String, String> {
        val env = base ?: ClaudeSettings.getInstance(project).resolveEnv()
        val settings = ClaudeSettings.getInstance(project)
        val apiKey = settings.anthropicApiKey
            .takeIf { it.isNotBlank() && settings.provider == Provider.ANTHROPIC && SecretStore.API_KEY !in env }
        val withSecrets = env +
            SecretStore.envOverlay(env.keys) +
            (apiKey?.let { mapOf(SecretStore.API_KEY to it) } ?: emptyMap())
        return withSecrets + dev.lain.claudejb.process.CredentialsVault.envOverlay(withSecrets.keys)
    }

    private fun launch(launchGen: Int, settings: ClaudeSettings, binary: File, workDir: File, resume: Boolean) {
        if (!auth.renew(binary, settings)) {
            edt { onLoginNeeded() }
            return
        }
        val env = effectiveLaunchEnv(cachedEnv ?: settings.resolveEnv().also { cachedEnv = it })
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
        val started = runCatching { proc.start() }
        if (started.isFailure) {
            process = null
            log.warn("Failed to start the claude process", started.exceptionOrNull())
            notifyError("Failed to start Claude Code: ${started.exceptionOrNull()?.message ?: "unknown error"}")
            return
        }
        if (launchGen != generation) {
            proc.terminate()
            if (process === proc) process = null
            return
        }
        requestInitialize()
        edt {
            ready = true
            transcript.add(Speaker.SYSTEM, "Claude Code ready.")
            auth.probe()
            fireState()
            poll.pollQuota()
            pump()
        }
    }

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
                initialized = true
                if (info.outputStyle.isNotBlank()) outputStyle = info.outputStyle
                val pinMissing = info.models.isNotEmpty() && info.models.none { it.value == DEFAULT_MODEL }
                if (model == DEFAULT_MODEL && pinMissing) settings.changeModel(preferredDefault(info.models))
                edt { fireMetadata() }
            },
        )
    }

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

    fun restart(resume: Boolean = true) {
        stop()
        start(resume)
    }

    fun stop() {
        generation++
        flushDeltas()
        cancelPendingElicitations()
        process?.terminate()
        process = null
        turnActive = false
        interrupting = false
        ready = false
        initialized = false
        starting = false
        liveThinkingTokens = 0
        promptSuggestion = null
        cachedEnv = null
        controlClient.failAll("process gone")
        taskTracker.clear()
        hookNarrator.clear()
        backgroundTaskRegistry.clear()
        agentScanner.clearTails()
        edt {
            cardManager.clear()
            diffs.clearReviewDiffs()
            fireState()
        }
    }

    fun send(text: String) = send(text, emptyList())

    fun send(text: String, attachments: List<Attachment>) {
        val composed = PromptComposer.compose(text, attachments, project.basePath) ?: return
        if (!isRunning()) {
            if (!start()) return
        }
        edt {
            queue.addLast(Outgoing(composed.wireText, composed.images, composed.displayText))
            fireState()
            pump()
        }
    }

    fun sendSideQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!isRunning()) {
            if (!start()) return
            edt {
                queue.addLast(Outgoing(trimmed, emptyList(), trimmed))
                fireState()
                pump()
            }
            return
        }
        edt {
            transcript.add(Speaker.USER, "↪ $trimmed")
            queries.askSideQuestion(trimmed) { answer ->
                transcript.add(Speaker.SYSTEM, answer?.let { "↩ $it" } ?: SIDE_QUESTION_UNANSWERED)
            }
            pump()
        }
    }

    fun setRemoteControl(enabled: Boolean, onSettled: () -> Unit) {
        queries.setRemoteControl(enabled) { outcome ->
            if (outcome.ok) {
                remoteControlEnabled = outcome.enabled
                fireState()
            }
            transcript.add(Speaker.SYSTEM, remoteControlNotice(outcome))
            onSettled()
        }
    }

    private fun remoteControlNotice(outcome: RemoteControlOutcome): String = when {
        !outcome.ok -> {
            val what = if (outcome.enabled) "switched on" else "switched off"
            "Remote Control could not be $what" + (outcome.error?.let { ": $it" } ?: ".")
        }

        !outcome.enabled -> "Remote Control is off. This chat keeps running in the IDE."

        outcome.sessionUrl != null -> "Remote Control is on — ${outcome.sessionUrl}"

        else -> "Remote Control is on. The session is listed at https://claude.ai/code"
    }

    fun removeQueued(index: Int) = edt {
        if (index in queue.indices) {
            val copy = queue.toMutableList()
            copy.removeAt(index)
            queue.clear()
            queue.addAll(copy)
            fireState()
        }
    }

    private fun pump() {
        if (!ready || queue.isEmpty() || !isRunning()) return
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            transcript.add(Speaker.USER, next.displayText)
            val msgUuid = java.util.UUID.randomUUID().toString()
            currentUserMessageId = msgUuid
            write(ControlProtocol.userMessageWithImages(next.text, next.images, uuid = msgUuid))
            turnActive = true
            poll.startQuotaPolling()
            poll.ensureAgentRevivalPoll()
        }
        promptSuggestion = null
        fireState()
    }

    fun clearSuggestion() {
        if (promptSuggestion == null) return
        promptSuggestion = null
        edt { fireState() }
    }

    fun interrupt() {
        if (!isRunning()) return
        edt {
            if (interrupting) return@edt
            cancelPendingElicitations()
            cardManager.all().filter { it.elicitation == null }.forEach {
                write(ControlProtocol.permissionDeny(it.requestId, "Interrupted."))
            }
            queue.clear()
            cardManager.clear()
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

    private fun finishInterrupt() {
        interrupting = false
        turnActive = false
        liveThinkingTokens = 0
        poll.pollQuota()
        fireState()
    }

    private fun recordAlert(
        verdict: String,
        rule: SecurityRule?,
        toolName: String,
        via: String? = null,
        command: String? = null,
        toolUseId: String? = null,
        detail: String? = null,
        inAgent: Boolean = false,
    ) {
        val matched = rule ?: return
        val settings = ClaudeSettings.getInstance(project)
        val alert = GuardAlert(
            at = System.currentTimeMillis(),
            rule = matched.name,
            category = matched.category.name,
            verdict = verdict,
            sessionId = sessionId,
            toolUseId = toolUseId,
            via = via,
            tool = toolName,
            detail = detail,
            command = command,
            inAgent = inAgent,
        )
        guardAlerts += alert
        val submitted = GuardAlertLog.record(settings.scope, alert, retentionDays = settings.state.guardLogRetentionDays)
        guardLog.submitted(submitted != null)
    }

    private fun presentPermission(request: PendingPermission) = edt {
        request.guard?.let {
            recordAlert(
                GuardAlert.ASKED,
                it.rule,
                request.toolName,
                command = ToolInputScanner.commandText(request.input),
                toolUseId = request.toolUseId,
                detail = it.reason,
            )
        }
        cards.present(request)
        if (request.reviewable && request.toolName in DiffPresenter.REVIEWABLE_TOOLS) {
            diffs.openReviewDiff(request.requestId, request.toolName, request.input)
        }
        fireAttention(AttentionReason.PERMISSION)
    }

    fun editSnapshot(toolUseId: String): EditSnapshot? = diffs.snapshot(toolUseId)

    fun restore(savedSessionId: String, dtos: List<EntryDTO>) {
        sessionId = savedSessionId
        agentScanner.restoreAdmitted(onTasksReplayed = ::fireState)
        toolUseTurn.clear()
        currentUserMessageId = null
        val saved = GuardAlertLog.forSession(ClaudeSettings.getInstance(project).scope, savedSessionId)
        guardAlerts.addAll(saved)
        val withGuard = GuardRestore.reinstate(dtos, GuardRestore.raisedInThisChat(dtos, saved))
        edt {
            transcript.clear()
            for (dto in withGuard) {
                val speaker = runCatching { Speaker.valueOf(dto.speaker) }.getOrNull() ?: continue
                transcript.add(
                    speaker,
                    dto.text,
                    meta = dto.meta,
                    toolUseId = dto.toolUseId,
                    parentToolUseId = dto.parentToolUseId,
                    filePath = dto.filePath,
                    commandText = dto.commandText,
                    messageText = dto.messageText,
                    blockedRule = dto.blockedRule,
                    bypassedRule = dto.bypassedRule,
                    bypassAction = dto.bypassAction,
                    toolState = when {
                        dto.failed -> ToolState.ERROR
                        dto.inFlight -> ToolState.ERROR
                        dto.meta == "Task" || dto.meta == "Agent" -> ToolState.ERROR
                        else -> ToolState.FINISHED
                    },
                )
            }
        }
    }

    private fun recordOpenAndTitle(id: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            if (!gitIntegration) titling.resolve(id)
            SessionHistory.getInstance(project).setOpenSessions(
                ChatSessionManager.getInstance(project).all()
                    .filterNot { it.gitIntegration }
                    .mapNotNull { it.sessionId },
            )
        }
    }

    fun pendingPermissions(): List<PendingPermission> = cards.pending()

    fun resolvePermission(
        requestId: String,
        allow: Boolean,
        denyMessage: String? = null,
        overrideInput: JsonObject? = null,
    ) = cards.resolvePermission(requestId, allow, denyMessage, overrideInput)

    val provider: Provider get() = ClaudeSettings.getInstance(project).provider

    fun refreshAfterRewind(paths: List<String>) {
        paths.forEach { diffs.markForRefresh(it) }
        diffs.refreshTouched()
    }

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

    fun renameSession(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        if (isRunning()) write(ControlProtocol.renameSessionRequest(ControlProtocol.newRequestId(), trimmed))
        titling.markRenamed()
        this.title = trimmed
        edt { fireTitleChanged() }
    }

    @org.jetbrains.annotations.TestOnly
    fun handleEventForTest(event: ClaudeEvent) {
        onEvent(event)
        flushDeltas()
    }

    private fun onEvent(event: ClaudeEvent) {
        if (event is ClaudeEvent.Stream) {
            bufferStream(event)
            return
        }
        flushDeltas()
        when (event) {
            is ClaudeEvent.Conversation -> onConversation(event)
            is ClaudeEvent.Control -> onControl(event)
            is ClaudeEvent.Task -> onTask(event)
            is ClaudeEvent.SessionSignal -> onSessionSignal(event)
            is ClaudeEvent.HookTelemetry -> onHookTelemetry(event)
            is ClaudeEvent.Notice -> notices.onNotice(event)
            is ClaudeEvent.Stream -> {}
        }
    }

    private fun bufferStream(event: ClaudeEvent.Stream) {
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

    private fun onConversation(event: ClaudeEvent.Conversation) {
        when (event) {
            is ClaudeEvent.Init -> onInit(event)

            is ClaudeEvent.ToolUse -> onToolUse(event)

            is ClaudeEvent.ToolResult -> onToolResult(event)

            is ClaudeEvent.Result -> onTurnResult(event)

            is ClaudeEvent.AssistantThinking -> edt {
                reconciler.finalizeThinking(event.text, event.parentToolUseId)
            }

            is ClaudeEvent.MessageStart -> edt {
                tokens.foldIntoSession()
                liveThinkingTokens = 0
                reconciler.onMessageBoundary()
            }

            is ClaudeEvent.LocalCommandOutput -> edt {
                if (event.content.isNotBlank()) transcript.add(Speaker.SYSTEM, event.content)
            }

            is ClaudeEvent.AssistantText -> edt {
                reconciler.finalizeAssistant(event.text, event.parentToolUseId)
            }
        }
    }

    private fun onInit(event: ClaudeEvent.Init) {
        sessionId = event.info.sessionId
        agentScanner.restoreAdmitted(onTasksReplayed = ::fireState)
        if (model == null && event.info.model.isNotBlank()) model = event.info.model
        if (event.info.outputStyle.isNotBlank()) outputStyle = event.info.outputStyle
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
            toolState = ToolState.LOADING,
            filePath = ToolNaming.toolFilePath(event.name, event.input, workingDir),
            commandText = ToolInputScanner.commandText(event.input),
            messageText = ToolInputScanner.messageText(event.input),
        )
        if (event.name in DiffPresenter.REVIEWABLE_TOOLS) {
            diffs.captureForReview(event.name, event.input, event.id)
            currentUserMessageId?.let { toolUseTurn[event.id] = it }
        }
    }

    private fun onToolResult(event: ClaudeEvent.ToolResult) = edt {
        if (runningAgents.nodes.values.none { it.meta.toolUseId == event.toolUseId }) {
            transcript.setToolState(
                event.toolUseId,
                if (event.isError) ToolState.ERROR else ToolState.FINISHED,
            )
        }
        if (backgroundTaskRegistry.observe(event)) {
            poll.ensureOutputTail()
            fireState()
        }
        val snap = diffs.onToolResult(event.toolUseId)
        if (!event.isError) {
            diffs.refreshTouched()
            if (ToolNaming.mayHaveWrittenUnknownFiles(transcript.toolNameOf(event.toolUseId))) {
                diffs.refreshProjectTree()
            }
        }
        val diff = if (snap != null && snap.toolName in DiffPresenter.REVIEWABLE_TOOLS) {
            DiffPresenter.proposedContent(snap.toolName, snap.input, snap.beforeText)
                ?.let { DiffPresenter.unifiedDiff(snap.beforeText, it) }
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (event.parentToolUseId != null) return@edt
        if (diff != null) {
            transcript.addToolOutput(event.toolUseId, diff, parentToolUseId = event.parentToolUseId, meta = "diff")
        } else {
            val text = event.content.trim()
            if (text.isNotBlank()) {
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
        poll.pollQuota()
        interrupting = false
        liveThinkingTokens = 0
        if (event.result.isError) {
            val message = event.result.result.ifBlank {
                event.result.errors.joinToString("\n").ifBlank { "Turn ended with error: ${event.result.subtype}" }
            }
            surfaceAuthFailure(message, message)
        } else {
            needsLogin = false
            login.onCleanResult()
            ReviewPrompt.onSuccessfulTurn(project)
        }
        diffs.refreshTouched()
        agentScanner.scan()
        fireState()
        pump()
        sessionId?.let { id -> recordOpenAndTitle(id) }
        fireAttention(if (event.result.isError) AttentionReason.ERROR else AttentionReason.TURN_DONE)
    }

    private fun surfaceAuthFailure(failureText: String, display: String) {
        when (LoginDetection.resolve(failureText, auth::canRenewCredential)) {
            AuthFailure.EXPIRED -> {
                transcript.add(Speaker.SYSTEM, EXPIRED_TOKEN_NOTICE)
                renewRejectedCredential()
            }

            AuthFailure.NO_IDENTITY -> {
                transcript.add(Speaker.ERROR, display)
                onLoginNeeded()
            }

            AuthFailure.NONE -> transcript.add(Speaker.ERROR, display)
        }
    }

    private fun renewRejectedCredential() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = ClaudeSettings.getInstance(project)
            val binary = ClaudeBinaryLocator.locate(settings.claudePath) ?: return@executeOnPooledThread
            if (!auth.renewRejected(binary, settings)) return@executeOnPooledThread
            log.info("the rejected credential was renewed; restarting the session on the new one")
            edt { restart() }
        }
    }

    private fun onControl(event: ClaudeEvent.Control) {
        when (event) {
            is ClaudeEvent.PermissionRequest -> broker.handle(event.requestId, event.request)

            is ClaudeEvent.HookCallback -> handleHookCallback(event.requestId, event.request)

            is ClaudeEvent.UserDialogRequest -> {
                write(DialogResponder.response(event.requestId))
                systemNotice(DialogResponder.notice(event.dialogKind))
            }

            is ClaudeEvent.Elicitation -> cards.presentElicitation(event.requestId, event.request)

            is ClaudeEvent.UnsupportedControlRequest -> broker.rejectUnsupported(event.requestId, event.subtype)

            is ClaudeEvent.ControlResult -> controlClient.onControlResult(event)
        }
    }

    private fun onTask(event: ClaudeEvent.Task) {
        when (event) {
            is ClaudeEvent.TaskStarted -> edt {
                runningAgents.observeSpawn(event.info.toolUseId)
                agentScanner.scan()
                if (taskTracker.onStarted(event.info)) fireState()
            }

            is ClaudeEvent.TaskProgress -> edt {
                runningAgents.observeSpawn(event.info.toolUseId)
                settleFromLifecycle(event.info.toolUseId, event.info.status)
                agentScanner.scan()
                taskTracker.onProgress(event.info)
                fireState()
            }

            is ClaudeEvent.TaskUpdated -> edt {
                taskTracker.onUpdated(event.info)
                val ended = settleFromLifecycle(taskTracker.tasks[event.info.taskId]?.toolUseId, event.info.patch.status)
                if (ended) agentScanner.scan()
                fireState()
            }

            is ClaudeEvent.TaskNotification -> edt {
                runningAgents.observeSettled(event.info.toolUseId, agentStatusOf(event.info.status))
                backgroundTaskRegistry.observeOutputFile(event.info.taskId, event.info.outputFile)
                backgroundTaskRegistry.settle(event.info.taskId, event.info.status)
                agentScanner.tailNow()
                agentScanner.scan()
                if (taskTracker.onNotification(event.info)) {
                    val head = SubagentNotice.headline(event.info.summary)
                    systemNotice("Subagent ${event.info.status}" + (head?.let { ": $it" } ?: ""))
                }
                fireState()
            }

            is ClaudeEvent.ToolProgress -> edt {
                transcript.setToolState(event.info.toolUseId, ToolState.RUNNING, event.info.elapsedTimeSeconds)
            }

            is ClaudeEvent.ToolUseSummary -> edt {
                if (event.info.summary.isNotBlank()) transcript.add(Speaker.SYSTEM, "↳ ${event.info.summary}")
            }

            is ClaudeEvent.BackgroundTasksChanged -> edt {
                taskTracker.replaceBackgroundTasks(event.info.tasks)
                backgroundTaskRegistry.observeLevel(event.info.tasks)
                poll.ensureOutputTail()
                fireState()
            }
        }
    }

    private fun onSessionSignal(event: ClaudeEvent.SessionSignal) {
        when (event) {
            is ClaudeEvent.RateLimit -> onRateLimit(event)

            is ClaudeEvent.AuthStatus -> onAuthStatus(event)

            is ClaudeEvent.ControlRequestProgress -> onControlRequestProgress(event)

            is ClaudeEvent.SessionStateChanged -> {
                sessionState = event.info.state
                edt { fireState() }
            }

            is ClaudeEvent.ThinkingTokens -> edt {
                liveThinkingTokens = event.info.estimatedTokens
                fireState()
            }

            is ClaudeEvent.ApiRetry -> {
                val of = if (event.info.maxRetries > 0) "/${event.info.maxRetries}" else ""
                systemNotice("Retrying (attempt ${event.info.attempt}$of)…")
            }

            is ClaudeEvent.CommandsChanged -> edt {
                commands = event.info.commands
                fireMetadata()
            }

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
        if (isHiddenUsageWindow(window)) return
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
            edt { surfaceAuthFailure(it, "Authentication error: $it") }
        }
        edt { fireState() }
    }

    private fun onControlRequestProgress(event: ClaudeEvent.ControlRequestProgress) {
        val i = event.info
        if (i.status == "api_retry") {
            val of = (i.maxRetries ?: 0).takeIf { it > 0 }?.let { "/$it" } ?: ""
            systemNotice("Retrying (attempt ${i.attempt ?: 1}$of)…")
        } else {
            log.debug("control_request_progress: ${i.status} for ${i.requestId}")
        }
    }

    private fun onHookTelemetry(event: ClaudeEvent.HookTelemetry) = edt {
        when (event) {
            is ClaudeEvent.HookStarted -> hookNarrator.onStarted(event.info)
            is ClaudeEvent.HookProgress -> hookNarrator.onProgress(event.info)
            is ClaudeEvent.HookResponse -> hookNarrator.onResponse(event.info)
        }
    }

    private fun onTerminated(gen: Int, exitCode: Int) {
        if (gen != generation) return
        val staleResume = resumedLaunch && !initialized
        flushDeltas()
        controlClient.failAll("process gone")
        edt {
            turnActive = false
            interrupting = false
            ready = false
            initialized = false
            liveThinkingTokens = 0
            promptSuggestion = null
            cardManager.clear()
            taskTracker.clear()
            hookNarrator.clear()
            if (exitCode != 0 && staleResume) {
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
                notifyError("Claude Code exited unexpectedly (code $exitCode).")
                fireAttention(AttentionReason.ERROR)
            } else {
                systemNotice("Session ended.")
            }
            fireState()
        }
    }

    private fun write(line: String) = process?.writeLine(line)

    private fun cancelPendingElicitations() {
        runCatching {
            cardManager.all().filter { it.elicitation != null }.forEach {
                write(ControlProtocol.elicitationResult(it.requestId, "cancel"))
            }
        }
    }

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

    internal fun systemNotice(message: String) = edt { transcript.add(Speaker.SYSTEM, message) }

    internal fun guardNotice(
        toolName: String,
        reason: String,
        rule: SecurityRule,
        action: String? = null,
        command: String? = null,
        toolUseId: String? = null,
    ) = edt {
        if (guardLandingOf(toolUseId) != AttentionLanding.Chat) return@edt
        transcript.add(
            Speaker.SYSTEM,
            "Allowed $toolName: $reason.",
            commandText = command?.takeIf { it.isNotBlank() },
            bypassedRule = rule.name,
            bypassAction = action,
        )
    }

    private fun guardLandingOf(toolUseId: String?): AttentionLanding {
        if (toolUseId == null || transcript.knowsTool(toolUseId)) return AttentionLanding.Chat
        val owner = runningAgents.nodes.values
            .firstOrNull { node -> node.entries.any { it.toolUseId == toolUseId } }
        return owner?.let { AttentionLanding.Agent(it.agentId) } ?: AttentionLanding.Elsewhere
    }

    fun guardAlertsAnchoredIn(entries: List<EntryDTO>): List<GuardAlert> {
        if (guardAlerts.isEmpty()) return emptyList()
        val anchors = entries.mapNotNullTo(HashSet()) { it.toolUseId }
        return guardAlerts.filter { it.toolUseId in anchors }
    }

    fun scanAgents() = agentScanner.scan()

    private fun labelAgentCards() {
        runningAgents.nodes.values.forEach { node ->
            val toolUseId = node.meta.toolUseId ?: return@forEach
            transcript.toolNameOf(toolUseId) ?: return@forEach
            transcript.setToolState(
                toolUseId,
                when (node.status) {
                    AgentStatus.RUNNING -> ToolState.RUNNING
                    AgentStatus.COMPLETED -> ToolState.FINISHED
                    else -> ToolState.ERROR
                },
            )
            val label = node.meta.description?.takeIf { it.isNotBlank() } ?: return@forEach
            transcript.setToolTitle(toolUseId, "${node.kindLabel} ($label)")
        }
    }

    private fun settleFromLifecycle(toolUseId: String?, status: String?): Boolean {
        if (toolUseId.isNullOrBlank() || status.isNullOrBlank()) return false
        val ending = agentStatusOf(status).takeIf { it != AgentStatus.RUNNING } ?: return false
        runningAgents.observeSettled(toolUseId, ending)
        return true
    }

    private fun agentStatusOf(status: String): AgentStatus = when (status.lowercase()) {
        "completed", "complete", "done", "finished", "success", "succeeded" -> AgentStatus.COMPLETED

        "", "running", "in_progress", "in-progress", "started", "starting", "pending", "queued", "paused",
        -> AgentStatus.RUNNING

        "stopped", "cancelled", "canceled", "interrupted", "aborted", "killed" -> AgentStatus.STOPPED

        else -> AgentStatus.FAILED
    }

    private fun fireAgents(fresh: List<String>) = listeners.forEach { it.onAgentsChanged(fresh) }

    private fun fireState() = listeners.forEach { it.onStateChanged() }
    private fun fireMetadata() = listeners.forEach { it.onMetadataChanged() }
    private fun firePermissions() = listeners.forEach { it.onPermissionsChanged() }
    private fun fireAttention(reason: AttentionReason, landing: AttentionLanding = AttentionLanding.Chat) =
        listeners.forEach { it.onAttention(reason, landing) }
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

    private fun ensureExecTrust(settings: ClaudeSettings): Boolean {
        if (!settings.requiresTrustPrompt()) return true
        val choice = Messages.showYesNoDialog(
            project,
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
        generation++
        starting = false
        poll.stopAll()
        cancelPendingElicitations()
        diffs.clearReviewDiffs()
        process?.terminate()
        process = null
        controlClient.failAll("process gone")
    }

    fun modelOptions(): List<ModelInfo> = models

    fun preferredDefaultModel(): String = preferredDefault(models)

    companion object {
        const val NOTIFICATION_GROUP = "Claude Code"

        const val EXPIRED_TOKEN_NOTICE =
            "Your access token expired while this chat was open. The sign-in itself is still valid and is " +
                "renewed when a session starts, but a running one cannot pick up the new token — so this turn " +
                "did not complete, and sending it again will fail the same way. Close this chat and open it " +
                "again to continue."

        const val SIDE_QUESTION_UNANSWERED = "↩ The side question was not answered."

        const val CONTROL_TIMEOUT_SECONDS = 30L

        const val DEFAULT_MODEL = "opus[1m]"

        const val RECOMMENDED_ALIAS = "default"

        fun preferredDefault(models: List<ModelInfo>, pinned: String = DEFAULT_MODEL): String = when {
            models.isEmpty() -> pinned

            models.any { it.value == pinned } -> pinned

            models.any { it.value == RECOMMENDED_ALIAS } -> RECOMMENDED_ALIAS

            else -> TIER_ORDER.firstNotNullOfOrNull { tier ->
                models.firstOrNull { it.value.contains(tier, ignoreCase = true) }?.value
            } ?: models.first().value
        }

        private val TIER_ORDER = listOf("opus", "sonnet", "haiku")

        const val THINKING_ON = 1

        val PERMISSION_MODES_CYCLE = PermissionMode.CYCLE.map { it.wire }

        val PERMISSION_MODES = PermissionMode.entries.map { it.wire }

        val EFFORT_LEVELS = EffortLevel.entries.map { it.wire }

        val SETTING_SOURCES = listOf("user", "project", "local")

        const val DEFAULT_IDE_MCP_PORT = 64342

        internal val VALID_PORTS = 1..65_535

        val IDE_MCP_TRANSPORTS = McpTransport.entries.map { it.wire }

        val CUSTOM_MCP_SERVERS_HINT = """
            {
              "my-http-server": { "type": "streamable-http", "url": "https://example.com/mcp", "headers": {} },
              "my-stdio-server": { "type": "stdio", "command": "/path/to/server", "args": [] }
            }
        """.trimIndent()

        fun isValidMcpConfig(text: String): Boolean =
            text.isBlank() || (runCatching { ClaudeJson.parseToJsonElement(text) }.getOrNull() is JsonObject)
    }
}
