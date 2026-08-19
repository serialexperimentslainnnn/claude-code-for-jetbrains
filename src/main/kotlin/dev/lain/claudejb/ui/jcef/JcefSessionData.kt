package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.LinkResolver
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
        plan: dev.lain.claudejb.session.PlanInfo? = null,
        git: JcefGitData.Snapshot? = null,
    ): String {
        val shown = JcefWorkloadData.visible(session, windowMinutes, nowMillis)
        val obj = buildJsonObject {
            put("usage", JcefUsageData.usageJson(session, usage) ?: JsonNull)
            put("plan", JcefPlanData.planJson(plan) ?: JsonNull)
            put("git", JcefGitData.gitJson(git) ?: JsonNull)
            put("context", JcefCostData.contextJson(session) ?: JsonNull)
            put("cost", JcefCostData.costJson(session) ?: JsonNull)
            put("account", JcefAccountData.accountJson(session) ?: JsonNull)
            put("backgroundTasks", JcefWorkloadData.backgroundTasksJson(session, shown))
            put("agentTree", JcefWorkloadData.agentTreeJson(session, shown))
            put("workloads", JcefWorkloadData.workloadsJson(workloads, windowMinutes, nowMillis))
            put("workloadWindow", JcefWorkloadData.windowJson(windowMinutes))
            put("model", JcefModelLabels.modelLabel(session))
            put("cwd", session.workingDir)
            put("home", LinkResolver.userHome())
            put("version", session.binaryVersion)
        }
        return obj.toString()
    }
}
