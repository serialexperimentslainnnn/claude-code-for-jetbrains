package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EffortLevel
import dev.lain.claudejb.session.PermissionMode
import dev.lain.claudejb.session.ToolNaming
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.GuardMode
import dev.lain.claudejb.settings.SecuritySuspensions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

internal object JcefSettingsMenu {

    internal data class Selected(
        val models: List<ModelInfo>,
        val model: String,
        val effort: String?,
        val mode: String,
        val approvals: Map<SecurityRule, Set<String>> = emptyMap(),
        val remoteControl: Boolean = false,
    )

    fun json(scope: String, state: ClaudeSettings.State, session: ClaudeSession): JsonArray =
        json(scope, state, selectedIn(session))

    internal fun json(scope: String, state: ClaudeSettings.State, selected: Selected): JsonArray = buildJsonArray {
        modelRows(selected)
        effortRows(selected)
        modeRows(selected)
        remoteControlRows(selected)
        chatRows(state)
        securityRows(scope, state)
        sessionApprovalRows(selected.approvals)
        sourceRows(state)
        toolRows(ALLOW, "Allowed tools", state.allowedTools, deferred = true)
        toolRows(DENY, "Disallowed tools", state.disallowedTools, deferred = true)
        toolRows(ALWAYS, "Always allowed tools", state.alwaysAllowTools, deferred = false)
        mcpRows(state)
    }

    fun apply(scope: String, state: ClaudeSettings.State, key: String, on: Boolean, models: List<String>): Boolean {
        val prefix = key.substringBefore(':', missingDelimiterValue = "")
        if (prefix.isEmpty()) return applyFlag(state, key, on)
        val value = key.substringAfter(':')
        val choice = Choice(scope, prefix, value, on)
        return applyChoice(choice, state, models) ?: applyList(scope, state, prefix, value, on) ?: false
    }

    fun applyToSession(session: ClaudeSession, key: String, on: Boolean) {
        if (!on) return
        val value = key.substringAfter(':', missingDelimiterValue = "")
        when (key.substringBefore(':', missingDelimiterValue = "")) {
            MODEL -> session.settings.changeModel(value)
            EFFORT -> session.settings.changeEffort(value)
            MODE -> session.settings.changePermissionMode(value)
            else -> {}
        }
    }

    fun isRemoteControl(key: String): Boolean = key == REMOTE_CONTROL

    fun alwaysAllowTool(key: String): String? {
        if (!key.startsWith("$ALWAYS:")) return null
        return key.removePrefix("$ALWAYS:").takeIf { it in ToolNaming.BUILTIN_TOOLS }
    }

    private fun JsonArrayBuilder.modelRows(selected: Selected) {
        selected.models.filter { it.value != ClaudeSession.RECOMMENDED_ALIAS }.forEach { m ->
            val label = JcefModelLabels.modelDisplayLabel(m)
            entry("$MODEL:${m.value}", "Model", label, m.value == selected.model, radio = true)
        }
    }

    private fun JsonArrayBuilder.effortRows(selected: Selected) {
        EffortLevel.entries.forEach { level ->
            val label = level.wire.replaceFirstChar { it.uppercase() }
            entry("$EFFORT:${level.wire}", "Effort", label, level.wire == selected.effort, radio = true)
        }
    }

    private fun JsonArrayBuilder.modeRows(selected: Selected) {
        ClaudeSession.PERMISSION_MODES.forEach { wire ->
            entry("$MODE:$wire", "Permission mode", PermissionMode.labelFor(wire), wire == selected.mode, radio = true)
        }
    }

    private fun JsonArrayBuilder.remoteControlRows(selected: Selected) {
        entry(
            REMOTE_CONTROL,
            "Remote control",
            "Drive this chat from claude.ai",
            selected.remoteControl,
            hostOwned = true,
        )
    }

    private fun JsonArrayBuilder.chatRows(s: ClaudeSettings.State) {
        entry("restoreChats", "Chat", "Restore open chats on startup", s.restoreOpenChatsOnStartup)
        entry("reduceMotion", "Chat", "Reduce motion", s.reduceMotion)
        entry("checkpointing", "Chat", "Let Claude rewind file changes", s.enableFileCheckpointing)
        entry("partialMessages", "Chat", "Stream partial messages", s.includePartialMessages)
    }

    private fun JsonArrayBuilder.securityRows(scope: String, s: ClaudeSettings.State) {
        val disabled = csvItems(s.disabledSecurityRules)
        val now = System.currentTimeMillis()
        val suspended = SecuritySuspensions.active(s.securityRuleSuspensions, now) +
            SecuritySuspensions.sessionSuspended(scope)
        val mode = if (SecuritySuspensions.guardSuspended(scope, s, now)) {
            GuardMode.ALLOW_ALL
        } else {
            GuardMode.from(s.guardMode) ?: GuardMode.DEFAULT
        }
        GuardMode.entries.forEach { m ->
            entry("$GUARD_MODE:${m.wire}", "Guard mode", m.label, m == mode, radio = true)
        }
        SecurityCategory.entries.forEach { category ->
            SecurityRule.of(category).forEach { rule ->
                val enforced = rule.name !in disabled && rule !in suspended
                entry("$RULE:${rule.name}", "Security", rule.label, enforced, sub = category.label)
            }
        }
    }

    private fun JsonArrayBuilder.sessionApprovalRows(approvals: Map<SecurityRule, Set<String>>) {
        approvals.forEach { (rule, commands) ->
            commands.forEach { command ->
                entry("$APPROVAL:${rule.name}:$command", "Approved in this chat", command, true, sub = rule.label)
            }
        }
    }

    fun sessionApproval(key: String): Pair<SecurityRule, String>? {
        if (!key.startsWith("$APPROVAL:")) return null
        val rest = key.removePrefix("$APPROVAL:")
        val rule = SecurityRule.from(rest.substringBefore(':', "")) ?: return null
        val command = rest.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
        return rule to command
    }

    private fun JsonArrayBuilder.sourceRows(s: ClaudeSettings.State) {
        ClaudeSession.SETTING_SOURCES.forEach { source ->
            val label = source.replaceFirstChar { it.uppercase() }
            entry("$SOURCE:$source", "Setting sources", label, csvHas(s.settingSources, source), deferred = true)
        }
    }

    private fun JsonArrayBuilder.toolRows(prefix: String, group: String, csv: String, deferred: Boolean) {
        ToolNaming.BUILTIN_TOOLS.forEach { tool ->
            entry("$prefix:$tool", group, tool, csvHas(csv, tool), deferred = deferred)
        }
    }

    private fun JsonArrayBuilder.mcpRows(s: ClaudeSettings.State) {
        entry("ideMcp", "MCP", "JetBrains MCP server", s.ideMcpEnabled, deferred = true)
        entry("strictMcp", "MCP", "Only the MCP servers configured here", s.strictMcpConfig, deferred = true)
    }

    private fun JsonArrayBuilder.entry(
        key: String,
        group: String,
        label: String,
        on: Boolean,
        radio: Boolean = false,
        deferred: Boolean = false,
        sub: String? = null,
        hostOwned: Boolean = false,
    ) = addJsonObject {
        put("key", key)
        put("group", group)
        if (sub != null) put("sub", sub)
        put("label", label)
        put("on", on)
        put("type", if (radio) TYPE_RADIO else TYPE_CHECK)
        put("deferred", deferred)
        if (hostOwned) put("hostOwned", true)
    }

    private val FLAG_SETTERS: Map<String, (ClaudeSettings.State, Boolean) -> Unit> = mapOf(
        "restoreChats" to { s, on -> s.restoreOpenChatsOnStartup = on },
        "reduceMotion" to { s, on -> s.reduceMotion = on },
        "checkpointing" to { s, on -> s.enableFileCheckpointing = on },
        "partialMessages" to { s, on -> s.includePartialMessages = on },
        "ideMcp" to { s, on -> s.ideMcpEnabled = on },
        "strictMcp" to { s, on -> s.strictMcpConfig = on },
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

            SOURCE -> toggle(value in ClaudeSession.SETTING_SOURCES, state.settingSources, value, on) {
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

    private fun csvItems(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun csvHas(csv: String, value: String): Boolean = value in csvItems(csv)

    private fun csvToggle(csv: String, value: String, on: Boolean): String {
        val current = csvItems(csv)
        val next = if (on) current + value else current.filterNot { it == value }
        return next.distinct().joinToString(",")
    }

    private fun selectedIn(session: ClaudeSession) = Selected(
        models = session.models,
        model = session.model ?: session.preferredDefaultModel(),
        effort = session.effort,
        mode = session.permissionMode,
        approvals = session.guardApprovals.all(),
        remoteControl = session.remoteControlEnabled,
    )

    internal const val REMOTE_CONTROL = "remoteControl"

    private const val APPROVAL = "approval"
    private const val GUARD_MODE = "guardmode"
    private const val MODEL = "model"
    private const val EFFORT = "effort"
    private const val MODE = "mode"
    private const val RULE = "rule"
    private const val SOURCE = "source"
    private const val ALLOW = "allow"
    private const val DENY = "deny"
    private const val ALWAYS = "always"

    private const val TYPE_CHECK = "check"
    private const val TYPE_RADIO = "radio"
}
