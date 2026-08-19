package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.lain.claudejb.session.ClaudeSession

object ReviewPrompt {

    const val TURNS_BEFORE_ASK = 25

    private const val TURNS_KEY = "claudejb.successfulTurns"
    private const val ASKED_KEY = "claudejb.reviewAsked"

    const val REVIEW_URL = "https://plugins.jetbrains.com/plugin/31965-claude-code-native/reviews"

    fun shouldAsk(successfulTurns: Int, asked: Boolean): Boolean =
        !asked && successfulTurns >= TURNS_BEFORE_ASK

    fun recordTurn(successfulTurns: Int): Int =
        if (successfulTurns >= TURNS_BEFORE_ASK) TURNS_BEFORE_ASK else successfulTurns + 1

    fun onSuccessfulTurn(project: Project) {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(ASKED_KEY, false)) return
        val turns = recordTurn(props.getInt(TURNS_KEY, 0))
        props.setValue(TURNS_KEY, turns, 0)
        if (!shouldAsk(turns, asked = false)) return
        props.setValue(ASKED_KEY, true)
        show(project)
    }

    private fun show(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeSession.NOTIFICATION_GROUP)
            .createNotification(
                "Enjoying Claude Code Native?",
                "A quick review on the JetBrains Marketplace genuinely helps other developers find it. " +
                    "This is the only time you'll be asked.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Write a review") { BrowserUtil.browse(REVIEW_URL) })
            .addAction(NotificationAction.createSimple("No thanks") { })
            .notify(project)
    }
}
