package dev.lain.claudejb.controller.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.protocol.control.ControlProtocol
import dev.lain.claudejb.model.session.history.LegacyModels
import dev.lain.claudejb.model.session.launch.LaunchOptions
import dev.lain.claudejb.model.session.launch.SessionLauncher
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.LaunchDefaults
import dev.lain.claudejb.model.settings.Provider
import dev.lain.claudejb.util.PluginIdentity

class SessionLiveSettings(
    private val session: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val fireState: () -> Unit,
    private val write: (String) -> Unit,
) {

    fun changeModel(value: String?, persist: Boolean = true) {
        val resolved = resolve(value)
        val previous = session.launch.model
        session.launch = session.launch.copy(model = resolved)
        if (persist) ClaudeSettings.getInstance(project).update { it.model = value.orEmpty() }
        if (session.isRunning()) {
            session.controlClient.send({ id -> ControlProtocol.setModelRequest(id, resolved) }) { res ->
                if (!res.success) edt { revertModel(previous, resolved, res.error) }
            }
        }
        fireState()
    }

    private fun revertModel(previous: String?, attempted: String?, error: String?) {
        if (session.launch.model != attempted) return
        session.launch = session.launch.copy(model = previous)
        val name = attempted?.let { LegacyModels.labelFor(it) ?: it } ?: "That model"
        val kept = previous?.let { LegacyModels.labelFor(it) ?: it } ?: "the previous model"
        val reason = error?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        session.transcript.add(Speaker.SYSTEM, "$name is not available on this account$reason — kept $kept.")
        fireState()
    }

    fun changePermissionMode(mode: String) {
        session.launch = session.launch.copy(permissionMode = mode)
        ClaudeSettings.getInstance(project).update { it.permissionMode = mode }
        if (session.isRunning()) {
            val wire = SessionLauncher.binaryPermissionMode(mode)
            write(ControlProtocol.setPermissionModeRequest(ControlProtocol.newRequestId(), wire))
        }
        fireState()
    }

    fun changeEffort(value: String?, persist: Boolean = true) {
        session.launch = session.launch.copy(effort = value)
        if (persist) ClaudeSettings.getInstance(project).update { it.effort = value.orEmpty() }
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
        session.lifecycle.cachedEnv = null
        fireState()
        if (wasRunning) {
            session.systemNotice("Provider → ${target.label} — restarting session.")
            session.restart(resume = true)
        }
    }

    private fun notifyConfigureProviderKey(target: Provider) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "${target.label} needs its own API key. Configure it in Settings — the provider isn't switched " +
                    "until a key is set, and your Anthropic credentials are never used for another provider.",
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple("Configure…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginIdentity.SETTINGS_ID)
                },
            )
            .notify(project)
    }

    fun changeThinkingTokens(tokens: Int?, persist: Boolean = true) {
        if (persist) ClaudeSettings.getInstance(project).update { it.thinkingTokens = tokens ?: 0 }
        if (tokens == session.launch.thinkingTokens) return
        val wasRunning = session.isRunning()
        session.launch = session.launch.copy(thinkingTokens = tokens)
        fireState()
        if (wasRunning) {
            val state = if (tokens != null) "on" else "off"
            session.systemNotice("Extended thinking $state — restarting session.")
            session.restart(resume = true)
        }
    }

    fun adopt(next: LaunchOptions) {
        val before = session.launch
        val relaunch = before.relaunchDiffers(next)
        val thinkingChanged = before.thinkingTokens != next.thinkingTokens
        if (resolve(before.model) != resolve(next.model)) changeModel(next.model, persist = false)
        if (before.effort != next.effort) changeEffort(next.effort, persist = false)
        if (before.permissionMode != next.permissionMode) changePermissionMode(next.permissionMode)
        changeThinkingTokens(next.thinkingTokens, persist = false)
        val live = session.launch
        session.launch = next.copy(
            model = live.model,
            effort = live.effort,
            permissionMode = live.permissionMode,
            thinkingTokens = live.thinkingTokens,
            sessionId = live.sessionId,
        )
        fireState()
        if (relaunch && !thinkingChanged && session.isRunning()) {
            session.systemNotice("Launch settings changed — restarting session.")
            session.restart(resume = true)
        }
    }

    private fun resolve(value: String?): String? =
        if (value == LaunchDefaults.RECOMMENDED_ALIAS) session.catalog.preferredDefaultModel() else value
}
