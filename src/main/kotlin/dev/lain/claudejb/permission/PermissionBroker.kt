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
     * Non-null when this card exists **because a security rule fired** — see [GuardAlert].
     *
     * It can only be set on a card for a rule the user switched OFF, because an enforced rule never produces a
     * card at all: it is denied outright. So a guard alert always means "the lock you opened just let something
     * through", which is exactly the thing that must not be quiet.
     */
    val guard: GuardAlert? = null,
) {
    /** Short headline for transcript notices, e.g. "Edit on App.kt". */
    val headline: String
        get() = DiffPresenter.filePathOf(input)?.substringAfterLast('/')?.let { "$toolName on $it" } ?: toolName
}

/**
 * Why a card is a **guard alert** rather than an ordinary permission question: the [SecurityRule] that fired,
 * and the guard's own sentence about it.
 *
 * The rule travels as the enum, not as prose. The card names it twice — the exact id, so the user can find the
 * switch, and the human [SecurityRule.label] with its category — and deriving either by parsing [reason] would
 * be a second, weaker copy of a classification already made: the wording is written for a human and is free to
 * change, so a card keyed on it would start saying "unknown rule" the day somebody improves a sentence.
 */
data class GuardAlert(val rule: SecurityRule, val reason: String?) {
    /** The row label the Settings page and the ⚙ menu draw, so all three surfaces say the same thing. */
    val label: String get() = rule.label

    /** The category it is grouped under, which is how the switch is actually found. */
    val category: String get() = rule.category.label
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
    private val sensitiveDecision: (input: JsonObject) -> SensitiveGuard.Decision =
        { SensitiveGuard.Decision(SensitiveGuard.Verdict.ALLOW, null) },
    /**
     * A call was refused by the sensitive-data guard — surface it in the transcript, with the guard's reason
     * **and the rule that refused it**.
     *
     * The rule travels because the transcript's block is where the user acts on it: it carries the *Disable
     * rule* link, and a link has to know which rule it opens. Passed as the enum for the same reason
     * [GuardAlert] carries one rather than prose — a block keyed on the wording would stop working the day
     * somebody improves a sentence.
     */
    private val onSensitiveDenied: (toolName: String, reason: String?, rule: SecurityRule?) -> Unit =
        { _, _, _ -> },
    /**
     * Whether the user explicitly answered "Allow always" to **this exact command** under **this exact rule** —
     * see [dev.lain.claudejb.settings.SecurityCommandApprovals].
     *
     * The ONE thing that may skip a guard card, and every word of that sentence is load-bearing. It is asked
     * only inside the ASK branch, i.e. only for a rule the user has already opened, so the approval cannot
     * outlive the suspension that made it possible. It is per COMMAND: "Allow always" on a `terraform destroy`
     * card opens that command and nothing else the same rule stops, which is why it is not the tool-level
     * [isRemembered] that answers here.
     */
    private val isGuardCommandApproved: (rule: SecurityRule, command: String?) -> Boolean = { _, _ -> false },
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
     *
     * **The caller does not enter into it.** An enforced rule denies whoever is calling — the agent's own tools
     * exactly like an MCP server or a Skill — and a rule the user switched off in Settings turns into a card
     * instead, every time. The tool name reaching this function is used only to WORD the transcript line and to
     * decide whether the card carries a diff; it decides no verdict, because it arrives on the wire.
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
                onSensitiveDenied(request.toolName, decision.reason, decision.rule)
                true
            }

            SensitiveGuard.Verdict.ASK -> {
                // A rule the user OPENED — in Settings, or by suspending it from a block. **Disabled means ASK,
                // never bypass**: the card is mandatory, so that opening something the guard protects costs an
                // explicit answer every time and the risk is knowingly taken rather than inherited.
                //
                // Nothing implicit may answer it. Not the permission mode — `bypassPermissions` and
                // `acceptEdits` must not make a guard card disappear, because "I disabled this rule" means "I
                // want to decide this one myself", not "stop watching for it"; that is why this never falls
                // through to [tryAutoApprove]. And not the tool-level "Always allow" either, which used to be
                // honoured here and was the one implicit pass left: one click on a `Bash` card opened every
                // command `Bash` can run, including every other one the same rule exists to stop.
                //
                // The single exception is an answer the user gave ON a card of exactly this kind, about exactly
                // this command — see [isGuardCommandApproved]. It cannot generalise past that command, and it
                // cannot outlive the rule being open, since this branch is the only place it is ever consulted.
                val reviewable = request.toolName in DiffPresenter.REVIEWABLE_TOOLS
                val approved = decision.rule?.let {
                    isGuardCommandApproved(it, ToolInputScanner.commandText(request.input))
                } == true
                if (!forceAsk() && approved) {
                    autoAllow(requestId, request, reviewable)
                } else {
                    present(presentable(requestId, request, reviewable, decision))
                }
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

    private fun presentable(
        requestId: String,
        request: CanUseToolRequest,
        reviewable: Boolean,
        /** Set only on the guard's ASK path, so the card can announce which open lock let this through. */
        guard: SensitiveGuard.Decision? = null,
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
            guard = guard?.rule?.let { GuardAlert(it, guard.reason) },
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
