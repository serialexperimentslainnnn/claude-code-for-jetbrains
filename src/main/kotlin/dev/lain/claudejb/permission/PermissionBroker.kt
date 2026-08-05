package dev.lain.claudejb.permission

import dev.lain.claudejb.diff.DiffPresenter
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.protocol.CanUseToolRequest
import dev.lain.claudejb.protocol.ControlProtocol
import dev.lain.claudejb.protocol.ElicitField
import dev.lain.claudejb.protocol.parseAskQuestions
import dev.lain.claudejb.protocol.str
import dev.lain.claudejb.session.PermissionMode
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
    val mode: String?, // "url" | "form" | null
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
    /** Verdict **and reason** for a call that trips the sensitive-data guard — see [SensitiveGuard.evaluate].
     *  ALLOW with no reason when the guard is not configured. One call, not two: classification canonicalises
     *  paths on disk under a timeout, and doing that twice per request is latency the user feels on the card. */
    private val sensitiveDecision: (toolName: String, input: JsonObject) -> SensitiveGuard.Decision =
        { _, _ -> SensitiveGuard.Decision(SensitiveGuard.Verdict.ALLOW, null) },
    /** A call was refused by the sensitive-data guard — surface it in the transcript, with the guard's reason. */
    private val onSensitiveDenied: (toolName: String, reason: String?) -> Unit = { _, _ -> },
) {

    /**
     * Three gates, in a fixed order that is itself the policy: the two tools that are not permission questions
     * at all, then the sensitive-data guard, then mode-based auto-approval. Anything that survives all three
     * gets a manual card.
     */
    fun handle(requestId: String, request: CanUseToolRequest) {
        if (presentSpecialCard(requestId, request)) return
        if (applySensitiveGuard(requestId, request)) return
        if (tryAutoApprove(requestId, request)) return
        // Not auto-approved (default/plan mode, or a write that escapes the project root): surface a manual card.
        present(presentable(requestId, request, request.toolName in DiffPresenter.REVIEWABLE_TOOLS))
    }

    /**
     * The two tools that are never auto-approved regardless of mode, because neither is really a permission
     * question: [AskUserQuestion] carries questions the user must answer, and ExitPlanMode is the agent asking
     * to leave plan mode — a deliberate user decision. Returns true when it presented a card.
     */
    private fun presentSpecialCard(requestId: String, request: CanUseToolRequest): Boolean {
        when (request.toolName) {
            "AskUserQuestion" -> present(questionPresentable(requestId, request))
            "ExitPlanMode" -> present(planPresentable(requestId, request))
            else -> return false
        }
        return true
    }

    /**
     * The sensitive-data guard, which runs BEFORE the mode is even looked at — so `bypassPermissions`,
     * `acceptEdits` and "Always allow" simply never reach their fast paths for a call that trips it (see
     * [SensitiveGuard]). The mode itself is untouched; this gate just never falls through to it.
     *   MCP / Skills → denied outright: third-party code has no business reading the user's keys.
     *   The agent's own tools → the user authorises it, explicitly, every time.
     *
     * Returns true when the guard settled the request (denied or turned it into a card).
     */
    private fun applySensitiveGuard(requestId: String, request: CanUseToolRequest): Boolean {
        val decision = sensitiveDecision(request.toolName, request.input)
        return when (decision.verdict) {
            SensitiveGuard.Verdict.DENY -> {
                // Tell the model WHICH rule refused and WHERE to change it. Until 5.0.0 this was a fixed string
                // that always said "credentials or private keys" and "allow it in Settings" — inaccurate for a
                // dangerous-command or foreign-territory denial, and vague about a setting that has an exact
                // path. SensitiveGuard.reason has produced the precise wording since 4.4.0; nothing was calling
                // it, so the user never saw it.
                respond(ControlProtocol.permissionDeny(requestId, denialMessage(decision.reason)))
                onSensitiveDenied(request.toolName, decision.reason)
                true
            }

            SensitiveGuard.Verdict.ASK -> {
                present(presentable(requestId, request, request.toolName in DiffPresenter.REVIEWABLE_TOOLS))
                true
            }

            SensitiveGuard.Verdict.ALLOW -> false // not our business — the normal flow runs
        }
    }

    /** Auto-approves when the mode (or "Always allow") says so AND the write is contained. True when approved. */
    private fun tryAutoApprove(requestId: String, request: CanUseToolRequest): Boolean {
        val reviewable = request.toolName in DiffPresenter.REVIEWABLE_TOOLS
        // A reviewable write is only ever eligible when its target is confined to the project root. See
        // [autoAllow] / [isWithinRoot] for the rationale (blast-radius containment of acceptEdits/bypass).
        // Non-negotiable, and checked first so no later branch can bypass it — including "Always allow".
        val contained = !reviewable ||
            DiffPresenter.isWithinRoot(DiffPresenter.filePathOf(request.input), projectRoot)
        if (!contained) return false
        val allowedByMode = when (PermissionMode.from(permissionMode())) {
            PermissionMode.BYPASS -> true
            PermissionMode.ACCEPT_EDITS -> reviewable
            else -> false
        }
        if (!allowedByMode && !isRemembered(request.toolName, request.input)) return false
        autoAllow(requestId, request, reviewable)
        return true
    }

    /** The AskUserQuestion card: the questions come from the tool input, and the answers go back as its result. */
    private fun questionPresentable(requestId: String, request: CanUseToolRequest) =
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
        "Bash" -> input.str("command")?.let { "$ $it" }.orEmpty()

        "Read", "Glob", "Grep" -> searchTarget(input)

        "WebFetch" -> input.str("url").orEmpty()

        "WebSearch" -> input.str("query").orEmpty()

        // Write/Edit/MultiEdit and everything else (incl. MCP tools) summarise as their `file_path`, if any.
        else -> relativeFilePath(input)
    }.take(MAX_SUMMARY_CHARS)

    /** What a search-shaped tool is pointed at: a file, a directory, or a pattern — whichever it carries. */
    private fun searchTarget(input: JsonObject): String {
        val target = input.str("file_path") ?: input.str("path") ?: input.str("pattern") ?: return ""
        // Only an absolute path is worth shortening; a bare pattern (`*.kt`) must survive untouched.
        return if (target.startsWith('/')) relativize(target) else target
    }

    private fun relativeFilePath(input: JsonObject): String =
        DiffPresenter.filePathOf(input)?.let { relativize(it) }.orEmpty()

    companion object {
        /**
         * Cap on the one-line summary shown on a permission card. The input can carry a whole file's contents
         * (a Write), and the card is a fixed-height box — past this the text is unreadable anyway, and the
         * card's own scroll area is what handles the rest.
         */
        private const val MAX_SUMMARY_CHARS = 2000

        /** Fallback told to the MODEL when the guard refused but produced no reason — should not happen (a DENY
         *  always comes from a classification), so this exists so an unexpected null degrades to something
         *  truthful rather than to an empty message. */
        const val SENSITIVE_DENIED: String =
            "Denied by the IDE: this call touches credentials, a dangerous command, or territory outside your own " +
                "space. Ask the user to run it themselves, or to adjust Settings ▸ Claude Code ▸ Security."

        /** The model-facing refusal: the guard's own words, framed so the model stops retrying instead of
         *  rephrasing the same call. Kept pure and public so the wording is unit-testable. */
        fun denialMessage(reason: String?): String =
            reason?.let {
                "Denied by the IDE: it $it. This is not something retrying will change — ask the user to " +
                    "run it themselves if they intended it."
            }
                ?: SENSITIVE_DENIED
    }
}
