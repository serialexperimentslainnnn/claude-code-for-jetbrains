package dev.lain.claudejb.ui

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.git.GitHistoryService
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionListener
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.ui.jcef.JcefCardPayload
import dev.lain.claudejb.ui.jcef.JcefHost
import dev.lain.claudejb.ui.jcef.JcefSessionData
import dev.lain.claudejb.ui.jcef.JcefSettingsMenu
import dev.lain.claudejb.ui.jcef.JcefState
import dev.lain.claudejb.ui.jcef.JcefTheme
import dev.lain.claudejb.vuln.VulnService
import java.awt.BorderLayout

class JcefChatPanel(internal val project: Project, val session: ClaudeSession) :
    JBPanel<JcefChatPanel>(BorderLayout()), Disposable, SessionListener {

    internal val router = ChatBridgeRouter(this)

    internal val host = JcefHost(this, router::dispatch)

    internal val links = LinkNavigator(project)

    internal val transcript = ChatTranscriptView(session, host::exec)

    internal val tray = AttachmentTray(project, host::exec, ::focusInput)

    internal val edits = ChatEditReview(project, session, tray::notify)

    private val pendingUntilReady = mutableListOf<() -> Unit>()

    internal val feed = SessionFeed(session, host::exec) {
        pushSession()
        pushMetaState()
    }

    private var wasRunning = false

    private var lastSessionJson: String? = null
    private var lastSettingsMenuJson: String? = null
    private var lastMetaState: Pair<String, String>? = null

    internal val onboarding = OnboardingController(project, session, host::exec)

    internal val agentTabs = ChatAgentTabs(this)

    internal val gitChat = GitChatFeed(this, host::exec)

    internal val guard = GuardFeed(this)

    internal val security = SecurityViews(this)

    init {
        background = ChatTheme.BG
        add(host.component, BorderLayout.CENTER)

        agentTabs.render()
        session.scanAgents()

        livePanels.add(this)
        session.transcript.addListener(transcript)
        session.addListener(this)
        session.login.attachUi(onboarding)

        val lafConn = ApplicationManager.getApplication().messageBus.connect(this)
        lafConn.subscribe(LafManagerListener.TOPIC, LafManagerListener { pushTheme() })
        Disposer.register(this, lafConn)

        project.service<GitHistoryService>().onRepositoryChanged(this) {
            ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) pushGit() }, ModalityState.any())
        }

        pushTheme()
        pushSettingsMenu()
        pushMetaState()
        pushPermissions()
        tray.push()
        pushSession()
        security.pushVuln()
        whenReady(feed::onSessionReady)
        feed.start()
        transcript.fullResync()
    }

    override fun onAgentsChanged(freshlyAdmitted: List<String>) {
        agentTabs.onAgentsScanned(freshlyAdmitted)
        transcript.refreshShown()
        pushSession()
    }

    override fun onStateChanged() {
        pushMetaState()
        pushSession()
        pushSettingsMenu()
        agentTabs.render()
        if (transcript.showsTask) transcript.refreshShown()
        drainPendingUntilReady()
        val running = session.isRunning()
        if (running && !wasRunning) feed.onSessionReady()
        wasRunning = running
        if (session.rateLimits.isNotEmpty()) feed.requestUsage()
        if (!session.turnActive && running) {
            feed.requestPlan()
            pushGit()
        }
        onboarding.onStateChanged()
    }

    override fun onMetadataChanged() {
        pushMetaState()
        pushSession()
    }
    override fun onPermissionsChanged() = pushPermissions()

    private fun whenReady(action: () -> Unit) {
        if (session.isRunning()) {
            action()
            return
        }
        pendingUntilReady += action
    }

    private fun drainPendingUntilReady() {
        if (pendingUntilReady.isEmpty() || !session.isRunning()) return
        val queued = pendingUntilReady.toList()
        pendingUntilReady.clear()
        queued.forEach { it() }
    }

    internal fun pushTheme() {
        val reduceMotion = ClaudeSettings.getInstance(project).reduceMotion
        host.exec("window.cc.theme && window.cc.theme(" + JcefTheme.vars(reduceMotion) + ")")
    }

    internal fun pushSettingsMenu() {
        val settings = ClaudeSettings.getInstance(project)
        val items = JcefSettingsMenu.json(settings.scope.id, settings.state, session).toString()
        if (items == lastSettingsMenuJson) return
        lastSettingsMenuJson = items
        host.exec("window.cc.settingsMenu && window.cc.settingsMenu({\"items\":$items})")
    }

    internal fun pushMetaState() {
        val meta = JcefState.metaJson(session)
        val state = JcefState.stateJson(session, feed.usage)
        val current = meta to state
        if (current == lastMetaState) return
        lastMetaState = current
        host.exec("window.cc.meta && window.cc.meta($meta);" + "window.cc.state && window.cc.state($state)")
    }

    internal fun pushPermissions() {
        val perms = session.cards.pending()
        val groups = listOf(JcefCardPayload.Group(perms, diffByRequest = edits.diffsFor(perms))) +
            gitChat.permissionGroup()
        host.exec("window.cc.permissions && window.cc.permissions(" + JcefCardPayload.permissionsJson(groups) + ")")
    }

    internal fun pushGit() = GitIntegration.getInstance(project).refresh(::pushSession)

    internal fun pushSession() {
        val json = JcefSessionData.sessionJson(
            session,
            windowMinutes = ClaudeSettings.getInstance(project).workloadWindowMinutes,
            nowMillis = System.currentTimeMillis(),
            usage = feed.usage,
            workloads = chatStrip()?.workloads().orEmpty(),
            plan = feed.plan,
            git = GitIntegration.getInstance(project).snapshot(),
            vuln = VulnService.getInstance(project).snapshot(),
        )
        if (json == lastSessionJson) return
        lastSessionJson = json
        LOG.debug("CC-TRACE pushSession ${json.take(TRACE_MAX)}")
        host.exec("window.cc.session && window.cc.session($json)")
    }

    internal fun chatStrip(): ChatTabsPanel? =
        (javax.swing.SwingUtilities.getAncestorOfClass(ChatTabsPanel::class.java, this) as? ChatTabsPanel)
            ?: ClaudeToolWindowFactory.chatTabs(project)

    fun openDashboard() {
        pushSession()
        security.pushVuln()
        feed.requestMcp()
        feed.requestVersion()
        feed.requestUsage()
        host.exec("window.cc.openDashboard && window.cc.openDashboard()")
    }

    fun focusTarget(): javax.swing.JComponent? = host.inputComponent()

    fun focusInput() = host.requestFocus()

    fun mentionCurrentFile() = tray.addCurrentFile()

    fun addAttachment(attachment: Attachment) = tray.add(attachment)

    override fun dispose() {
        livePanels.remove(this)
        session.transcript.removeListener(transcript)
        session.removeListener(this)
        session.login.detachUi(onboarding)
        onboarding.dispose()
        transcript.stop()
        feed.stop()
        gitChat.dispose()
    }

    internal companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(JcefChatPanel::class.java)

        private const val TRACE_MAX = 2000

        private val livePanels = java.util.concurrent.CopyOnWriteArrayList<JcefChatPanel>()
        fun broadcastTheme() {
            livePanels.forEach { it.pushTheme() }
        }

        fun pushSessionToAll() {
            livePanels.forEach { it.pushSession() }
        }

        fun pushSettingsMenuToAll() {
            livePanels.forEach { it.pushSettingsMenu() }
        }

        fun pushStateToAll() {
            livePanels.forEach { it.pushMetaState() }
        }
    }
}
