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

internal object SourceScriptAudit {

    private val log = logger<SourceScriptAudit>()

    private const val MAX_BYTES = 512L * 1024

    fun findingIn(scriptPath: String?, policy: SensitiveGuard.Policy): String? {
        val path = scriptPath?.trim().orEmpty()
        if (path.isEmpty()) return null
        val text = runCatching {
            val file = File(path)
            if (!file.isFile || file.length() > MAX_BYTES) return@runCatching null
            file.readText()
        }.getOrNull() ?: return null
        val decision = SensitiveGuard.evaluate(buildJsonObject { put("command", text) }, policy)
        return decision.reason.takeIf { decision.verdict != SensitiveGuard.Verdict.ALLOW }
    }

    fun untrusted(scriptPath: String) {
        log.warn("not sourcing '$scriptPath': the execution config has not been trusted for this project")
    }

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
                        "<b>Settings ▸ Claude Code Security</b>.",
                    NotificationType.WARNING,
                )
                .notify(null)
        }
    }
}
