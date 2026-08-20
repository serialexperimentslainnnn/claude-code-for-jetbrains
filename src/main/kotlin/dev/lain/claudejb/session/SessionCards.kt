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

class SessionCards(
    private val session: ClaudeSession,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Unit,
    private val firePermissions: () -> Unit,
    private val fireAttention: (AttentionReason) -> Unit,
) {

    fun pending(): List<PendingPermission> = session.cardManager.all()

    fun editSnapshot(toolUseId: String): EditSnapshot? = session.diffs.snapshot(toolUseId)

    internal fun present(request: PendingPermission) = edt {
        session.cardManager.present(request)
        fireAttention(AttentionReason.PERMISSION)
    }

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
            DiffPresenter.filePathOf(request.input)?.let { session.diffs.markForRefresh(it) }
            request.toolUseId?.let { session.diffs.captureForReview(request.toolName, request.input, it) }
        }
        val effectiveInput = overrideInput ?: reviewEditOverride(requestId, request) ?: request.input
        if (request.reviewable && effectiveInput !== request.input) {
            request.toolUseId?.let { session.diffs.updateSnapshotInput(it, effectiveInput) }
        }
        write(ControlProtocol.permissionAllow(requestId, effectiveInput))
        // A guard alert answered Yes is a rule that matched and a call that ran, which is the same fact the
        // two bypasses report — so it reports it the same way, naming the rule and saying what let it past.
        // An ordinary permission card is not that, and stays an ordinary line.
        val guard = request.guard
        if (guard == null) {
            session.systemNotice("Approved ${request.headline}")
        } else {
            session.guardNotice(request.toolName, "${guard.rule.label} matched, and you accepted it", guard.rule)
        }
        if (request.isPlan && session.permissionMode == PermissionMode.PLAN.wire) {
            session.settings.changePermissionMode(PermissionMode.DEFAULT.wire)
        }
    }

    private fun reviewEditOverride(requestId: String, request: PendingPermission): JsonObject? =
        session.diffs.takeReviewEdit(requestId)?.let { (currentText, editedText) ->
            dev.lain.claudejb.diff.HunkSelection
                .encodeInput(request.toolName, request.input, currentText, editedText)
        }

    private fun reject(requestId: String, request: PendingPermission, denyMessage: String?) {
        session.diffs.closeReviewDiff(requestId)
        val message = denyMessage ?: "User rejected the ${request.toolName} request."
        write(ControlProtocol.permissionDeny(requestId, message))
        session.systemNotice("Rejected ${request.headline}")
    }

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

    fun resolveElicitation(requestId: String, action: String, content: JsonObject?) {
        session.cardManager.remove(requestId) ?: return
        write(ControlProtocol.elicitationResult(requestId, action, content))
        session.systemNotice("Elicitation: $action")
        firePermissions()
    }
}
