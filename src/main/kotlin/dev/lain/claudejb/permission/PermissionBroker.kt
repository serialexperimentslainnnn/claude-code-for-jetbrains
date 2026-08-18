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
    /**
     * Non-null when this card exists **because [SensitiveGuard] turned an approval into a question** — as
     * opposed to the ordinary card the permission mode would have raised anyway. Renders as the red pulsing
     * guard alert, naming the rule.
     */
    val guard: GuardAlert? = null,
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
 * Why a card is a **guard alert** rather than an ordinary permission question: the [SecurityRule] that tripped,
 * and [SensitiveGuard]'s own wording for it.
 *
 * It exists because the two are indistinguishable on screen otherwise, and they are not the same event. An
 * ordinary card is the permission mode doing its job — the user chose `default`, so Claude asks. A guard alert
 * is a call that was **about to be auto-approved** and was taken back off that path by a deterministic rule,
 * which is the case where "Accept" deserves a different amount of attention. Until this existed the ASK branch
 * threw the whole [SensitiveGuard.Decision] away, so the guard's finding — the rule, the path, the wording it
 * had already produced — reached nobody, and the strongest signal the plugin has looked exactly like the
 * weakest.
 *
 * [reason] is the guard's sentence, verbatim, including the "disable this in Settings ▸ …" tail: the lever has
 * to be discoverable from the card itself, because the card is where the user is when they want it.
 */
data class GuardAlert(val rule: SecurityRule, val reason: String)

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
     *  paths on disk under a timeout, and doing that twice per request is latency the user feels on the card.
     *  Takes the input and NOT the tool name, because the guard's verdict does not depend on who is calling —
     *  see [SensitiveGuard]. The name is still the broker's business for everything else (the card's title, the
     *  reviewable set, "Always allow"); it is simply not an input to this decision. */
    private val sensitiveDecision: (input: JsonObject) -> SensitiveGuard.Decision =
        { SensitiveGuard.Decision(SensitiveGuard.Verdict.ALLOW, null) },
    /** A call was refused by the sensitive-data guard — surface it in the transcript, with the guard's reason. */
    private val onSensitiveDenied: (toolName: String, reason: String?) -> Unit = { _, _ -> },
    /**
     * True when this session must put **every** call to the user, whatever the permission mode says and whatever
     * they have marked "Always allow" — the Git integration's chat, whose turns the plugin itself starts
     * ([ClaudeSession.gitIntegration]).
     *
     * A button that makes the agent write to the user's repository is the plugin acting, not the user typing, so
     * it does not get to inherit a permission the user granted for their own work. Note what this does NOT do:
     * it only ever turns an auto-approval into a card. A guard `DENY` stays a `DENY`, and nothing here can allow
     * something that would otherwise have been asked about.
     */
    private val forceAsk: () -> Boolean = { false },
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
     * question: `AskUserQuestion` carries questions the user must answer, and `ExitPlanMode` is the agent asking
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
     *   An ENFORCED rule → denied outright, for every caller alike: the agent's own tools, an MCP server, a Skill.
     *   A rule the user switched OFF in Settings → a card, every time, drawn as a red [GuardAlert].
     *
     * Returns true when the guard settled the request (denied or turned it into a card).
     */
    private fun applySensitiveGuard(requestId: String, request: CanUseToolRequest): Boolean {
        val decision = sensitiveDecision(request.input)
        return when (decision.verdict) {
            SensitiveGuard.Verdict.DENY -> {
                // Tell the model WHICH rule refused and WHERE to change it. Until 5.0.0 this was a fixed string
                // that always said "credentials or private keys" and "allow it in Settings" — inaccurate for a
                // dangerous-command or foreign-territory denial, and vague about a setting that has an exact
                // path. SensitiveGuard.evaluate has produced the precise wording in its Decision.reason since
                // 4.4.0; nothing was reading it, so the user never saw it.
                respond(ControlProtocol.permissionDeny(requestId, denialMessage(decision.reason)))
                onSensitiveDenied(request.toolName, decision.reason)
                true
            }

            SensitiveGuard.Verdict.ASK -> {
                // **"Always allow" is honoured here, and it is safe by construction rather than by care.** This
                // branch is only reachable when the rule that tripped is DISABLED — an enforced rule returns DENY
                // above and no button, mode or memory can reach it. So the most this can do is stop re-asking about
                // a door the user already opened deliberately, in Settings, in the cold; it cannot open one.
                //
                // It did not work before, and the failure was invisible: the guard runs BEFORE [tryAutoApprove], so
                // a guard card carried an "Always allow" button whose effect the next identical call ignored. A
                // button that does not do what it says is worse than an absent one — the user believes they have
                // answered, and the same card comes back.
                //
                // [forceAsk] still wins (the Git chat puts every call to the user), and a reviewable write outside
                // the project root is still never auto-approved — the same containment check [tryAutoApprove]
                // applies, restated here rather than shared, because this path must not inherit a relaxation that
                // is added to that one later.
                val reviewable = request.toolName in DiffPresenter.REVIEWABLE_TOOLS
                val contained = !reviewable ||
                    DiffPresenter.isWithinRoot(DiffPresenter.filePathOf(request.input), projectRoot)
                if (!forceAsk() && contained && isRemembered(request.toolName, request.input)) {
                    autoAllow(requestId, request, reviewable)
                    return true
                }
                // Tagged as a GUARD card, not merely presented. Same renderer, same buttons, different weight:
                // this one is only here because a rule pulled it off the auto-approval path, and the user cannot
                // tell that from a card that looks like every other one. See [GuardAlert].
                present(presentable(requestId, request, reviewable, guardAlert(decision)))
                true
            }

            SensitiveGuard.Verdict.ALLOW -> false // not our business — the normal flow runs
        }
    }

    /** Auto-approves when the mode (or "Always allow") says so AND the write is contained. True when approved. */
    private fun tryAutoApprove(requestId: String, request: CanUseToolRequest): Boolean {
        // Before the mode AND before "Always allow" — both of them are auto-approvals, and checking this after
        // either one would leave exactly the hole it exists to close (a remembered tool is approved by
        // `isRemembered` even when the mode says no).
        if (forceAsk()) return false
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

    /**
     * The guard's finding as card data, or null when the guard had nothing to do with this request.
     *
     * A [SensitiveGuard.Decision] that reached ASK always carries a rule (a verdict comes from a classification),
     * so the null case is the ordinary card. The reason falls back to the rule's own label rather than to an
     * empty string: an alert that names no cause is worse than a plain card, because it says something is wrong
     * and refuses to say what.
     */
    private fun guardAlert(decision: SensitiveGuard.Decision): GuardAlert? =
        decision.rule?.let { GuardAlert(it, decision.reason ?: it.label) }

    private fun presentable(
        requestId: String,
        request: CanUseToolRequest,
        reviewable: Boolean,
        guard: GuardAlert? = null,
    ) =
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
            guard = guard,
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
            "Denied by the IDE: this call touches credentials, a dangerous command, or territory it must not. " +
                "Do not retry it and do not attempt another way to reach the same result."

        /**
         * The model-facing refusal: states WHAT tripped the guard and WHAT the model must stop doing — nothing
         * else. Deliberately carries no suggested workaround ("ask the user to run it themselves", "try X
         * instead"): a refusal that hands the model its next move is an invitation to keep pushing at the same
         * boundary from a different angle, which is exactly the shape a prompt injection exploits. The model is
         * told to stop, not redirected — figuring out what to do next, if anything, is its problem, not this
         * message's. Kept pure and public so the wording is unit-testable.
         */
        fun denialMessage(reason: String?): String =
            reason?.let { "Denied by the IDE: it $it. Do not retry it and do not attempt another way to do the same thing." }
                ?: SENSITIVE_DENIED
    }
}
