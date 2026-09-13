package dev.lain.claudejb.model.session.launch

import dev.lain.claudejb.model.settings.ClaudeSettings

data class LaunchOptions(
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String = "default",
    val thinkingTokens: Int? = null,
    val allowedTools: String = "",
    val disallowedTools: String = "",
    val settingSources: String = "user,project,local",
    val includePartialMessages: Boolean = true,
    val customMcpServers: String = "",
    val ideRules: Set<IdeRule> = emptySet(),
    val ideIntegration: Boolean = false,
    val ideSockets: Map<IdeServer, String> = emptyMap(),
    val maxTurns: Int? = null,
    val maxBudgetUsd: Double? = null,
    val fallbackModel: String? = null,
    val addDirs: List<String> = emptyList(),
    val betas: String? = null,
    val strictMcpConfig: Boolean = false,
    val sessionId: String? = null,
    val fork: Boolean = false,
) {

    fun relaunchDiffers(other: LaunchOptions): Boolean = argvOnly() != other.argvOnly()

    private fun argvOnly(): LaunchOptions = copy(
        model = null,
        effort = null,
        permissionMode = "",
        thinkingTokens = null,
        allowedTools = "",
        disallowedTools = "",
        sessionId = null,
        fork = false,
    )

    companion object {

        fun from(settings: ClaudeSettings): LaunchOptions {
            val s = settings.state
            return LaunchOptions(
                model = s.model.ifBlank { null },
                effort = s.effort.ifBlank { null },
                permissionMode = s.permissionMode.ifBlank { "default" },
                thinkingTokens = s.thinkingTokens.takeIf { it > 0 },
                allowedTools = s.allowedTools,
                disallowedTools = s.disallowedTools,
                settingSources = s.settingSources,
                includePartialMessages = s.includePartialMessages,
                customMcpServers = s.customMcpServers,
                ideRules = IdeRule.parse(s.ideMcp.rules),
                ideIntegration = s.ideMcp.enabled,
                maxTurns = settings.maxTurns,
                maxBudgetUsd = settings.maxBudgetUsd,
                fallbackModel = settings.fallbackModel,
                addDirs = settings.addDirs,
                betas = settings.betas,
                strictMcpConfig = settings.strictMcpConfig,
            )
        }
    }
}
