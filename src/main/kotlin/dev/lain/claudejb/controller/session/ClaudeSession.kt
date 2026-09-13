package dev.lain.claudejb.controller.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.session.auth.LoginCoordinator
import dev.lain.claudejb.controller.session.control.RemoteControl
import dev.lain.claudejb.controller.session.control.SessionControlClient
import dev.lain.claudejb.controller.session.control.SessionQueries
import dev.lain.claudejb.controller.session.diff.DiffLifecycleManager
import dev.lain.claudejb.controller.session.diff.RollbackManager
import dev.lain.claudejb.controller.session.events.HookActivityNarrator
import dev.lain.claudejb.controller.session.events.NoticeNarrator
import dev.lain.claudejb.controller.session.events.SessionEventRouter
import dev.lain.claudejb.controller.session.guard.PermissionCardManager
import dev.lain.claudejb.controller.session.guard.SessionCards
import dev.lain.claudejb.controller.session.guard.SessionGuard
import dev.lain.claudejb.controller.session.history.AgentScanner
import dev.lain.claudejb.controller.session.turn.PollSchedule
import dev.lain.claudejb.controller.session.turn.PromptComposer
import dev.lain.claudejb.controller.session.turn.PromptQueue
import dev.lain.claudejb.controller.session.turn.QuotaWarnings
import dev.lain.claudejb.controller.session.turn.TurnControl
import dev.lain.claudejb.model.context.Attachment
import dev.lain.claudejb.model.protocol.ClaudeEvent
import dev.lain.claudejb.model.session.agents.AgentRegistry
import dev.lain.claudejb.model.session.agents.BackgroundTaskRegistry
import dev.lain.claudejb.model.session.agents.TaskTracker
import dev.lain.claudejb.model.session.history.SessionStore
import dev.lain.claudejb.model.session.launch.LaunchOptions
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.TranscriptModel
import dev.lain.claudejb.model.session.transcript.TranscriptReconciler
import dev.lain.claudejb.model.session.turn.SessionSignals
import dev.lain.claudejb.model.session.turn.TokenAccountant
import dev.lain.claudejb.model.session.turn.TurnState
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.thisLogger
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeSession(
    val project: Project,
    @Volatile var title: String,
    val gitIntegration: Boolean = false,
) : Disposable {

    private val log = thisLogger()

    internal val notifier = SessionNotifier(project)

    val transcript = TranscriptModel()

    val tokens = TokenAccountant()
    internal val taskTracker = TaskTracker()
    internal val reconciler = TranscriptReconciler(transcript)

    val diffs = DiffLifecycleManager(project)
    val rollback = RollbackManager(project, diffs, reseedReadState = { p, m -> queries.seedReadState(p, m) })
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
        quota = QuotaWarnings(log, QuotaWarnings.Announce(inTranscript = ::systemNotice, asNotification = notifier::info)),
    )

    val persistence = SessionPersistence(this, project, ::edt, ::fireState, ::fireTitleChanged)

    private val notices = NoticeNarrator(
        log = log,
        systemNotice = ::systemNotice,
        addRow = { speaker, text, meta -> transcript.add(speaker, text, meta = meta) },
        notifyInfo = notifier::info,
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
    internal val hookNarrator = HookActivityNarrator(transcript)

    val login = LoginCoordinator(
        project,
        edt = ::edt,
        notifier = notifier,
        restartSession = { if (!lifecycle.disposed) restart() },
    )

    @Volatile var sessionId: String? = null
        internal set

    @Volatile var launch: LaunchOptions = LaunchOptions()
        internal set

    val turn = TurnState()

    val signals = SessionSignals()

    val runningAgents = AgentRegistry(subagentsDir = { sessionId?.let { SessionStore.subagentsDir(it) } })

    val backgroundTaskRegistry = BackgroundTaskRegistry()

    val agentScanner: AgentScanner = AgentScanner.forSession(this, onFresh = ::fireAgents, onOutputGrew = ::fireState)

    fun ownerAgentOfTask(taskId: String): String? {
        val fromLink = backgroundTaskRegistry.taskOf(taskId)?.ownerToolUseId
        val fromEdge = taskTracker.tasks[taskId]?.toolUseId
        val tool = fromLink ?: fromEdge ?: return null
        return runningAgents.nodes.values.firstOrNull { it.meta.toolUseId == tool }?.agentId
    }

    val catalog = BinaryCatalog(this, ::fireMetadata)

    val remote = RemoteControl(queries, transcript, ::fireState)

    val prompts = PromptQueue(
        transcript = transcript,
        edt = ::edt,
        write = ::write,
        canSend = { lifecycle.ready && isRunning() },
        onSent = {
            turn.active = true
            poll.startQuotaPolling()
            poll.ensureAgentRevivalPoll()
        },
        fireState = ::fireState,
    )

    val turnControl = TurnControl(this, ::edt, ::write, ::fireState)

    private val listeners = CopyOnWriteArrayList<SessionListener>()

    internal val poll = PollSchedule.forSession(this, ::edt, ::fireState)

    val guard = SessionGuard(
        session = this,
        project = project,
        edt = ::edt,
        write = ::write,
        fireState = ::fireState,
        fireAttention = ::fireAttention,
    )

    internal val events = SessionEventRouter(this, ::edt, ::fireState, ::fireMetadata, ::fireAttention, notices)

    val lifecycle = SessionLifecycle(this, project, ::edt, ::fireState, ::fireAttention, events::onEvent)

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
        edt { poll.pollQuota() }
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
        edt { if (listeners.isEmpty() && poll.quotaRunning) poll.stopQuota() }
    }

    fun isRunning(): Boolean = lifecycle.isRunning()

    fun start(resume: Boolean = sessionId != null): Boolean = lifecycle.start(resume)

    fun restart(resume: Boolean = true) = lifecycle.restart(resume)

    fun stop() = lifecycle.stop()

    fun send(text: String) = send(text, emptyList())

    fun send(text: String, attachments: List<Attachment>) {
        val composed = PromptComposer.compose(text, attachments, project.basePath) ?: return
        if (!isRunning()) {
            if (!start()) return
        }
        prompts.enqueue(composed.wireText, composed.images, composed.displayText)
    }

    fun sendSideQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!isRunning()) {
            if (!start()) return
            prompts.enqueue(trimmed, emptyList(), trimmed)
            return
        }
        edt {
            transcript.add(Speaker.USER, "↪ $trimmed")
            queries.askSideQuestion(trimmed) { answer ->
                transcript.add(Speaker.SYSTEM, answer?.let { "↩ $it" } ?: SIDE_QUESTION_UNANSWERED)
            }
            prompts.pump()
        }
    }

    @org.jetbrains.annotations.TestOnly
    fun handleEventForTest(event: ClaudeEvent) {
        events.onEvent(event)
        events.flushDeltas()
    }

    internal fun flushDeltas() = events.flushDeltas()

    internal fun write(line: String): Boolean = lifecycle.write(line)

    internal fun systemNotice(message: String) = edt { transcript.add(Speaker.SYSTEM, message) }

    private fun fireAgents(fresh: List<String>) = listeners.forEach { it.onAgentsChanged(fresh) }

    private fun fireState() = listeners.forEach { it.onStateChanged() }
    private fun fireMetadata() = listeners.forEach { it.onMetadataChanged() }
    private fun firePermissions() = listeners.forEach { it.onPermissionsChanged() }
    private fun fireAttention(reason: AttentionReason, landing: AttentionLanding = AttentionLanding.Chat) =
        listeners.forEach { it.onAttention(reason, landing) }
    private fun fireTitleChanged() = listeners.forEach { it.onTitleChanged() }

    override fun dispose() = lifecycle.shutdown()

    private companion object {
        const val SIDE_QUESTION_UNANSWERED = "↩ The side question was not answered."
    }
}
