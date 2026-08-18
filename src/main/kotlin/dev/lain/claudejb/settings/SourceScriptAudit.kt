package dev.lain.claudejb.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.permission.SensitiveGuard
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Reads the user's environment script and judges it **before it is sourced**, with the same rules the guard
 * applies to anything the agent asks to run.
 *
 * ### Why this file exists at all
 * `ClaudeSettings.State.sourceScript` is the one thing in this plugin that executes arbitrary code the plugin
 * itself supplies to a shell — `EnvScriptLoader` sources it in a login shell to capture the environment. There is
 * already a trust gate in front of it (`SettingsExecutionTrust`: a project may not silently arrive carrying one),
 * and that gate answers a different question — *may this run* — while saying nothing about *what is in it*.
 *
 * The gap that closes here is the one that matters most for the guard's own promise: the deterministic lock
 * intercepts every tool call, and a variable a sourced script exports is invisible to it
 * (`SettingsSensitivePolicy` cannot source a shell on the request thread). So a script that exported
 * `CREDS=~/.ssh/id_rsa`, or that dumped a key on the way past, was the one piece of code in the whole design that
 * ran with nothing looking at it. Now the same rule set reads it first.
 *
 * ### What a finding does, and why it is a refusal rather than a warning
 * The script is **not sourced** and the session launches without it, with a notification naming what tripped.
 * Sourcing it anyway and warning afterwards would be a warning about something that has already happened: a
 * `source` is not a preview, its side effects are the point, and by the time the notification is on screen the key
 * has been read. Losing the script's environment degrades a session (a `claude` binary that may not be on `PATH`);
 * running it does not degrade anything, it just happens. Fail closed.
 *
 * The user is never stuck: the notification names the script and the finding, so the fix is theirs to make in a
 * file they own — or to accept, by switching the rule off in Settings ▸ Claude Code ▸ Security, which is the same
 * escape hatch every other rule has.
 */
internal object SourceScriptAudit {

    private val log = logger<SourceScriptAudit>()

    /**
     * How much of the script is read. The same order of magnitude as the guard's own reader: a shell profile is
     * kilobytes, and a "profile" that is half a megabyte is not one.
     */
    private const val MAX_BYTES = 512L * 1024

    /**
     * The reason this script must not be sourced, or null when it is clean (or absent, or unreadable).
     *
     * An unreadable script returns null rather than a refusal, deliberately: `EnvScriptLoader` already logs and
     * degrades when the file is missing, and inventing a security finding out of "the file is not there" would put
     * a security notification in front of somebody whose only mistake is a stale path.
     */
    fun findingIn(scriptPath: String?, policy: SensitiveGuard.Policy): String? {
        val path = scriptPath?.trim().orEmpty()
        if (path.isEmpty()) return null
        val text = runCatching {
            val file = File(path)
            if (!file.isFile || file.length() > MAX_BYTES) return@runCatching null
            file.readText()
        }.getOrNull() ?: return null
        // Judged as the command it is: the whole text under a `command` key, which is the shape the guard's own
        // scanner tokenises, de-obfuscates and expands. `Bash` as the caller name because that is what a login
        // shell sourcing a file IS — and because a trusted caller is the WEAKER reading, so a finding here is a
        // finding for every caller.
        val decision = SensitiveGuard.evaluate(buildJsonObject { put("command", text) }, policy)
        return decision.reason.takeIf { decision.verdict != SensitiveGuard.Verdict.ALLOW }
    }

    /** Says out loud that the environment script was skipped, and why. */
    fun refused(scriptPath: String, reason: String) {
        log.warn("not sourcing '$scriptPath': $reason")
        val app = ApplicationManager.getApplication() ?: return
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        app.invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
                .createNotification(
                    "Claude Code did not run your environment script",
                    "<b>$scriptPath</b> was not sourced, so this session starts without the environment it " +
                        "provides.<br><br>The deterministic security lock read it first and found: " +
                        "<i>$reason</i><br><br>" +
                        "It is your file: fix the line, point <b>Settings ▸ Claude Code ▸ Executable</b> at " +
                        "another script, or accept it by switching that rule off in " +
                        "<b>Settings ▸ Claude Code ▸ Security</b>.",
                    NotificationType.WARNING,
                )
                .notify(null)
        }
    }
}
