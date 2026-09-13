package dev.lain.claudejb.view.payload.menu

import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.protocol.EffortLevel
import dev.lain.claudejb.model.protocol.PermissionMode
import dev.lain.claudejb.model.session.launch.GodMode
import dev.lain.claudejb.model.session.transcript.ToolNaming
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.LaunchDefaults
import dev.lain.claudejb.model.settings.guard.GuardMode
import dev.lain.claudejb.model.settings.guard.SecuritySuspensions

internal object JcefSettingsMenu {

    fun apply(scope: String, state: ClaudeSettings.State, key: String, on: Boolean, models: List<String>): Boolean {
        val prefix = key.substringBefore(':', missingDelimiterValue = "")
        if (prefix.isEmpty()) return applyFlag(state, key, on)
        val value = key.substringAfter(':')
        val choice = Choice(scope, prefix, value, on)
        return applyChoice(choice, state, models) ?: applyList(scope, state, prefix, value, on) ?: false
    }

    fun isRemoteControl(key: String): Boolean = key == REMOTE_CONTROL

    fun alwaysAllowTool(key: String): String? {
        if (!key.startsWith("$ALWAYS:")) return null
        return key.removePrefix("$ALWAYS:").takeIf { it in ToolNaming.BUILTIN_TOOLS }
    }

    fun sessionApproval(key: String): Pair<SecurityRule, String>? {
        if (!key.startsWith("$APPROVAL:")) return null
        val rest = key.removePrefix("$APPROVAL:")
        val rule = SecurityRule.from(rest.substringBefore(':', "")) ?: return null
        val command = rest.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
        return rule to command
    }

    private val FLAG_SETTERS: Map<String, (ClaudeSettings.State, Boolean) -> Unit> = mapOf(
        "restoreChats" to { s, on -> s.restoreOpenChatsOnStartup = on },
        "reduceMotion" to { s, on -> s.reduceMotion = on },
        "checkpointing" to { s, on -> s.enableFileCheckpointing = on },
        "partialMessages" to { s, on -> s.includePartialMessages = on },
        "strictMcp" to { s, on -> s.strictMcpConfig = on },
        GOD_MODE to { s, on -> GodMode.set(s, on) },
    )

    private fun applyFlag(state: ClaudeSettings.State, key: String, on: Boolean): Boolean {
        val setter = FLAG_SETTERS[key] ?: return false
        setter(state, on)
        return true
    }

    private class Choice(val scope: String, val prefix: String, val value: String, val on: Boolean)

    private fun applyChoice(
        choice: Choice,
        state: ClaudeSettings.State,
        models: List<String>,
    ): Boolean? = when (choice.prefix) {
        GUARD_MODE -> select(GuardMode.from(choice.value) != null, choice.on) {
            applyGuardMode(choice.scope, state, GuardMode.from(choice.value) ?: GuardMode.DEFAULT)
        }

        MODEL -> select(choice.value in models, choice.on) { state.model = choice.value }

        EFFORT -> select(EffortLevel.from(choice.value) != null, choice.on) { state.effort = choice.value }

        MODE -> select(PermissionMode.from(choice.value) != null, choice.on) {
            state.permissionMode = choice.value
        }

        else -> null
    }

    private fun applyGuardMode(scope: String, state: ClaudeSettings.State, chosen: GuardMode) {
        if (chosen == GuardMode.ALLOW_ALL) {
            SecuritySuspensions.guardOff(scope, state, SecuritySuspensions.Duration.FOREVER, System.currentTimeMillis())
        } else {
            SecuritySuspensions.guardOn(scope, state)
            state.guardMode = chosen.wire
        }
    }

    private fun applyList(
        scope: String,
        state: ClaudeSettings.State,
        prefix: String,
        value: String,
        on: Boolean,
    ): Boolean? =
        when (prefix) {
            RULE -> applyRule(scope, state, value, on)

            SOURCE -> toggle(value in LaunchDefaults.SETTING_SOURCES, state.settingSources, value, on) {
                state.settingSources = it
            }

            ALLOW -> toggle(value in ToolNaming.BUILTIN_TOOLS, state.allowedTools, value, on) {
                state.allowedTools = it
            }

            DENY -> toggle(value in ToolNaming.BUILTIN_TOOLS, state.disallowedTools, value, on) {
                state.disallowedTools = it
            }

            else -> null
        }

    private fun applyRule(scope: String, state: ClaudeSettings.State, value: String, on: Boolean): Boolean {
        val rule = SecurityRule.from(value) ?: return false
        val next = csvToggle(state.disabledSecurityRules, rule.name, on = !on)
        state.disabledSecurityRules = SecurityRule.canonicalCsv(csvItems(next))
        if (on) {
            state.securityRuleSuspensions =
                SecuritySuspensions.without(state.securityRuleSuspensions, rule, System.currentTimeMillis())
            SecuritySuspensions.releaseSessionScoped(scope, rule)
        }
        return true
    }

    private fun select(known: Boolean, on: Boolean, write: () -> Unit): Boolean {
        if (!known) return false
        if (on) write()
        return true
    }

    private fun toggle(known: Boolean, csv: String, value: String, on: Boolean, write: (String) -> Unit): Boolean {
        if (!known) return false
        write(csvToggle(csv, value, on))
        return true
    }

    internal fun csvItems(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    internal fun csvHas(csv: String, value: String): Boolean = value in csvItems(csv)

    private fun csvToggle(csv: String, value: String, on: Boolean): String {
        val current = csvItems(csv)
        val next = if (on) current + value else current.filterNot { it == value }
        return next.distinct().joinToString(",")
    }

    internal const val REMOTE_CONTROL = "remoteControl"

    internal const val APPROVAL = "approval"
    internal const val GUARD_MODE = "guardmode"
    internal const val MODEL = "model"
    internal const val EFFORT = "effort"
    internal const val MODE = "mode"
    internal const val RULE = "rule"
    internal const val SOURCE = "source"
    internal const val ALLOW = "allow"
    internal const val DENY = "deny"
    internal const val ALWAYS = "always"
    internal const val GOD_MODE = "godMode"
}
