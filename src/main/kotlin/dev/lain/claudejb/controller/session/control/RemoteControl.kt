package dev.lain.claudejb.controller.session.control

import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.session.transcript.TranscriptModel

class RemoteControl(
    private val queries: SessionQueries,
    private val transcript: TranscriptModel,
    private val fireState: () -> Unit,
) {

    @Volatile var enabled: Boolean = false
        private set

    @Volatile var error: String? = null
        private set

    fun set(on: Boolean, onSettled: () -> Unit) {
        queries.setRemoteControl(on) { outcome ->
            if (outcome.ok) enabled = outcome.enabled
            error = if (outcome.ok) null else refusal(outcome)
            fireState()
            transcript.add(Speaker.SYSTEM, notice(outcome))
            onSettled()
        }
    }

    private fun refusal(outcome: RemoteControlOutcome): String {
        val what = if (outcome.enabled) "switch Remote Control on" else "switch Remote Control off"
        return outcome.error?.takeIf { it.isNotBlank() }?.let { "Could not $what: $it" } ?: "Could not $what."
    }

    private fun notice(outcome: RemoteControlOutcome): String = when {
        !outcome.ok -> refusal(outcome) +
            " Remote Control has to be enabled for your account, and by your organisation on Team and Enterprise plans."

        !outcome.enabled -> "Remote Control is off. This chat keeps running in the IDE."

        outcome.sessionUrl != null -> "Remote Control is on — ${outcome.sessionUrl}"

        else -> "Remote Control is on. The session is listed at https://claude.ai/code"
    }
}
