package dev.lain.claudejb.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.ClaudeSession
import java.util.concurrent.atomic.AtomicBoolean

internal object SafeAlarm {

    private val log = logger<SafeAlarm>()
    private val warned = AtomicBoolean(false)

    fun storeFailed() {
        log.warn("the IDE password safe refused to store a value (it did not read back)")
        if (!warned.compareAndSet(false, true)) return
        val app = ApplicationManager.getApplication() ?: return
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        app.invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
                .createNotification(
                    "Claude Code cannot store your settings securely",
                    "The IDE's password safe would not keep them: a value written to it could not be read " +
                        "back. Until this is fixed, your settings and your sign-in will not survive a " +
                        "restart — nothing is being written in plain text instead.<br><br>" +
                        "Check <b>Settings ▸ Appearance &amp; Behavior ▸ System Settings ▸ Passwords</b>, " +
                        "and on Linux that the keyring (GNOME Keyring / KWallet) is running and unlocked. " +
                        "The IDE log records the store's own error.",
                    NotificationType.ERROR,
                )
                .notify(null)
        }
    }
}
