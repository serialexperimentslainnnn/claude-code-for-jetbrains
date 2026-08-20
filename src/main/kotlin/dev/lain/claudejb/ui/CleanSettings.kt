package dev.lain.claudejb.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecuritySuspensions

/**
 * The two "put it back how it was" buttons, and the one thing they have in common: a question first.
 *
 * They differ in reach and each says so in its own words. Neither touches a credential — the sign-in, the
 * provider API keys and the Git host tokens are not configuration — and neither reaches another project,
 * because since 5.6 a project's settings are its own.
 */
internal object CleanSettings {

    const val PLUGIN_TITLE = "Restore Plugin to default state"

    const val GUARD_TITLE = "Restore Sensitive Guard settings to default"

    /** Everything the plugin stores for this project, back to a fresh install. */
    fun restorePlugin(project: Project): Boolean {
        val body = "Put this project's Claude Code settings back to a fresh install?\n\n" +
            "This clears the model, permission mode, executable paths, environment, MCP servers and every " +
            "Sensitive Guard rule, mode and whitelist — for this project, in this IDE.\n\n" +
            "It does not sign you out, and it does not touch your provider keys, your Git host tokens, or " +
            "any other project's settings. There is no undo."
        if (!confirm(project, PLUGIN_TITLE, body)) return false
        return ClaudeSettings.getInstance(project).wipe().also { if (it) repaint() }
    }

    /** Only what the guard owns; every other setting on the plugin's page is left alone. */
    fun restoreGuard(project: Project): Boolean {
        val body = "Put the Sensitive Guard back to its default configuration?\n\n" +
            "Every rule returns to Enforcing, Allow All is switched off, and the extra credential globs, " +
            "extra blocked domains and all three whitelists are emptied — for this project, in this IDE.\n\n" +
            "Nothing else on the Claude Code page changes. There is no undo."
        if (!confirm(project, GUARD_TITLE, body)) return false
        val settings = ClaudeSettings.getInstance(project)
        settings.update { state ->
            val defaults = ClaudeSettings.State()
            // guardOn rather than three assignments: it is the one place that knows Allow All has an
            // in-memory store as well as two persisted ones, and forgetting that store is how a switch lies.
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
        // The until-the-IDE-closes suspensions live in memory and no document write can reach them; leaving
        // them behind would mean a rule still Permissive on a page that says every rule is Enforcing.
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
