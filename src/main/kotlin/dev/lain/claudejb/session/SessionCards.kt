package dev.lain.claudejb.session

import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.diff.EditSnapshot
import dev.lain.claudejb.permission.ElicitationCard
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.ElicitationRequest
import dev.lain.claudejb.protocol.parseElicitationFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The cards a turn puts to the user, and what happens when they answer one: a permission request, an
 * `AskUserQuestion`, an MCP elicitation. Reached as `session.cards.resolvePermission(…)`.
 *
 * **One subject, and the boundary is the answer.** Everything here happens between "the binary asked" and "the
 * control response is written" — presenting the card, capturing what an editable review diff ended up
 * containing, and replying allow or deny. What the guard decided BEFORE any of this is `PermissionBroker`'s, and
 * nothing in this file can change that verdict: it receives requests the broker chose to surface and it answers
 * them. It cannot approve a call the broker denied, because a denied call never becomes a card at all.
 *
 * **Why the review-edit path is here rather than in the diff layer.** [reviewEditOverride] is the one place the
 * user's own edit of a proposed change becomes the input the binary is told to write, so it belongs beside the
 * approval it modifies. It is fail-safe by construction — no edit yields null and the binary writes its own
 * version — which is a property worth keeping in one readable place instead of split across two files.
 */
class SessionCards(
    private val session: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Unit,
    private val firePermissions: () -> Unit,
    private val fireAttention: (AttentionReason) -> Unit,
) {

    /** Every card still waiting for an answer, in the order they arrived. */
    fun pending(): List<PendingPermission> = session.cardManager.all()

    /** The pre-write snapshot behind a reviewable tool call, so the transcript can still show its diff. */
    fun editSnapshot(toolUseId: String): EditSnapshot? = session.diffs.snapshot(toolUseId)

    internal fun present(request: PendingPermission) = edt {
        session.cardManager.present(request)
        fireAttention(AttentionReason.PERMISSION)
    }

    /** Invoked by the chat UI when the user clicks Accept/Reject on a permission card. */
    fun resolvePermission(
        requestId: String,
        allow: Boolean,
        denyMessage: String? = null,
        overrideInput: JsonObject? = null,
    ) {
        val request = session.cardManager.remove(requestId) ?: return
        if (allow) {
            approve(requestId, request, overrideInput)
        } else {
            reject(requestId, request, denyMessage)
        }
        firePermissions()
    }

    private fun approve(requestId: String, request: PendingPermission, overrideInput: JsonObject?) {
        if (request.reviewable) {
            // Snapshot/refresh stay on the ORIGINAL input: they describe the real file (before-text + path),
            // independent of any narrowed payload (e.g. an edited review diff) we actually send.
            DiffPresenter.filePathOf(request.input)?.let { session.diffs.markForRefresh(it) }
            // Snapshot before answering allow (the binary writes right after), so "View diff" works from the
            // transcript once the transient approval diff has closed. Synchronous read — small project files.
            request.toolUseId?.let { session.diffs.captureForReview(request.toolName, request.input, it) }
        }
        val effectiveInput = overrideInput ?: reviewEditOverride(requestId, request) ?: request.input
        // If the user edited the proposed content (or an override narrowed the write), repoint the captured
        // snapshot at the EFFECTIVE input so the transcript's inline diff + "View diff" show what was actually
        // written — not Claude's original proposal.
        if (request.reviewable && effectiveInput !== request.input) {
            request.toolUseId?.let { session.diffs.updateSnapshotInput(it, effectiveInput) }
        }
        write(ControlProtocol.permissionAllow(requestId, effectiveInput))
        session.systemNotice("Approved ${request.headline}")
        // Approving an ExitPlanMode plan leaves plan mode: the plugin is the source of truth for
        // permissionMode, so flip it back to default (and push set_permission_mode) — otherwise the binary
        // proceeds out of plan while the chip stays stuck on "plan".
        if (request.isPlan && session.permissionMode == PermissionMode.PLAN.wire) {
            session.settings.changePermissionMode(PermissionMode.DEFAULT.wire)
        }
    }

    /**
     * If an editable review diff was open and the user TWEAKED the proposed content, re-encodes the tool input
     * so the binary writes THEIR version (file_path preserved). Also closes the diff.
     *
     * Fail-safe by construction: no edit (or a read-only viewer) yields null, and the binary then writes its
     * own version — an unreadable document can never turn into a wrong write.
     */
    private fun reviewEditOverride(requestId: String, request: PendingPermission): JsonObject? =
        session.diffs.takeReviewEdit(requestId)?.let { (currentText, editedText) ->
            dev.lain.claudejb.diff.HunkSelection
                .encodeInput(request.toolName, request.input, currentText, editedText)
        }

    private fun reject(requestId: String, request: PendingPermission, denyMessage: String?) {
        session.diffs.closeReviewDiff(requestId) // reject → discard the review diff tab
        val message = denyMessage ?: "User rejected the ${request.toolName} request."
        write(ControlProtocol.permissionDeny(requestId, message))
        session.systemNotice("Rejected ${request.headline}")
    }

    /**
     * Invoked by the chat UI when the user submits answers to an AskUserQuestion card. Replies allow with
     * updatedInput = original input + {"answers": {questionText: chosenLabel}}; the binary echoes the choice
     * back as the tool result (verified against claude 2.1.150).
     */
    fun resolveQuestion(requestId: String, answers: Map<String, String>) {
        val request = session.cardManager.remove(requestId) ?: return
        val updated = buildJsonObject {
            request.input.forEach { (k, v) -> put(k, v) }
            put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
        }
        write(ControlProtocol.permissionAllow(requestId, updated))
        session.systemNotice("Answered Claude's question")
        firePermissions()
    }

    /**
     * Surfaces an MCP `elicitation` (binary -> host) as a non-modal card. The user's Accept/Decline/Cancel (via
     * [resolveElicitation]) is what writes the ElicitResult. EDT-confined, like every other card operation.
     */
    internal fun presentElicitation(requestId: String, req: ElicitationRequest) = present(
        PendingPermission(
            requestId = requestId,
            toolName = "elicitation",
            input = JsonObject(emptyMap()),
            title = req.displayName?.ifBlank { null } ?: req.title?.ifBlank { null } ?: req.mcpServerName,
            summary = "",
            reviewable = false,
            elicitation = ElicitationCard(
                serverName = req.mcpServerName,
                message = req.message,
                description = req.description?.ifBlank { null },
                mode = req.mode,
                url = req.url,
                fields = parseElicitationFields(req.requestedSchema),
            ),
        ),
    )

    /** Invoked by the chat UI when the user resolves an elicitation card. Writes the ElicitResult and clears it. */
    fun resolveElicitation(requestId: String, action: String, content: JsonObject?) {
        session.cardManager.remove(requestId) ?: return
        write(ControlProtocol.elicitationResult(requestId, action, content))
        session.systemNotice("Elicitation: $action")
        firePermissions()
    }
}
