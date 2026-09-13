package dev.lain.claudejb.controller.bridge

import com.intellij.openapi.options.ShowSettingsUtil
import dev.lain.claudejb.controller.commands.LivePanels
import dev.lain.claudejb.controller.session.ChatSessionManager
import dev.lain.claudejb.model.bridge.Msg
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.LaunchDefaults
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.util.thisLogger
import dev.lain.claudejb.view.feed.ChatTheme
import dev.lain.claudejb.view.payload.menu.JcefSettingsMenu
import dev.lain.claudejb.view.settings.ClaudeSettingsConfigurable
import dev.lain.claudejb.view.window.JcefChatPanel

internal class BridgeSettings(private val panel: JcefChatPanel) {

    private val log = thisLogger()

    private val guard = BridgeGuard(panel)

    private val session get() = panel.session

    fun handle(m: Msg.Settings) {
        when (m) {
            is Msg.ChangeModel -> session.settings.changeModel(m.value)

            is Msg.ChangeMode -> session.settings.changePermissionMode(m.wire)

            is Msg.ChangeEffort -> session.settings.changeEffort(m.value)

            is Msg.ChangeThinking ->
                session.settings.changeThinkingTokens(if (m.on) LaunchDefaults.THINKING_ON else null)

            is Msg.ChangeVibe -> {
                ChatTheme.setVibeMode(m.on)
                LivePanels.pushTheme()
            }

            is Msg.ChangeProvider -> session.settings.changeProvider(Provider.fromId(m.id))

            is Msg.SettingsToggle -> toggle(m)

            is Msg.Guard -> guard.handle(m)

            Msg.SettingsRefresh -> ClaudeSettings.getInstance(panel.project).reload { LivePanels.pushSettingsMenu() }

            Msg.OpenSettings ->
                ShowSettingsUtil.getInstance().showSettingsDialog(panel.project, ClaudeSettingsConfigurable::class.java)
        }
    }

    private fun toggle(m: Msg.SettingsToggle) {
        if (!write(m)) {
            log.warn("The chat's settings menu asked for a switch this build does not have: ${m.key}")
            return
        }
        LivePanels.pushSettingsMenu()
    }

    private fun write(m: Msg.SettingsToggle): Boolean {
        val settings = ClaudeSettings.getInstance(panel.project)
        JcefSettingsMenu.alwaysAllowTool(m.key)?.let { tool ->
            if (m.on) settings.alwaysAllow.remember(tool) else settings.alwaysAllow.forget(tool)
            return true
        }
        JcefSettingsMenu.sessionApproval(m.key)?.let { (rule, command) ->
            if (!m.on) session.guard.approvals.revoke(rule, command)
            return true
        }
        if (JcefSettingsMenu.isRemoteControl(m.key)) {
            session.remote.set(m.on) { LivePanels.pushSettingsMenu() }
            return true
        }
        val scope = settings.scope.id
        val models = session.catalog.models.map { it.value }
        if (!JcefSettingsMenu.apply(scope, settings.state, m.key, m.on, models)) return false
        settings.update { JcefSettingsMenu.apply(scope, it, m.key, m.on, models) }
        ChatSessionManager.getInstance(panel.project).adoptSettings()
        return true
    }
}
