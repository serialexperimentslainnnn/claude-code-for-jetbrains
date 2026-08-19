package dev.lain.claudejb.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.Provider
import dev.lain.claudejb.ui.ClaudeSettingsConfigurable

class SessionLiveSettings(
    private val session: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    private val write: (String) -> Unit,
) {

    fun changeModel(value: String?) {
        val resolved = if (value == ClaudeSession.RECOMMENDED_ALIAS) session.preferredDefaultModel() else value
        val previous = session.model
        session.model = resolved
        if (session.isRunning()) {
            session.controlClient.send({ id -> ControlProtocol.setModelRequest(id, resolved) }) { res ->
                if (!res.success) edt { revertModel(previous, resolved, res.error) }
            }
        }
        fireState()
    }

    private fun revertModel(previous: String?, attempted: String?, error: String?) {
        if (session.model != attempted) return
        session.model = previous
        val name = attempted?.let { LegacyModels.labelFor(it) ?: it } ?: "That model"
        val kept = previous?.let { LegacyModels.labelFor(it) ?: it } ?: "the previous model"
        val reason = error?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        session.transcript.add(Speaker.SYSTEM, "$name is not available on this account$reason — kept $kept.")
        fireState()
    }

    fun changePermissionMode(mode: String) {
        session.permissionMode = mode
        ClaudeSettings.getInstance(project).update { it.permissionMode = mode }
        if (session.isRunning()) {
            val wire = SessionLauncher.binaryPermissionMode(session.permissionMode)
            write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), wire))
        }
        fireState()
    }

    fun changeEffort(value: String?) {
        session.effort = value
        fireState()
    }

    fun changeProvider(target: Provider) {
        val settings = ClaudeSettings.getInstance(project)
        if (target == settings.provider) return
        if (target.requiresApiKey && settings.getProviderApiKey(target).isBlank()) {
            notifyConfigureProviderKey(target)
            return
        }
        val wasRunning = session.isRunning()
        settings.update { it.provider = target.id }
        session.cachedEnv = null
        fireState()
        if (wasRunning) {
            session.systemNotice("Provider → ${target.label} — restarting session.")
            session.restart(resume = true)
        }
    }

    private fun notifyConfigureProviderKey(target: Provider) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "${target.label} needs its own API key. Configure it in Settings — the provider isn't switched " +
                    "until a key is set, and your Anthropic credentials are never used for another provider.",
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple("Configure…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java)
                },
            )
            .notify(project)
    }

    fun changeThinkingTokens(tokens: Int?) {
        if (tokens == session.thinkingTokens) return
        val wasRunning = session.isRunning()
        session.thinkingTokens = tokens
        fireState()
        if (wasRunning) {
            val state = if (tokens != null) "on" else "off"
            session.systemNotice("Extended thinking $state — restarting session.")
            session.restart(resume = true)
        }
    }

    @Suppress("LongParameterList")
    fun configureLaunchOptions(
        allowedTools: String,
        disallowedTools: String,
        settingSources: String,
        includePartialMessages: Boolean,
        ideMcpEnabled: Boolean = false,
        ideMcpTransport: String = "sse",
        ideMcpPort: Int = ClaudeSession.DEFAULT_IDE_MCP_PORT,
        customMcpServers: String = "",
        maxTurns: Int? = null,
        maxBudgetUsd: Double? = null,
        fallbackModel: String? = null,
        addDirs: List<String> = emptyList(),
        betas: String? = null,
        strictMcpConfig: Boolean = false,
    ) {
        session.allowedTools = allowedTools
        session.disallowedTools = disallowedTools
        session.settingSources = settingSources
        session.includePartialMessages = includePartialMessages
        session.ideMcpEnabled = ideMcpEnabled
        session.ideMcpTransport = ideMcpTransport.ifBlank { "sse" }
        session.ideMcpPort = ideMcpPort.takeIf { it in ClaudeSession.VALID_PORTS } ?: ClaudeSession.DEFAULT_IDE_MCP_PORT
        session.customMcpServers = customMcpServers
        session.maxTurns = maxTurns
        session.maxBudgetUsd = maxBudgetUsd
        session.fallbackModel = fallbackModel
        session.addDirs = addDirs
        session.betas = betas
        session.strictMcpConfig = strictMcpConfig
        fireState()
    }

    fun cyclePermissionMode() {
        val order = ClaudeSession.PERMISSION_MODES_CYCLE
        val idx = order.indexOf(session.permissionMode).let { if (it < 0) 0 else it }
        changePermissionMode(order[(idx + 1) % order.size])
    }
}
