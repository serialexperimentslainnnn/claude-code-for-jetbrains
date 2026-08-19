package dev.lain.claudejb.session

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentStatePollGateTest {

    private var turnActive = false
    private var anySettled = false
    private var anyRunning = false
    private var scans = 0

    private fun schedule() = PollSchedule(
        isRunning = { true },
        turnActive = { turnActive },
        effects = PollSchedule.SessionEffects(edt = { it() }, fireState = {}),
        quota = PollSchedule.QuotaSource(
            requestSessionCost = { it(null as JsonObject?) },
            requestContextUsage = { it(null) },
            onSessionCost = {},
            onContextUsage = {},
        ),
        outputTail = PollSchedule.OutputTailSource(anyTailable = { false }, tailNow = {}),
        agentRevival = PollSchedule.AgentRevivalSource(
            anySettledAgent = { anySettled },
            anyRunningAgent = { anyRunning },
            scanAgents = { scans++ },
        ),
    )

    private fun pollStarts(): Boolean {
        val poll = schedule()
        poll.ensureAgentRevivalPoll()
        val started = poll.agentPollRunning
        poll.stopAll()
        return started
    }

    @Test
    fun `a RUNNING agent keeps the poll alive even with the main session idle`() {
        turnActive = false
        anyRunning = true
        anySettled = false
        assertTrue(pollStarts(), "an agent shown as RUNNING can still finish; the poll must keep watching")
    }

    @Test
    fun `a settled agent still needs a live turn to be worth watching`() {
        anyRunning = false
        anySettled = true
        turnActive = true
        assertTrue(pollStarts(), "a settled agent can revive while a turn runs")
        turnActive = false
        assertFalse(pollStarts(), "with no turn there is nothing that could revive it")
    }

    @Test
    fun `an idle chat with nothing in flight polls zero times`() {
        turnActive = false
        anyRunning = false
        anySettled = false
        assertFalse(pollStarts(), "nothing can change, so nothing should be re-read")
    }

    @Test
    fun `the poll retires itself once every agent has settled and the turn is over`() {
        turnActive = false
        anyRunning = true
        anySettled = false
        val poll = schedule()
        poll.ensureAgentRevivalPoll()
        assertTrue(poll.agentPollRunning)
        anyRunning = false
        anySettled = true
        poll.pollAgentStateForTest()
        assertFalse(poll.agentPollRunning, "with no turn and nothing running, the timer must retire")
        poll.stopAll()
    }
}
