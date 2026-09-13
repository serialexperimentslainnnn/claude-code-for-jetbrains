package dev.lain.claudejb.view.guard

import com.intellij.openapi.application.ApplicationManager
import dev.lain.claudejb.controller.commands.GuardPromptedActions
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.SecretStore
import dev.lain.claudejb.model.settings.SettingsScope
import dev.lain.claudejb.model.settings.guard.GuardAlert
import dev.lain.claudejb.model.settings.guard.GuardAlertLog
import dev.lain.claudejb.util.edt
import dev.lain.claudejb.util.logger
import dev.lain.claudejb.view.guard.JcefGuardData
import dev.lain.claudejb.view.window.JcefChatPanel

internal class GuardFeed(private val panel: JcefChatPanel) {

    fun push() {
        val scope = scope()
        val sessionId = panel.session.sessionId
        val recorded = panel.session.guard.guardLog.recorded
        val dropped = panel.session.guard.guardLog.dropped
        offEdt {
            val json = JcefGuardData.guardJson(
                alerts = read(scope, sessionId),
                recorded = recorded,
                dropped = dropped,
                recording = !SecretStore.inert(),
                max = GuardAlertLog.MAX_ENTRIES,
            )
            edt(panel.project) { panel.host.exec("window.cc.guard && window.cc.guard($json)") }
        }
    }

    fun explain(id: String) {
        val scope = scope()
        val sessionId = panel.session.sessionId
        offEdt {
            val alert = read(scope, sessionId).firstOrNull { JcefGuardData.idOf(it) == id }
            val prompt = alert?.let(GuardPromptedActions::explainBlockPrompt)
            edt(panel.project) {
                if (prompt == null) {
                    panel.session.systemNotice(GuardPromptedActions.ENTRY_GONE)
                } else {
                    panel.session.sendSideQuestion(prompt)
                }
            }
        }
    }

    private fun scope(): SettingsScope = ClaudeSettings.getInstance(panel.project).scope

    private fun read(scope: SettingsScope, sessionId: String?): List<GuardAlert> {
        if (sessionId.isNullOrBlank()) return emptyList()
        return runCatching { GuardAlertLog.forSession(scope, sessionId) }
            .onFailure { logger.warn("Claude Code could not read the guard alert log", it) }
            .getOrDefault(emptyList())
    }

    private fun offEdt(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(block).onFailure { logger.warn("Claude Code could not answer the guard view", it) }
        }
    }

    private companion object {
        private val logger = logger<GuardFeed>()
    }
}
