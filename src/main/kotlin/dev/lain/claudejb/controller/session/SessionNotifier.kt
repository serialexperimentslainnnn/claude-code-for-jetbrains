package dev.lain.claudejb.controller.session

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.env.requiresTrustPrompt
import dev.lain.claudejb.model.settings.env.setExecutionTrusted
import dev.lain.claudejb.util.PluginIdentity

class SessionNotifier(private val project: Project) {

    fun error(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification("Claude Code", content, NotificationType.ERROR)
            .notify(project)
    }

    fun info(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification("Claude Code", content, NotificationType.INFORMATION)
            .notify(project)
    }

    fun info(content: String, actionLabel: String, action: () -> Unit) =
        withAction(content, NotificationType.INFORMATION, actionLabel, action)

    fun warning(content: String, actionLabel: String, action: () -> Unit) =
        withAction(content, NotificationType.WARNING, actionLabel, action)

    private fun withAction(content: String, type: NotificationType, actionLabel: String, action: () -> Unit) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification("Claude Code", content, type)
            .addAction(NotificationAction.createSimple(actionLabel) { action() })
            .notify(project)
    }

    fun missingBinary() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification(
                "Claude Code",
                "The 'claude' binary was not found on PATH or in a typical location. " +
                    "Install Claude Code (https://claude.com/code), or set the executable path manually.",
                NotificationType.ERROR,
            )
            .addAction(
                NotificationAction.createSimple("Configure paths…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginIdentity.SETTINGS_ID)
                },
            )
            .notify(project)
    }

    fun ensureExecTrust(settings: ClaudeSettings): Boolean {
        if (!settings.requiresTrustPrompt()) return true
        val choice = Messages.showYesNoDialog(
            project,
            "This project is configured to run an environment script and/or a custom MCP server when a Claude " +
                "Code session starts. These execute code on your machine. Only allow this if you trust this " +
                "project. Run them?",
            "Trust Claude Code Execution Config?",
            "Trust and run",
            "Cancel",
            Messages.getWarningIcon(),
        )
        return if (choice == Messages.YES) {
            settings.setExecutionTrusted(true)
            true
        } else {
            error("Launch cancelled. Review the source script / custom MCP servers in Settings, then try again.")
            false
        }
    }

    companion object {
        fun remoteProjectRefusal(root: String?): String {
            val where = root ?: "this location"
            return "Claude Code will not run on a network or remote drive ($where). Running an autonomous agent " +
                "rooted on shared storage is a security risk it refuses by design — move the project to a local " +
                "disk. For unrestricted use, run the `claude` CLI directly."
        }
    }
}
