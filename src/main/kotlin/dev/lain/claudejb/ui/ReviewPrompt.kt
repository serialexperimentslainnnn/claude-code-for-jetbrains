package dev.lain.claudejb.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.lain.claudejb.session.ClaudeSession

/**
 * Asks — **once, ever, per user** — for a Marketplace review, after enough successful turns that the person
 * demonstrably uses and keeps using the plugin.
 *
 * ### Why this is deliberately timid
 * On the JetBrains Marketplace the *rating* is a stronger ranking signal than the download count: measured on
 * the live "Claude Code" query, a plugin with 8.6k downloads and a 4.54 rating outranks several with 4–10× the
 * downloads, while the official plugin's 4.4M downloads still land it below them on a 2.38 rating. So a rating is
 * worth asking for — but an annoying prompt earns *low* ratings, which under that same weighting is far worse
 * than having none. Every parameter here is chosen to make it impossible to experience this as nagging:
 *
 *  - it needs [TURNS_BEFORE_ASK] **successful** turns first (errors and interrupts don't count), so it can only
 *    ever reach someone who has actually got value out of it repeatedly;
 *  - it is a non-modal IDE balloon — ignorable by doing nothing, never a dialog that blocks work;
 *  - it fires **at most once for the lifetime of the installation**, application-wide (not per project), and the
 *    flag is written *before* the balloon is shown, so even a crash can't cause a second ask;
 *  - dismissing, rating, or "don't ask" all end it permanently and identically. There is no "remind me later"
 *    on purpose: a deferral is just a nag with extra steps.
 *
 * The counter and the flag live in application-level [PropertiesComponent] (not project settings), so opening a
 * second project doesn't reset progress or produce a second prompt. [shouldAsk] and [recordTurn] are pure so the
 * policy is unit-testable without an IDE.
 */
object ReviewPrompt {

    /** Successful turns before we ask. High enough that only a repeat user is ever prompted. */
    const val TURNS_BEFORE_ASK = 25

    private const val TURNS_KEY = "claudejb.successfulTurns"
    private const val ASKED_KEY = "claudejb.reviewAsked"

    /** The plugin's Marketplace review tab. */
    const val REVIEW_URL = "https://plugins.jetbrains.com/plugin/31965-claude-code-native/reviews"

    /**
     * Pure policy: ask only once, and only past the threshold. [asked] short-circuits so a user who has already
     * seen it (or opted out) is never counted or prompted again.
     */
    fun shouldAsk(successfulTurns: Int, asked: Boolean): Boolean =
        !asked && successfulTurns >= TURNS_BEFORE_ASK

    /** Pure: the next counter value. Saturates at [TURNS_BEFORE_ASK] so the number can't grow without bound. */
    fun recordTurn(successfulTurns: Int): Int =
        if (successfulTurns >= TURNS_BEFORE_ASK) TURNS_BEFORE_ASK else successfulTurns + 1

    /**
     * Counts one successful turn and, at the threshold, shows the one-time review balloon. Call on the EDT after
     * a non-error [ClaudeEvent.Result]. Cheap and allocation-free on the overwhelmingly common path (already
     * asked → a single boolean read).
     */
    fun onSuccessfulTurn(project: Project) {
        val props = PropertiesComponent.getInstance() // application-level: once per user, not once per project
        if (props.getBoolean(ASKED_KEY, false)) return
        val turns = recordTurn(props.getInt(TURNS_KEY, 0))
        props.setValue(TURNS_KEY, turns, 0)
        if (!shouldAsk(turns, asked = false)) return
        // Mark BEFORE showing: if anything goes wrong after this point we under-ask rather than ask twice.
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
            .addAction(NotificationAction.createSimple("No thanks") { /* already flagged — nothing to do */ })
            .notify(project)
    }
}
