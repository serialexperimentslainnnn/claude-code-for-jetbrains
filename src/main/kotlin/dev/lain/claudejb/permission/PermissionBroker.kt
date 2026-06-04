package dev.lain.claudejb.permission

import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.session.PermissionMode
import dev.lain.claudejb.protocol.CanUseToolRequest
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.ElicitField
import dev.lain.claudejb.protocol.parseAskQuestions
import dev.lain.claudejb.protocol.str
import kotlinx.serialization.json.JsonObject

/**
 * A tool request awaiting the user's decision. It is rendered as an inline Accept/Reject card in the chat
 * (and, for file writes, alongside an in-editor diff) rather than a blocking modal dialog.
 */
data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val title: String,
    val summary: String,
    /** True for Edit/Write/MultiEdit — these also get a native diff to review. */
    val reviewable: Boolean,
    /** Non-null for AskUserQuestion: render these instead of an Accept/Reject card. */
    val questions: List<AskQuestion>? = null,
    /** Correlates with the assistant ToolUse.id; keys the persistent edit snapshot. */
    val toolUseId: String? = null,
    /**
     * True for ExitPlanMode: the agent is proposing a plan (carried in [planText]). Render a "Plan" card with
     * the plan body and Approve plan / Keep planning buttons (allow / deny) instead of an Accept/Reject card.
     */
    val isPlan: Boolean = false,
    /** The proposed plan text for an ExitPlanMode request (markdown), shown verbatim in the plan card. */
    val planText: String? = null,
    /** can_use_tool `description`: a short noun phrase / sentence about the action, when the binary supplies it. */
    val description: String? = null,
    /** can_use_tool `decision_reason`: why this request was surfaced (e.g. a deny rule), when present. */
    val decisionReason: String? = null,
    /** can_use_tool `blocked_path`: the path that triggered the request (e.g. a Bash access outside the root). */
    val blockedPath: String? = null,
    /** Non-null for an MCP elicitation: render an elicitation card instead of an Accept/Reject card. */
    val elicitation: ElicitationCard? = null,
) {
    /** Short headline for transcript notices, e.g. "Edit on App.kt". */
    val headline: String
        get() = DiffPresenter.filePathOf(input)?.substringAfterLast('/')?.let { "$toolName on $it" } ?: toolName
}

/**
 * The data for an MCP elicitation card (carried on a [PendingPermission]). [fields] is the flat set of
 * primitive inputs extracted from the requested_schema; it is empty for URL mode or a non-renderable schema,
 * in which case the card is a plain Accept/Decline.
 */
data class ElicitationCard(
    val serverName: String,
    val message: String,
    val description: String?,
    val mode: String?,            // "url" | "form" | null
    val url: String?,
    val fields: List<ElicitField>,
)

/**
 * Decides `can_use_tool` requests. Auto-approves according to the permission mode; otherwise it hands the
 * request to the UI via [present] as a [PendingPermission] and returns immediately — the user's later
 * Accept/Reject (through [ClaudeSession.resolvePermission]) is what actually writes the control response.
 *
 * This is intentionally non-blocking: the process reader thread is never parked on a modal dialog.
 */
class PermissionBroker(
    private val permissionMode: () -> String,
    private val respond: (String) -> Unit,
    private val onApprovedWrite: (String) -> Unit,
    private val present: (PendingPermission) -> Unit,
    /** Auto-approved file edit (acceptEdits/bypassPermissions): pop its diff so the user still sees it. */
    private val onAutoReviewed: (toolName: String, input: JsonObject, toolUseId: String) -> Unit,
    /** Returns true when the user has marked [toolName] as "Always allow" (auto-approve, no card). */
    private val isRemembered: (toolName: String, input: JsonObject) -> Boolean = { _, _ -> false },
    /** Project root for turning absolute paths into relative ones in permission cards. */
    private val projectRoot: String? = null,
) {

    fun handle(requestId: String, request: CanUseToolRequest) {
        // AskUserQuestion is not a permission: it carries questions the user must answer. Always surface it
        // to the UI (never auto-approve, regardless of mode) so the answers can be collected and returned.
        if (request.toolName == "AskUserQuestion") {
            present(
                PendingPermission(
                    requestId = requestId,
                    toolName = request.toolName,
                    input = request.input,
                    title = request.title ?: "Claude has a question",
                    summary = "",
                    reviewable = false,
                    questions = parseAskQuestions(request.input),
                    toolUseId = request.toolUseId.ifBlank { null },
                )
            )
            return
        }
        // ExitPlanMode is the agent proposing a plan and asking to leave plan mode. It is never auto-approved
        // (leaving plan mode is a deliberate user decision): always surface a dedicated plan card whose
        // Approve plan / Keep planning map onto the standard allow / deny resolution.
        if (request.toolName == "ExitPlanMode") {
            present(planPresentable(requestId, request))
            return
        }
        val mode = permissionMode()
        val reviewable = request.toolName in DiffPresenter.REVIEWABLE_TOOLS
        // A reviewable write is only eligible for auto-approval when its target is confined to the project root.
        // See [autoAllow] / [isWithinRoot] for the rationale (blast-radius containment of acceptEdits/bypass).
        val autoApprovable = !reviewable ||
            DiffPresenter.isWithinRoot(DiffPresenter.filePathOf(request.input), projectRoot)
        when (PermissionMode.from(mode)) {
            PermissionMode.BYPASS -> if (autoApprovable) { autoAllow(requestId, request, reviewable); return }
            PermissionMode.ACCEPT_EDITS -> if (reviewable && autoApprovable) { autoAllow(requestId, request, reviewable); return }
            else -> {}
        }
        // "Always allow" honoured here, gated by the same autoApprovable check: a remembered reviewable write
        // outside the project root still falls through to a manual card (path containment is non-negotiable).
        if (isRemembered(request.toolName, request.input) && autoApprovable) {
            autoAllow(requestId, request, reviewable); return
        }
        // Not auto-approved (default/plan mode, or a write that escapes the project root): surface a manual card.
        present(presentable(requestId, request, reviewable))
    }

    private fun presentable(requestId: String, request: CanUseToolRequest, reviewable: Boolean) =
        PendingPermission(
            requestId = requestId,
            toolName = request.toolName,
            input = request.input,
            title = request.title ?: defaultTitle(request),
            summary = summarize(request.toolName, request.input),
            reviewable = reviewable,
            toolUseId = request.toolUseId.ifBlank { null },
            description = request.description?.ifBlank { null },
            decisionReason = request.decisionReason?.ifBlank { null },
            blockedPath = request.blockedPath?.ifBlank { null },
        )

    /**
     * Builds the plan card for an ExitPlanMode request. The plan body lives in the tool input — the binary puts
     * it under `plan` — so it is read from there; the card resolves through the usual allow (Approve plan) / deny
     * (Keep planning) path so no new control wiring is needed.
     */
    private fun planPresentable(requestId: String, request: CanUseToolRequest) =
        PendingPermission(
            requestId = requestId,
            toolName = request.toolName,
            input = request.input,
            title = request.title ?: "Claude proposes a plan",
            summary = "",
            reviewable = false,
            toolUseId = request.toolUseId.ifBlank { null },
            isPlan = true,
            planText = request.input.str("plan")?.ifBlank { null },
            description = request.description?.ifBlank { null },
            decisionReason = request.decisionReason?.ifBlank { null },
            blockedPath = request.blockedPath?.ifBlank { null },
        )

    /** Replies to an unsupported binary->host control request so the binary is not left waiting. */
    fun rejectUnsupported(requestId: String, subtype: String?) {
        respond(ControlProtocol.error(requestId, "Unsupported control request: ${subtype ?: "?"}"))
    }

    /**
     * Writes the `allow` control response for a request the mode permits silently. Reached only after [handle]
     * has cleared it for auto-approval: in particular, reviewable writes (Edit/Write/MultiEdit) get here **only**
     * when their `file_path` is confined to the project root ([DiffPresenter.isWithinRoot]). This caps the blast
     * radius of acceptEdits/bypassPermissions to the project tree — a write outside it (e.g. ~/.ssh, /etc) is
     * degraded to an explicit manual card rather than auto-applied. Non-reviewable tools (Bash, etc.) carry no
     * file_path and keep the prior auto-allow behaviour under bypassPermissions.
     */
    private fun autoAllow(requestId: String, request: CanUseToolRequest, reviewable: Boolean) {
        if (reviewable) {
            DiffPresenter.filePathOf(request.input)?.let(onApprovedWrite)
            // Pop the diff *before* answering allow: the binary writes the file right after, so the snapshot of
            // the current contents must be captured now (the callback reads disk synchronously).
            onAutoReviewed(request.toolName, request.input, request.toolUseId)
        }
        respond(ControlProtocol.permissionAllow(requestId, request.input))
    }

    private fun relativize(path: String): String {
        val root = projectRoot ?: return path.substringAfterLast('/')
        val prefix = if (root.endsWith('/')) root else "$root/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else path.substringAfterLast('/')
    }

    private fun defaultTitle(request: CanUseToolRequest): String =
        "Claude wants to use ${request.toolName}" +
            (DiffPresenter.filePathOf(request.input)?.let { " on ${relativize(it)}" } ?: "")

    private fun summarize(toolName: String, input: JsonObject): String = when (toolName) {
        "Bash" -> input.str("command")?.let { "$ $it" } ?: ""
        "Read", "Glob", "Grep" -> (input.str("file_path") ?: input.str("path") ?: input.str("pattern") ?: "")
            .let { if (it.startsWith('/')) relativize(it) else it }
        "Write", "Edit", "MultiEdit" -> DiffPresenter.filePathOf(input)?.let { relativize(it) } ?: ""
        "WebFetch" -> input.str("url") ?: ""
        "WebSearch" -> input.str("query") ?: ""
        else -> DiffPresenter.filePathOf(input)?.let { relativize(it) } ?: ""
    }.take(2000)
}
