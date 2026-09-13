package dev.lain.claudejb.controller.session.turn

import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.control.ControlProtocol
import kotlinx.serialization.json.JsonObject

class TurnControl(
    private val session: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Unit,
    private val fireState: () -> Unit,
) {

    fun interrupt() {
        if (!session.isRunning()) return
        edt {
            if (session.turn.interrupting) return@edt
            cancelPendingElicitations()
            session.cardManager.all().filter { it.elicitation == null }.forEach {
                write(ControlProtocol.permissionDeny(it.requestId, "Interrupted."))
            }
            session.prompts.clear()
            session.cardManager.clear()
            session.diffs.clearReviewDiffs()
            session.turn.interrupting = true
            fireState()
            session.controlClient.query(
                buildRequest = ControlProtocol::interruptRequest,
                onResult = { _: JsonObject? -> edt { finish() } },
                decode = { it },
            )
        }
    }

    private fun finish() {
        session.turn.reset()
        session.poll.pollQuota()
        fireState()
    }

    fun cancelPendingElicitations() {
        runCatching {
            session.cardManager.all().filter { it.elicitation != null }.forEach {
                write(ControlProtocol.elicitationResult(it.requestId, "cancel"))
            }
        }
    }
}
