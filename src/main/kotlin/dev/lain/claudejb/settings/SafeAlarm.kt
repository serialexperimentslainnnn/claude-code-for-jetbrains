package dev.lain.claudejb.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.ClaudeSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Says out loud that the IDE's password safe would not keep what we asked it to keep.
 *
 * **Why this exists.** The plugin puts everything it must remember into `PasswordSafe` — the OAuth
 * credential, the API keys, and since this release the settings — and `PasswordSafe.set` reports failure to
 * nobody: it returns `Unit`, throws nothing, and the backend logs its own SEVERE afterwards, if at all. On
 * this machine that was
 * `secret_password_store_sync error code 36 — Can't find session /org/freedesktop/secrets/session/928`: an
 * expired Secret Service session, with libsecret in use and KWallet never even asked (which is why its
 * "an application wants access" prompt never appeared and its wallets stayed empty).
 *
 * From the outside the plugin then looks like it is losing things at random: the settings do not survive a
 * restart, and the login is gone. Neither is something the user can guess, and both are things only they can
 * fix — unlock the keyring, or point Settings ▸ Appearance & Behavior ▸ System Settings ▸ Passwords at a
 * store that works. So it is a notification, not a log line.
 *
 * Once per IDE run: a failing store fails on every write, and one warning is information while twenty is
 * noise people learn to dismiss.
 */
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
