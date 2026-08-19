package dev.lain.claudejb.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.PermissionMode
import java.util.concurrent.atomic.AtomicBoolean

internal object LegacySettingsNotice {

    private val log = logger<LegacySettingsNotice>()
    private val told = AtomicBoolean(false)

    fun permissionModeRefused(wire: String) {
        log.warn(
            "not adopting the permission mode '$wire' from a project's claude-code.xml: the settings are global " +
                "since 5.5.0, so a repository may not weaken them — keeping '${LegacyPermissionMode.SAFE}'",
        )
        if (!told.compareAndSet(false, true)) return
        val app = ApplicationManager.getApplication() ?: return
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        app.invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
                .createNotification(
                    "Claude Code kept its default permission mode",
                    "A settings file in this project (<code>.idea/claude-code.xml</code>, from before 5.5.0) asked " +
                        "for the <b>${PermissionMode.labelFor(wire)}</b> permission mode. Everything else in it was " +
                        "adopted, but that was not: settings are shared by every project now, and a file that comes " +
                        "with a repository does not get to decide how much Claude Code asks you.<br><br>" +
                        "Claude Code will keep asking each time. If that mode is what you want, choose it yourself " +
                        "in <b>Settings ▸ Claude Code</b>.",
                    NotificationType.WARNING,
                )
                .notify(null)
        }
    }
}
