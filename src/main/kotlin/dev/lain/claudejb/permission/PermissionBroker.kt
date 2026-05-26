package dev.lain.claudejb.permission

import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.protocol.CanUseToolRequest
import dev.lain.claudejb.protocol.ControlProtocol
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
) {
    /** Short headline for transcript notices, e.g. "Edit on App.kt". */
    val headline: String
        get() = DiffPresenter.filePathOf(input)?.substringAfterLast('/')?.let { "$toolName on $it" } ?: toolName
}

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
    private val onAutoReviewed: (toolName: String, input: JsonObject) -> Unit,
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
                )
            )
            return
        }
        val mode = permissionMode()
        val reviewable = request.toolName in DiffPresenter.REVIEWABLE_TOOLS
        when {
            mode == "bypassPermissions" -> {
                autoAllow(requestId, request, reviewable); return
            }
            mode == "acceptEdits" && reviewable -> {
                autoAllow(requestId, request, reviewable); return
            }
        }
        present(
            PendingPermission(
                requestId = requestId,
                toolName = request.toolName,
                input = request.input,
                title = request.title ?: defaultTitle(request),
                summary = summarize(request.toolName, request.input),
                reviewable = reviewable,
            )
        )
    }

    /** Replies to an unsupported binary->host control request so the binary is not left waiting. */
    fun rejectUnsupported(requestId: String, subtype: String?) {
        respond(ControlProtocol.error(requestId, "Unsupported control request: ${subtype ?: "?"}"))
    }

    private fun autoAllow(requestId: String, request: CanUseToolRequest, reviewable: Boolean) {
        if (reviewable) {
            DiffPresenter.filePathOf(request.input)?.let(onApprovedWrite)
            // Pop the diff *before* answering allow: the binary writes the file right after, so the snapshot of
            // the current contents must be captured now (the callback reads disk synchronously).
            onAutoReviewed(request.toolName, request.input)
        }
        respond(ControlProtocol.permissionAllow(requestId, request.input))
    }

    private fun defaultTitle(request: CanUseToolRequest): String =
        "Claude wants to use ${request.toolName}" +
            (DiffPresenter.filePathOf(request.input)?.let { " on ${it.substringAfterLast('/')}" } ?: "")

    private fun summarize(toolName: String, input: JsonObject): String = when (toolName) {
        "Bash" -> input.str("command")?.let { "$ $it" } ?: ""
        "Read", "Glob", "Grep" -> input.str("file_path") ?: input.str("path") ?: input.str("pattern") ?: ""
        "Write", "Edit", "MultiEdit" -> DiffPresenter.filePathOf(input) ?: ""
        "WebFetch" -> input.str("url") ?: ""
        "WebSearch" -> input.str("query") ?: ""
        else -> DiffPresenter.filePathOf(input) ?: ""
    }.take(2000)
}
