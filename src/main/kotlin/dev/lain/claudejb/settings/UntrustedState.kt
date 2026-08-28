package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger

/**
 * Settings that arrived in a file rather than from the person sitting at the IDE.
 *
 * Two routes reach here: the legacy `.idea/claude-code.xml` a project carries, which is adopted automatically the
 * first time that project is opened, and an explicit *Import settings…*. A repository can commit the first one, so
 * the fields below are not a file's decision to make: three of them name something the plugin then executes, one is
 * the persisted answer to the trust dialog that would otherwise ask about them, and the rest are the guard's own
 * controls. Left alone, a clone-and-open would supply both the code to run and the record that the user had already
 * agreed to run it.
 *
 * Everything else is adopted, so migrating a project's model, effort or MCP endpoints still works.
 */
internal object UntrustedState {

    private val log = logger<UntrustedState>()

    /**
     * A file the project carried, adopted with no one asked. Strips the execution primitives, the trust flag, the
     * master switch, and the guard's rules and whitelists too — nothing here was consented to.
     */
    fun fromProjectFile(state: ClaudeSettings.State): ClaudeSettings.State = disarm(state, keepGuardRules = false)

    /**
     * A file the user picked in *Import settings…* and confirmed. The confirmation names the guard's rules and
     * whitelists, so those are the point of the feature and travel. What it does not name — the execution
     * primitives, the trust flag, the master switch, and remembered tool approvals — does not.
     */
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
