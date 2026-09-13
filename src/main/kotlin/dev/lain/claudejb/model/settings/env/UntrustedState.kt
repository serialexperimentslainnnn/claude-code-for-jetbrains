package dev.lain.claudejb.model.settings.env

import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.guard.GuardMode
import dev.lain.claudejb.model.settings.guard.guardMode
import dev.lain.claudejb.model.settings.legacy.LegacyPermissionMode
import dev.lain.claudejb.util.logger

internal object UntrustedState {

    private val log = logger<UntrustedState>()

    fun fromProjectFile(state: ClaudeSettings.State): ClaudeSettings.State = disarm(state, keepGuardRules = false)

    fun fromImportedFile(state: ClaudeSettings.State): ClaudeSettings.State = disarm(state, keepGuardRules = true)

    private fun disarm(state: ClaudeSettings.State, keepGuardRules: Boolean): ClaudeSettings.State {
        val stripped = mutableListOf<String>()

        fun clear(name: String, current: String, set: () -> Unit) {
            if (current.isBlank()) return
            set()
            stripped += name
        }

        clear("claudePath", state.claudePath) { state.claudePath = "" }
        clear("nodePath", state.nodePath) { state.nodePath = "" }
        clear("sourceScript", state.sourceScript) { state.sourceScript = "" }
        clear("customMcpServers", state.customMcpServers) { state.customMcpServers = "" }
        clear("alwaysAllowTools", state.alwaysAllowTools) { state.alwaysAllowTools = "" }

        if (!keepGuardRules) {
            clear("disabledSecurityRules", state.disabledSecurityRules) { state.disabledSecurityRules = "" }
            clear("securityRuleSuspensions", state.securityRuleSuspensions) { state.securityRuleSuspensions = "" }
            clear("securityCommandWhitelist", state.securityCommandWhitelist) { state.securityCommandWhitelist = "" }
            clear("securityCategoryWhitelists", state.securityCategoryWhitelists) {
                state.securityCategoryWhitelists = ""
            }
            clear("securityRuleWhitelists", state.securityRuleWhitelists) { state.securityRuleWhitelists = "" }
        }

        if (state.executionTrusted) {
            state.executionTrusted = false
            stripped += "executionTrusted"
        }
        if (state.guardMode != GuardMode.DEFAULT.wire) {
            state.guardMode = GuardMode.DEFAULT.wire
            stripped += "guardMode"
        }
        if (state.guardDisabledUntil != 0L) {
            state.guardDisabledUntil = 0
            stripped += "guardDisabledUntil"
        }
        if (LegacyPermissionMode.weakensSecurity(state.permissionMode)) {
            state.permissionMode = LegacyPermissionMode.SAFE
            stripped += "permissionMode"
        }

        if (stripped.isNotEmpty()) {
            log.warn(
                "settings arriving in a file do not get to decide what runs or how much the guard asks — " +
                    "ignored: ${stripped.joinToString(", ")}",
            )
        }
        return state
    }
}
