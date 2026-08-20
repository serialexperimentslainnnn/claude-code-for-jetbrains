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

data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val title: String,
    val summary: String,
    val reviewable: Boolean,
    val questions: List<AskQuestion>? = null,
    val toolUseId: String? = null,
    val isPlan: Boolean = false,
    val planText: String? = null,
    val description: String? = null,
    val decisionReason: String? = null,
    val blockedPath: String? = null,
    val elicitation: ElicitationCard? = null,
    val guard: GuardAlert? = null,
) {
    val headline: String
        get() = DiffPresenter.filePathOf(input)?.substringAfterLast('/')?.let { "$toolName on $it" } ?: toolName
}

data class GuardAlert(val rule: SecurityRule, val reason: String?) {
    val label: String get() = rule.label

    val category: String get() = rule.category.label
}

data class ElicitationCard(
    val serverName: String,
    val message: String,
    val description: String?,
    val mode: String?,
    val url: String?,
    val fields: List<ElicitField>,
)

class PermissionBroker(
    private val permissionMode: () -> String,
    private val respond: (String) -> Unit,
    private val onApprovedWrite: (String) -> Unit,
    private val present: (PendingPermission) -> Unit,
    private val onAutoReviewed: (toolName: String, input: JsonObject, toolUseId: String) -> Unit,
    private val isRemembered: (toolName: String, input: JsonObject) -> Boolean = { _, _ -> false },
    private val projectRoot: String? = null,
    private val sensitiveDecision: (input: JsonObject) -> SensitiveGuard.Decision =
        { SensitiveGuard.Decision(SensitiveGuard.Verdict.ALLOW, null) },
    private val onSensitiveDenied: (toolName: String, reason: String?, rule: SecurityRule?, command: String?) -> Unit =
        { _, _, _, _ -> },
    /**
     * A call the guard matched and let through anyway — the two bypasses, *Allow All* and a whitelist.
     *
     * Only fired when a rule actually matched. A call nothing objected to is ordinary work and says nothing;
     * this is for the case where something WOULD have been stopped, so the transcript can say which rule it
     * was and why it ran.
     */
    private val onSensitiveBypassed: (toolName: String, reason: String?, rule: SecurityRule) -> Unit =
        { _, _, _ -> },
    private val isGuardCommandApproved: (rule: SecurityRule, command: String?) -> Boolean = { _, _ -> false },
    private val forceAsk: () -> Boolean = { false },
) {

    fun handle(requestId: String, request: CanUseToolRequest) {
        if (presentSpecialCard(requestId, request)) return
        if (applySensitiveGuard(requestId, request)) return
        if (tryAutoApprove(requestId, request)) return
        present(presentable(requestId, request, request.toolName in DiffPresenter.REVIEWABLE_TOOLS))
    }

    private fun presentSpecialCard(requestId: String, request: CanUseToolRequest): Boolean {
        when (request.toolName) {
            "AskUserQuestion" -> present(questionPresentable(requestId, request))
            "ExitPlanMode" -> present(planPresentable(requestId, request))
            else -> return false
        }
        return true
    }

    private fun applySensitiveGuard(requestId: String, request: CanUseToolRequest): Boolean {
        val decision = sensitiveDecision(request.input)
        return when (decision.verdict) {
            SensitiveGuard.Verdict.DENY -> {
                respond(ControlProtocol.permissionDeny(requestId, denialMessage(decision.reason)))
                onSensitiveDenied(
                    request.toolName,
                    decision.reason,
                    decision.rule,
                    ToolInputScanner.commandText(request.input),
                )
                true
            }

            SensitiveGuard.Verdict.ASK -> {
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

            SensitiveGuard.Verdict.ALLOW -> {
                decision.rule?.let { onSensitiveBypassed(request.toolName, decision.reason, it) }
                false
            }
        }
    }

    private fun tryAutoApprove(requestId: String, request: CanUseToolRequest): Boolean {
        if (forceAsk()) return false
        val reviewable = request.toolName in DiffPresenter.REVIEWABLE_TOOLS
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

    fun rejectUnsupported(requestId: String, subtype: String?) {
        respond(ControlProtocol.error(requestId, "Unsupported control request: ${subtype ?: "?"}"))
    }

    private fun autoAllow(requestId: String, request: CanUseToolRequest, reviewable: Boolean) {
        if (reviewable) {
            DiffPresenter.filePathOf(request.input)?.let(onApprovedWrite)
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
        else -> relativeFilePath(input)
    }.take(MAX_SUMMARY_CHARS)

    private fun searchTarget(input: JsonObject): String {
        val target = input.str("file_path") ?: input.str("path") ?: input.str("pattern") ?: return ""
        return if (target.startsWith('/')) relativize(target) else target
    }

    private fun relativeFilePath(input: JsonObject): String =
        DiffPresenter.filePathOf(input)?.let { relativize(it) }.orEmpty()

    companion object {
        private const val MAX_SUMMARY_CHARS = 2000

        const val SENSITIVE_DENIED: String =
            "Denied by the IDE: this call touches credentials, a dangerous command, or territory it must not. " +
                "Do not retry it and do not attempt another way to reach the same result."

        fun denialMessage(reason: String?): String =
            reason?.let { "Denied by the IDE: it $it. Do not retry it and do not attempt another way to do the same thing." }
                ?: SENSITIVE_DENIED
    }
}
