package dev.lain.claudejb.view.window

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.controller.bridge.ChatBridgeRouter
import dev.lain.claudejb.controller.commands.LivePanels
import dev.lain.claudejb.controller.commands.OnboardingController
import dev.lain.claudejb.controller.commands.git.GitIntegration
import dev.lain.claudejb.controller.context.LinkNavigator
import dev.lain.claudejb.controller.git.GitHistoryService
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.controller.session.IdeWithoutChat
import dev.lain.claudejb.controller.session.SessionListener
import dev.lain.claudejb.controller.vuln.VulnService
import dev.lain.claudejb.model.bridge.JcefBridge
import dev.lain.claudejb.model.context.Attachment
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.logger
import dev.lain.claudejb.view.feed.AttachmentTray
import dev.lain.claudejb.view.feed.ChatAgentTabs
import dev.lain.claudejb.view.feed.ChatEditReview
import dev.lain.claudejb.view.feed.ChatTheme
import dev.lain.claudejb.view.feed.ChatTranscriptView
import dev.lain.claudejb.view.feed.SecurityViews
import dev.lain.claudejb.view.feed.SessionFeed
import dev.lain.claudejb.view.git.GitChatFeed
import dev.lain.claudejb.view.guard.GuardFeed
import dev.lain.claudejb.view.jcef.JcefHost
import dev.lain.claudejb.view.log.LogFeed
import dev.lain.claudejb.view.payload.JcefTheme
import dev.lain.claudejb.view.payload.chat.JcefCardPayload
import dev.lain.claudejb.view.payload.chat.JcefState
import dev.lain.claudejb.view.payload.menu.SettingsMenuRows
import dev.lain.claudejb.view.payload.panel.JcefSessionData
import java.awt.BorderLayout

class JcefChatPanel(internal val project: Project, val session: ClaudeSession) :
    JBPanel<JcefChatPanel>(BorderLayout()), Disposable, SessionListener {

    internal val router = ChatBridgeRouter(this)

    internal val host = JcefHost(this, router::dispatch) { IdeWithoutChat.serve(project) }

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

    internal val logFeed = LogFeed(this)

    internal val security = SecurityViews(this)

    init {
        background = ChatTheme.BG
        add(host.component, BorderLayout.CENTER)

        agentTabs.render()
        session.agentScanner.scan()

        LivePanels.add(this)
        session.transcript.addListener(transcript)
        session.addListener(this)
        session.login.attachUi(onboarding)

        val lafConn = ApplicationManager.getApplication().messageBus.connect(this)
        lafConn.subscribe(LafManagerListener.TOPIC, LafManagerListener { pushTheme() })
        Disposer.register(this, lafConn)

        project.service<GitHistoryService>().onRepositoryChanged(this) {
            edt(project) { pushGit() }
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
        if (session.signals.rateLimits.isNotEmpty()) feed.requestUsage()
        if (!session.turn.active && running) {
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

    internal fun cardSession(scope: String): ClaudeSession =
        if (scope == JcefBridge.SCOPE_GIT) gitChat.session() else session

    internal fun pushTheme() {
        val reduceMotion = ClaudeSettings.getInstance(project).reduceMotion
        host.exec("window.cc.theme && window.cc.theme(" + JcefTheme.vars(reduceMotion) + ")")
    }

    internal fun pushSettingsMenu() {
        val settings = ClaudeSettings.getInstance(project)
        val items = SettingsMenuRows.json(settings.scope.id, settings.state, session).toString()
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
        LOG.debug { "pushSession $json" }
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
        LivePanels.remove(this)
        session.transcript.removeListener(transcript)
        session.removeListener(this)
        session.login.detachUi(onboarding)
        onboarding.dispose()
        transcript.stop()
        feed.stop()
        gitChat.dispose()
    }

    private companion object {
        val LOG = logger<JcefChatPanel>()
    }
}
