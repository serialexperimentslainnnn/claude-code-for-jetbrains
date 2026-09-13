package dev.lain.claudejb.view.payload.panel

import dev.lain.claudejb.controller.context.LinkResolver
import dev.lain.claudejb.controller.session.ClaudeSession
import dev.lain.claudejb.model.protocol.models.UsageReport
import dev.lain.claudejb.model.vuln.VulnSnapshot
import dev.lain.claudejb.view.git.JcefGitData
import dev.lain.claudejb.view.payload.JcefVulnData
import dev.lain.claudejb.view.payload.composer.JcefModelLabels
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JcefSessionData {

    data class Workload(
        val chatId: String,
        val title: String,
        val selected: Boolean,
        val session: ClaudeSession,
    )

    fun sessionJson(
        session: ClaudeSession,
        windowMinutes: Int,
        nowMillis: Long,
        usage: UsageReport? = null,
        workloads: List<Workload> = emptyList(),
        plan: dev.lain.claudejb.controller.session.control.PlanInfo? = null,
        git: JcefGitData.Snapshot? = null,
        vuln: VulnSnapshot? = null,
    ): String {
        val shown = JcefWorkloadData.visible(session, windowMinutes, nowMillis)
        val obj = buildJsonObject {
            put("usage", JcefUsageData.usageJson(session, usage) ?: JsonNull)
            put("plan", JcefPlanData.planJson(plan) ?: JsonNull)
            put("git", JcefGitData.gitJson(git) ?: JsonNull)
            put("vuln", JcefVulnData.vulnJson(vuln) ?: JsonNull)
            put("context", JcefCostData.contextJson(session) ?: JsonNull)
            put("cost", JcefCostData.costJson(session) ?: JsonNull)
            put("account", JcefAccountData.accountJson(session) ?: JsonNull)
            put("backgroundTasks", JcefWorkloadData.backgroundTasksJson(session, shown))
            put("agentTree", JcefWorkloadData.agentTreeJson(session, shown))
            put("workloads", JcefWorkloadData.workloadsJson(workloads, windowMinutes, nowMillis))
            put("workloadWindow", JcefWorkloadData.windowJson(windowMinutes))
            put("model", JcefModelLabels.modelLabel(session))
            put("cwd", session.project.basePath)
            put("home", LinkResolver.userHome())
            put("version", session.catalog.binaryVersion)
        }
        return obj.toString()
    }
}
