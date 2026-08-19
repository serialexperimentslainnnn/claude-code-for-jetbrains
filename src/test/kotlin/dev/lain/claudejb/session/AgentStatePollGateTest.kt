package dev.lain.claudejb.session

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **The gate that decides whether the agent tree is re-read — the reported bug was that it stopped watching.**
 *
 * An agent's colour comes from its own transcript, but only a scan reads it, and only this poll runs a scan
 * when nothing is happening in the main stream. The old gate was `turnActive() && anySettledAgent()`, which
 * failed in exactly the reported way: a subagent that finished while the main session sat idle had no live
 * turn (so the timer had already stopped) and was not settled (it was the thing that needed settling), so
 * nothing ever re-read it and it stayed RUNNING — green — for the rest of the session.
 *
 * The property under test is the gate, not the clock: asserting that a `javax.swing.Timer` ticked would mean
 * waiting on real time, which is how a suite acquires a flaky test. So this drives [PollSchedule] with the
 * closures it is built from and asserts whether a scan was asked for.
 */
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

    /** The gate is private; `ensureAgentRevivalPoll` starting the timer is its observable consequence. */
    private fun pollStarts(): Boolean {
        val poll = schedule()
        poll.ensureAgentRevivalPoll()
        val started = poll.agentPollRunning
        poll.stopAll()
        return started
    }

    @Test
    fun `a RUNNING agent keeps the poll alive even with the main session idle`() {
        // THE REPORTED BUG. A backgrounded subagent finishes after the turn that spawned it ended; the main
        // stream says nothing (a task_notification carries an optional tool_use_id that several of the
        // binary's call sites omit), so the transcript is the only witness — and only a scan reads it.
        turnActive = false
        anyRunning = true
        anySettled = false
        assertTrue(pollStarts(), "an agent shown as RUNNING can still finish; the poll must keep watching")
    }

    @Test
    fun `a settled agent still needs a live turn to be worth watching`() {
        // The revival direction, unchanged: an agent cannot start writing again with no turn behind it.
        anyRunning = false
        anySettled = true
        turnActive = true
        assertTrue(pollStarts(), "a settled agent can revive while a turn runs")
        turnActive = false
        assertFalse(pollStarts(), "with no turn there is nothing that could revive it")
    }

    @Test
    fun `an idle chat with nothing in flight polls zero times`() {
        // The half that keeps the fix honest: this pass re-parses every admitted agent's whole transcript, so
        // a poll that never retires is a real cost. Nothing running, nothing settled, no turn — no timer.
        turnActive = false
        anyRunning = false
        anySettled = false
        assertFalse(pollStarts(), "nothing can change, so nothing should be re-read")
    }

    @Test
    fun `the poll retires itself once every agent has settled and the turn is over`() {
        // Same shape as the live case: it starts while an agent runs, and stops on the tick after it stops
        // running — which is what stops an idle chat paying for the scan for ever.
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
