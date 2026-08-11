package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.session.AgentStatus

/**
 * The ONE state vocabulary the page colours by: `running` · `completed` · `failed` · `stopped`.
 *
 * There used to be three. An agent sent `AgentStatus.name.lowercase()`, a background task sent a boolean the
 * tab bar turned into `done` and the dashboard turned into `completed`, and each of them had its own block of
 * CSS — so the same finished task was green in one view and grey in the other, and adding a state meant
 * editing four places and forgetting one. The host decides the word; the page only paints it.
 */
internal object JcefStatus {

    fun of(status: AgentStatus): String = when (status) {
        AgentStatus.RUNNING -> "running"

        AgentStatus.COMPLETED -> "completed"

        AgentStatus.FAILED -> "failed"

        // Cut off by a binary that is no longer there: from the outside that is a failure — the work did not
        // finish and nothing is going to finish it — but it is worth its own word in a tooltip.
        AgentStatus.STOPPED -> "stopped"
    }

    /** A background task has no lifecycle beyond "is it still listed": present means running. */
    fun of(running: Boolean): String = if (running) "running" else "completed"
}
