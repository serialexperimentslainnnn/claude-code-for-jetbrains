package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ContextUsage
import kotlinx.serialization.json.JsonObject

class PollSchedule(
    private val isRunning: () -> Boolean,
    private val turnActive: () -> Boolean,
    private val effects: SessionEffects,
    private val quota: QuotaSource,
    private val outputTail: OutputTailSource,
    private val agentRevival: AgentRevivalSource,
) {

    class SessionEffects(val edt: (() -> Unit) -> Unit, val fireState: () -> Unit)

    class QuotaSource(
        val requestSessionCost: ((JsonObject?) -> Unit) -> Unit,
        val requestContextUsage: ((ContextUsage?) -> Unit) -> Unit,
        val onSessionCost: (JsonObject) -> Unit,
        val onContextUsage: (ContextUsage) -> Unit,
    )

    class OutputTailSource(val anyTailable: () -> Boolean, val tailNow: () -> Unit)

    class AgentRevivalSource(
        val anySettledAgent: () -> Boolean,
        val anyRunningAgent: () -> Boolean,
        val scanAgents: () -> Unit,
    )

    private val quotaPollTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollQuota() }.apply { isRepeats = true }

    private var quotaPollInFlight = false

    private val outputTailTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollLiveOutput() }.apply { isRepeats = true }

    private fun pollLiveOutput() {
        if (!outputTail.anyTailable()) {
            outputTailTimer.stop()
            return
        }
        outputTail.tailNow()
    }

    fun ensureOutputTail() {
        if (outputTail.anyTailable() && !outputTailTimer.isRunning) outputTailTimer.start()
    }

    private val agentRevivalTimer = javax.swing.Timer(AGENT_REVIVAL_POLL_MS) { pollAgentRevival() }.apply {
        isRepeats = true
    }

    private fun agentStateCouldChange(): Boolean =
        agentRevival.anyRunningAgent() || (turnActive() && agentRevival.anySettledAgent())

    private fun pollAgentRevival() {
        if (!agentStateCouldChange()) {
            agentRevivalTimer.stop()
            return
        }
        agentRevival.scanAgents()
    }

    fun ensureAgentRevivalPoll() {
        if (agentStateCouldChange() && !agentRevivalTimer.isRunning) agentRevivalTimer.start()
    }

    val quotaRunning: Boolean get() = quotaPollTimer.isRunning

    val agentPollRunning: Boolean get() = agentRevivalTimer.isRunning

    fun pollAgentStateForTest() = pollAgentRevival()

    fun stopQuota() = quotaPollTimer.stop()

    fun pollQuota() {
        if (!isRunning()) return
        if (quotaPollInFlight) return
        quotaPollInFlight = true
        var pending = 2
        val settle = {
            if (--pending == 0) {
                quotaPollInFlight = false
                effects.fireState()
            }
        }
        quota.requestSessionCost { cost ->
            if (cost != null) quota.onSessionCost(cost)
            settle()
        }
        quota.requestContextUsage { cu ->
            if (cu != null) quota.onContextUsage(cu)
            settle()
        }
        if (!turnActive()) effects.edt { quotaPollTimer.stop() }
    }

    fun startQuotaPolling() = effects.edt {
        pollQuota()
        if (!quotaPollTimer.isRunning) quotaPollTimer.start()
    }

    fun stopAll() {
        quotaPollTimer.stop()
        outputTailTimer.stop()
        agentRevivalTimer.stop()
    }

    companion object {
        const val QUOTA_POLL_MS = 1_000

        const val AGENT_REVIVAL_POLL_MS = 5_000
    }
}
