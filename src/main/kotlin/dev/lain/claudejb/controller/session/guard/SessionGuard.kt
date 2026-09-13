package dev.lain.claudejb.controller.session.guard

import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.lain.claudejb.controller.session.AttentionLanding
import dev.lain.claudejb.controller.session.AttentionReason
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.diff.DiffPresenter
import dev.lain.claudejb.model.mcp.OwnTools
import dev.lain.claudejb.model.mcp.RunCard
import dev.lain.claudejb.model.permission.broker.GuardBypass
import dev.lain.claudejb.model.permission.broker.GuardDenial
import dev.lain.claudejb.model.permission.broker.PendingPermission
import dev.lain.claudejb.model.permission.broker.PermissionBroker
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import dev.lain.claudejb.model.protocol.control.ControlProtocol
import dev.lain.claudejb.model.protocol.models.CanUseToolRequest
import dev.lain.claudejb.model.session.transcript.EntryDTO
import dev.lain.claudejb.model.session.transcript.Speaker
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.ToolFilters
import dev.lain.claudejb.model.settings.guard.GuardAlert
import dev.lain.claudejb.model.settings.guard.GuardAlertLog
import dev.lain.claudejb.model.settings.guard.GuardCommandApprovals
import dev.lain.claudejb.model.settings.guard.guardSuspended
import dev.lain.claudejb.model.settings.guard.sensitiveDecision
import dev.lain.claudejb.util.thisLogger
import java.util.concurrent.CopyOnWriteArrayList

class SessionGuard(
    private val session: ClaudeSession,
    private val project: Project,
    private val edt: (() -> Unit) -> Unit,
    private val write: (String) -> Unit,
    private val fireState: () -> Unit,
    private val fireAttention: (AttentionReason, AttentionLanding) -> Unit,
) {

    private val log = thisLogger()

    val approvals = GuardCommandApprovals()

    val guardLog = GuardLogTally()

    private val alerts = CopyOnWriteArrayList<GuardAlert>()

    val broker = PermissionBroker(
        permissionMode = { session.launch.permissionMode },
        respond = write,
        onApprovedWrite = { session.diffs.markForRefresh(it) },
        present = ::present,
        onAutoReviewed = session.diffs::autoOpenDiff,
        projectRoot = project.basePath,
        isRemembered = { toolName, _ -> alwaysAllowed(toolName) },
        forceAsk = { session.gitIntegration },
        sensitiveDecision = { input ->
            ClaudeSettings.getInstance(project).sensitiveDecision(OwnTools.guardInput(input), project.basePath)
        },
        isGuardCommandApproved = { rule, command -> approvals.isApproved(rule, command) },
        onSensitiveDenied = ::onDenied,
        onSensitiveBypassed = ::onBypassed,
    )

    fun onPermission(requestId: String, request: CanUseToolRequest) {
        if (ToolFilters.isDisallowed(ClaudeSettings.getInstance(project).state, request.toolName)) {
            write(ControlProtocol.permissionDeny(requestId, "${request.toolName} is a disallowed tool in this project's settings."))
            edt { session.transcript.add(Speaker.SYSTEM, "Refused ${request.toolName}: it is on the disallowed tools list.") }
            return
        }
        broker.handle(requestId, request)
    }

    private fun alwaysAllowed(toolName: String): Boolean {
        val settings = ClaudeSettings.getInstance(project)
        return settings.isToolAlwaysAllowed(toolName) || ToolFilters.isAllowed(settings.state, toolName)
    }

    private fun onDenied(denial: GuardDenial) = edt {
        val landing = landingOf(denial.toolUseId)
        if (landing == AttentionLanding.Chat) {
            session.transcript.add(
                Speaker.SYSTEM,
                denial.reason?.let { "Blocked ${denial.toolName}: it $it." }
                    ?: "Blocked ${denial.toolName} by the sensitive-data guard. See Settings ▸ Claude Code Security.",
                commandText = denial.command?.takeIf { it.isNotBlank() },
                blockedRule = denial.rule?.name,
            )
        }
        fireAttention(AttentionReason.GUARD_BLOCKED, landing)
        recordAlert(
            GuardAlert.DENIED,
            denial.rule,
            denial.toolName,
            command = denial.command,
            toolUseId = denial.toolUseId,
            detail = denial.detail,
            inAgent = landing != AttentionLanding.Chat,
        )
        fireState()
    }

    private fun onBypassed(bypass: GuardBypass) {
        val offer = bypass.action ?: if (ClaudeSettings.getInstance(project).guardSuspended()) {
            PermissionBroker.ENABLE_GUARD
        } else {
            PermissionBroker.REMOVE_FROM_WHITELIST
        }
        notice(
            bypass.toolName,
            bypass.reason ?: "${bypass.rule.label} matched, and a bypass is in force",
            bypass.rule,
            offer,
            bypass.command,
            bypass.toolUseId,
        )
        edt {
            recordAlert(
                GuardAlert.ALLOWED,
                bypass.rule,
                bypass.toolName,
                via = offer,
                command = bypass.command,
                toolUseId = bypass.toolUseId,
                detail = bypass.detail,
                inAgent = landingOf(bypass.toolUseId) != AttentionLanding.Chat,
            )
        }
    }

    private fun present(request: PendingPermission) = edt {
        request.guard?.let {
            recordAlert(
                GuardAlert.ASKED,
                it.rule,
                OwnTools.display(request.toolName, request.input) ?: request.toolName,
                command = ToolInputScanner.commandText(request.input),
                toolUseId = request.toolUseId,
                detail = it.reason,
            )
        }
        val card = RunCard.of(request.toolName, request.input)
        session.cards.present(card?.let { request.copy(title = it.title, summary = it.summary) } ?: request)
        if (request.reviewable && request.toolName in DiffPresenter.REVIEWABLE_TOOLS) {
            session.diffs.openReviewDiff(request.requestId, request.toolName, request.input)
        }
        fireAttention(AttentionReason.PERMISSION, AttentionLanding.Chat)
    }

    private fun recordAlert(
        verdict: String,
        rule: SecurityRule?,
        toolName: String,
        via: String? = null,
        command: String? = null,
        toolUseId: String? = null,
        detail: String? = null,
        inAgent: Boolean = false,
    ) {
        val matched = rule ?: return
        log.info("guard: $verdict ${matched.name} tool=$toolName")
        val settings = ClaudeSettings.getInstance(project)
        val alert = GuardAlert(
            at = System.currentTimeMillis(),
            rule = matched.name,
            category = matched.category.name,
            verdict = verdict,
            sessionId = session.sessionId,
            toolUseId = toolUseId,
            via = via,
            tool = toolName,
            detail = detail,
            command = command,
            inAgent = inAgent,
        )
        alerts += alert
        val scope = settings.scope
        val retention = settings.state.guardLogRetentionDays
        AppExecutorUtil.getAppExecutorService().execute {
            val submitted = GuardAlertLog.record(scope, alert, retentionDays = retention)
            guardLog.submitted(submitted != null)
        }
    }

    fun notice(
        toolName: String,
        reason: String,
        rule: SecurityRule,
        action: String? = null,
        command: String? = null,
        toolUseId: String? = null,
    ) = edt {
        if (landingOf(toolUseId) != AttentionLanding.Chat) return@edt
        session.transcript.add(
            Speaker.SYSTEM,
            "Allowed $toolName: $reason.",
            commandText = command?.takeIf { it.isNotBlank() },
            bypassedRule = rule.name,
            bypassAction = action,
        )
    }

    fun landingOf(toolUseId: String?): AttentionLanding {
        if (toolUseId == null || session.transcript.knowsTool(toolUseId)) return AttentionLanding.Chat
        val owner = session.runningAgents.nodes.values
            .firstOrNull { node -> node.entries.any { it.toolUseId == toolUseId } }
        return owner?.let { AttentionLanding.Agent(it.agentId) } ?: AttentionLanding.Elsewhere
    }

    fun alertsAnchoredIn(entries: List<EntryDTO>): List<GuardAlert> {
        if (alerts.isEmpty()) return emptyList()
        val anchors = entries.mapNotNullTo(HashSet()) { it.toolUseId }
        return alerts.filter { it.toolUseId in anchors }
    }

    fun restore(savedSessionId: String): List<GuardAlert> {
        val saved = GuardAlertLog.forSession(ClaudeSettings.getInstance(project).scope, savedSessionId)
        alerts.clear()
        alerts.addAll(saved)
        return saved
    }
}
