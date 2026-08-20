package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardAlertLog
import dev.lain.claudejb.settings.SecretStore
import dev.lain.claudejb.settings.SecuritySuspensions

internal object CleanSettings {

    const val PLUGIN_TITLE = "Restore Plugin to default state"

    const val GUARD_TITLE = "Restore Sensitive Guard settings to default"

    fun restorePlugin(project: Project): Boolean {
        val body = "Put this project's Claude Code settings back to a fresh install?\n\n" +
            "This clears the model, permission mode, executable paths, environment, MCP servers and every " +
            "Sensitive Guard rule, mode and whitelist, along with the guard's alert history, the list of " +
            "chats to reopen and the agent index — for this project, in this IDE.\n\n" +
            "Your conversations are not touched. It does not sign you out, and it does not touch your " +
            "provider keys, your Git host tokens, or any other project's settings. There is no undo."
        if (!confirm(project, PLUGIN_TITLE, body)) return false
        val settings = ClaudeSettings.getInstance(project)
        val scope = settings.scope
        if (!settings.wipe()) return false
        GuardAlertLog.clear(scope)
        SecretStore.clear(scope.openChatsName)
        SecretStore.clear(scope.agentIndexName)
        repaint()
        return true
    }

    fun restoreGuard(project: Project): Boolean {
        val body = "Put the Sensitive Guard back to its default configuration?\n\n" +
            "Every rule returns to Enforcing, Allow All is switched off, and the extra credential globs, " +
            "extra blocked domains and all three whitelists are emptied — for this project, in this IDE.\n\n" +
            "Nothing else on the Claude Code page changes. There is no undo."
        if (!confirm(project, GUARD_TITLE, body)) return false
        val settings = ClaudeSettings.getInstance(project)
        settings.update { state ->
            val defaults = ClaudeSettings.State()
            SecuritySuspensions.guardOn(state)
            state.guardMode = defaults.guardMode
            state.disabledSecurityRules = defaults.disabledSecurityRules
            state.securityRuleSuspensions = defaults.securityRuleSuspensions
            state.securityExtraBlockedDomains = defaults.securityExtraBlockedDomains
            state.securityCommandWhitelist = defaults.securityCommandWhitelist
            state.securityCategoryWhitelists = defaults.securityCategoryWhitelists
            state.securityRuleWhitelists = defaults.securityRuleWhitelists
            state.sensitiveExtraGlobs = defaults.sensitiveExtraGlobs
        }
        SecurityRule.entries.forEach { SecuritySuspensions.releaseSessionScoped(it) }
        repaint()
        return true
    }

    private fun repaint() {
        JcefChatPanel.pushStateToAll()
        JcefChatPanel.pushSettingsMenuToAll()
    }

    private fun confirm(project: Project, title: String, body: String) = MessageDialogBuilder
        .yesNo(title, body)
        .yesText("Restore")
        .noText("Cancel")
        .ask(project)
}
