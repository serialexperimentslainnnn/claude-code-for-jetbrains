package dev.lain.claudejb.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.PermissionMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Says out loud that something in a project's old settings file was NOT adopted, and why.
 *
 * A migration that silently drops a value is indistinguishable from one that quietly keeps it — and the value
 * dropped here is a security setting ([LegacyPermissionMode]), so the user has to be able to tell which of the
 * two happened. Two things they cannot otherwise know: that the file they never opened asked for a weaker
 * permission mode, and that the plugin is running on the default instead.
 *
 * Once per IDE run, like [SafeAlarm]: telling somebody once is information, and the same sentence at every
 * project they open is noise they learn to dismiss. The window is narrow anyway — the legacy path only runs
 * while nothing has been stored yet.
 */
internal object LegacySettingsNotice {

    private val log = logger<LegacySettingsNotice>()
    private val told = AtomicBoolean(false)

    /** Reports that [wire] was left out of the migration and the default is in force instead. */
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
