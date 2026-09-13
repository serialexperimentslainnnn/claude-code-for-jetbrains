package dev.lain.claudejb.controller.bridge

import dev.lain.claudejb.controller.commands.LivePanels
import dev.lain.claudejb.controller.commands.git.GitActionCatalog
import dev.lain.claudejb.controller.commands.git.GitIntegration
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.WorkloadWindow
import dev.lain.claudejb.util.thisLogger
import dev.lain.claudejb.view.window.ClaudeToolWindowFactory
import dev.lain.claudejb.view.window.JcefChatPanel

internal class BridgeSessionControl(private val panel: JcefChatPanel) {

    private val log = thisLogger()

    private val vuln = BridgeVuln(panel)

    private val navigation = BridgeNavigation(panel)

    private val session get() = panel.session

    fun handle(m: Msg.SessionControl) {
        when (m) {
            is Msg.Vuln -> vuln.handle(m)

            is Msg.Navigation -> navigation.handle(m)

            is Msg.Onboarding -> panel.onboarding.handle(m)

            Msg.McpRefresh -> panel.feed.requestMcp()

            is Msg.McpReconnect -> {
                session.queries.reconnectMcp(m.name)
                panel.feed.requestMcp()
            }

            is Msg.McpToggle -> {
                session.queries.toggleMcp(m.name, m.enabled)
                panel.feed.requestMcp()
            }

            is Msg.StopTask -> session.queries.stopTask(m.taskId)

            is Msg.SetWorkloadWindow -> workloadWindow(m.minutes)

            is Msg.GitAction -> gitAction(m)

            Msg.NewChat -> ClaudeToolWindowFactory.newChat(panel.project)

            Msg.CloseThisChat -> navigation.withStrip("close this chat") { strip ->
                strip.tabFor(session)?.let { strip.close(it) }
            }

            Msg.OpenGitView -> ClaudeToolWindowFactory.showGitView(panel.project)
        }
    }

    private fun gitAction(m: Msg.GitAction) {
        GitIntegration.getInstance(panel.project).perform(m.id, m.hash, { panel.gitChat.session() }) { panel.pushGit() }
        if (GitActionCatalog.byId(m.id)?.kind == GitActionCatalog.Kind.PROMPT) panel.gitChat.show()
    }

    private fun workloadWindow(minutes: Int) {
        if (minutes !in WorkloadWindow.WINDOW_MINUTES) {
            log.warn("Workloads view asked for a window this build does not offer: $minutes")
            return
        }
        ClaudeSettings.getInstance(panel.project).update { it.workloadWindowMinutes = minutes }
        LivePanels.pushSession()
    }
}
