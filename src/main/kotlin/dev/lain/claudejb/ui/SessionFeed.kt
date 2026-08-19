package dev.lain.claudejb.ui

import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.protocol.afterResets
import dev.lain.claudejb.protocol.mergedOver
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.PlanInfo
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.swing.Timer

internal class SessionFeed(
    private val session: ClaudeSession,
    private val exec: (String) -> Unit,
    private val onRefreshed: () -> Unit,
) {

    var usage: UsageReport? = null
        private set
    private var askedAt = 0L

    var plan: PlanInfo? = null
        private set

    private val timer = Timer(USAGE_POLL_MS) { requestUsage() }.apply { isRepeats = true }

    fun start() = timer.start()

    fun stop() = timer.stop()

    fun onSessionReady() {
        requestMcp()
        requestVersion()
        requestUsage()
        requestPlan()
    }

    fun requestPlan() {
        session.queries.requestPlan { info ->
            if (info == plan) return@requestPlan
            plan = info
            onRefreshed()
        }
    }

    fun requestUsage() {
        val now = System.currentTimeMillis()
        if (usage != null && now - askedAt < USAGE_MIN_INTERVAL_MS) return
        askedAt = now
        session.queries.requestUsage { report ->
            if (report == null) return@requestUsage
            usage = report.mergedOver(usage).afterResets(System.currentTimeMillis())
            onRefreshed()
        }
    }

    fun requestMcp() {
        session.queries.requestMcpStatus { json ->
            if (json != null) exec("window.cc.mcp && window.cc.mcp($json)")
        }
    }

    fun requestVersion() {
        if (session.binaryVersion != null) return
        session.queries.requestBinaryVersion { payload ->
            val v = payload?.let {
                it["version"]?.jsonPrimitive?.contentOrNull
                    ?: it["binary_version"]?.jsonPrimitive?.contentOrNull
                    ?: it["claude_code_version"]?.jsonPrimitive?.contentOrNull
            }
            if (!v.isNullOrBlank()) {
                session.binaryVersion = v
                onRefreshed()
            }
        }
    }

    private companion object {
        const val USAGE_POLL_MS = 30_000

        const val USAGE_MIN_INTERVAL_MS = 12_000L
    }
}
