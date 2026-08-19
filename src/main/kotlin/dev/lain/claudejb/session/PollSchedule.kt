package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.ContextUsage
import kotlinx.serialization.json.JsonObject

/**
 * Owns the three timers a live session runs, and the retirement rule for each — extracted out of
 * `ClaudeSession` because that rule is already subtle enough per timer to want one file, not three scattered
 * private functions:
 *
 * - **quota** ([pollQuota] / [startQuotaPolling], [QUOTA_POLL_MS] = 1 s): session cost + context usage. Runs
 *   only while a turn is active or at least one panel is watching, and retires itself the moment neither is
 *   true — an idle session polls zero times.
 * - **output tail** ([ensureOutputTail], also [QUOTA_POLL_MS]): a running background task's output file,
 *   independent of the turn — a task backgrounded near the end of one keeps writing after it ends, which is
 *   exactly when the user goes to read it. Stops itself the moment nothing is tailable.
 * - **agent state** ([ensureAgentRevivalPoll], [AGENT_REVIVAL_POLL_MS] = 5 s): re-reads the agent tree so an
 *   agent's colour follows what its transcript says, in BOTH directions — a RESUMED one stops reading as
 *   finished, and a finished one stops reading as running. A pass re-parses every admitted agent's whole
 *   transcript, so it costs far more than the other two and runs at a slower cadence; its gate
 *   ([agentStateCouldChange]) is what keeps that honest — with every agent settled and no turn running,
 *   nothing can move and the timer retires itself, so an idle chat with nothing in flight polls zero times.
 *
 * The three [QuotaSource]/[OutputTailSource]/[AgentRevivalSource] parameters are each exactly what their own
 * poller needs — grouped so the constructor names three dependencies instead of the eight closures inside
 * them. Every closure is exactly the call `ClaudeSession` made inline before this was split out — nothing
 * about when a poll fires, retires, or which state it reads or writes has changed.
 */
class PollSchedule(
    private val isRunning: () -> Boolean,
    private val turnActive: () -> Boolean,
    private val effects: SessionEffects,
    private val quota: QuotaSource,
    private val outputTail: OutputTailSource,
    private val agentRevival: AgentRevivalSource,
) {

    /** The two session-wide effects every poller needs: hopping to the EDT, and pushing a state refresh. */
    class SessionEffects(val edt: (() -> Unit) -> Unit, val fireState: () -> Unit)

    /** What [pollQuota] needs: the two async requests, and where a non-null result lands. */
    class QuotaSource(
        val requestSessionCost: ((JsonObject?) -> Unit) -> Unit,
        val requestContextUsage: ((ContextUsage?) -> Unit) -> Unit,
        val onSessionCost: (JsonObject) -> Unit,
        val onContextUsage: (ContextUsage) -> Unit,
    )

    /** What the output-tail poll needs: whether anything is worth tailing, and how to tail it. */
    class OutputTailSource(val anyTailable: () -> Boolean, val tailNow: () -> Unit)

    /**
     * What the agent-state poll needs: whether anything could still change, and how to rescan the tree.
     *
     * Two questions, not one, because an agent's state can move in BOTH directions and each has its own
     * trigger. [anySettledAgent] is the revival case — a finished agent that gets more records is working
     * again — and it can only happen while a turn runs. [anyRunningAgent] is the opposite and was missing:
     * an agent shown as RUNNING may have finished on disk with nothing in the main stream to say so.
     */
    class AgentRevivalSource(
        val anySettledAgent: () -> Boolean,
        val anyRunningAgent: () -> Boolean,
        val scanAgents: () -> Unit,
    )

    private val quotaPollTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollQuota() }.apply { isRepeats = true }

    /** Guards [pollQuota] against overlapping round-trips; see the comment there. EDT-confined. */
    private var quotaPollInFlight = false

    /**
     * A separate timer from [quotaPollTimer] and NOT tied to the turn: a task backgrounded near the end of a
     * turn keeps writing after the turn is over, and that is precisely when the user goes to read it. It
     * costs a `size` check per running task per tick and stops itself the moment nothing is tailable, so an
     * idle session runs no timer at all.
     */
    private val outputTailTimer = javax.swing.Timer(QUOTA_POLL_MS) { pollLiveOutput() }.apply { isRepeats = true }

    private fun pollLiveOutput() {
        if (!outputTail.anyTailable()) {
            outputTailTimer.stop()
            return
        }
        outputTail.tailNow()
    }

    /** Starts the live-output poll if anything is worth tailing. EDT. Idempotent — a running timer is left alone. */
    fun ensureOutputTail() {
        if (outputTail.anyTailable() && !outputTailTimer.isRunning) outputTailTimer.start()
    }

    /**
     * Re-reads the agent tree while a turn runs, so an agent that is RESUMED stops reading as finished.
     *
     * No event covers this: a settled agent revives by getting more records in its own transcript, and a nested
     * one has no `tool_use_id` at all, so it revives only through its parent. Neither writes anything to the
     * main stream — the growth is visible only by walking the directory again, which is what the scan acts on.
     */
    private val agentRevivalTimer = javax.swing.Timer(AGENT_REVIVAL_POLL_MS) { pollAgentRevival() }.apply {
        isRepeats = true
    }

    /**
     * Whether any agent's state could still change, and therefore whether the tree is worth re-reading.
     *
     * Two directions, and the second one is the fix for agents frozen on green:
     *  - **A RUNNING agent can finish**, and nothing in the main stream necessarily says so — the
     *    `task_notification` carries an optional `tool_use_id` that several of the binary's call sites omit,
     *    so the only witness is the agent's own transcript. This does NOT depend on a turn being active: a
     *    backgrounded agent finishes after the turn that spawned it ended, which is precisely the case the
     *    old gate could not see. It stopped the timer the moment the main session went idle, so a subagent
     *    that finished while nothing else was happening stayed RUNNING — green — for the rest of the session.
     *  - **A settled agent can revive**, by getting more records; that one genuinely needs a live turn, since
     *    an agent cannot start writing again with no turn behind it.
     *
     * When neither holds — every agent settled and no turn running — nothing can move, and the timer retires
     * itself exactly as before, so an idle chat with nothing in flight still polls zero times.
     */
    private fun agentStateCouldChange(): Boolean =
        agentRevival.anyRunningAgent() || (turnActive() && agentRevival.anySettledAgent())

    private fun pollAgentRevival() {
        if (!agentStateCouldChange()) {
            agentRevivalTimer.stop()
            return
        }
        agentRevival.scanAgents()
    }

    /** Starts the poll if any agent's state could still move. EDT. Idempotent — a running timer is left alone. */
    fun ensureAgentRevivalPoll() {
        if (agentStateCouldChange() && !agentRevivalTimer.isRunning) agentRevivalTimer.start()
    }

    /** Whether the quota timer is currently running — used to decide whether to stop it when the last listener leaves. */
    val quotaRunning: Boolean get() = quotaPollTimer.isRunning

    /**
     * Whether the agent-state timer is currently running — the observable consequence of
     * [agentStateCouldChange], which is private because nothing in production asks it directly.
     *
     * Exposed for the gate's test, and as a read-only property rather than a hook: the alternative is a test
     * that waits on a real `javax.swing.Timer` to tick, which is how a suite acquires a flaky test.
     */
    val agentPollRunning: Boolean get() = agentRevivalTimer.isRunning

    /** Runs one agent-state pass synchronously — the tick, without the clock. See [agentPollRunning]. */
    fun pollAgentStateForTest() = pollAgentRevival()

    /** Stops just the quota timer, e.g. when the last observing panel goes away. */
    fun stopQuota() = quotaPollTimer.stop()

    /**
     * Fire one session-cost + context-usage poll; results are cached (via [QuotaSource.onSessionCost]/
     * [QuotaSource.onContextUsage]) and pushed to panels via [SessionEffects.fireState]. No-op while the process is not
     * running (the control requests would deliver null and clobber the cached last-good values, blanking the
     * usage meter); and even when running we only overwrite the cache on a non-null result, so a transient
     * null never blanks the panels — the last good values stay until a real one arrives.
     */
    fun pollQuota() {
        if (!isRunning()) return
        // Never let polls overlap. The control channel is SHARED with `can_use_tool` and the tool-result
        // traffic, so at a one-second cadence a binary busy streaming answers slower than we ask, the
        // requests pile up, and everything queued behind them — tool cards finishing, permissions — waits
        // on two numbers. One poll in flight at a time; a slow answer skips a tick instead of stacking.
        if (quotaPollInFlight) return
        quotaPollInFlight = true
        var pending = 2
        // ONE state push per poll, not one per answer: a full push re-serializes meta + state + dashboard,
        // and doing it twice a second competed with the streaming transcript for no new information.
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
        // The timer exists to track a turn AS IT RUNS, nothing else. Context and cost cannot move while the
        // session sits idle, so polling forever was a round-trip through the binary for two numbers that
        // provably had not changed — and retiring at turn end is also what makes the 1-second cadence
        // affordable at all. The turn-start, turn-end and process-ready paths each poll directly, so nothing
        // waits on a clock.
        if (!turnActive()) effects.edt { quotaPollTimer.stop() }
    }

    /** Begin tracking a running turn: poll now, then keep the meters live until it ends. */
    fun startQuotaPolling() = effects.edt {
        pollQuota()
        if (!quotaPollTimer.isRunning) quotaPollTimer.start()
    }

    /** Stops all three timers — used on dispose, so a closed tab leaks no EDT timer. */
    fun stopAll() {
        quotaPollTimer.stop()
        outputTailTimer.stop()
        agentRevivalTimer.stop()
    }

    companion object {
        /** Interval (ms) of the session-scoped quota poll (get_session_cost + get_context_usage), shared by all
         *  ChatPanels observing this session — one timer per session, not one per tab.
         *
         *  One second, and the budget holds because of two multipliers already in place: the timer only runs
         *  WHILE A TURN IS ACTIVE (it retires at turn end — idle sessions poll zero times), and both requests
         *  are local IPC to the `claude` process, which answers from its own counters without a network hop.
         *  At 60s the context meter and cost sat visibly frozen through a whole turn and only told the truth
         *  after it ended, which reads as a broken meter exactly while the user is watching it. */
        const val QUOTA_POLL_MS = 1_000

        /** Interval (ms) of the agent-revival rescan — see [agentRevivalTimer].
         *
         *  Five seconds, not one: a pass re-parses every admitted agent's transcript, and a session that ran
         *  dozens of agents is exactly the one this feature exists for, so the cost scales with the worst case.
         *  A revived agent takes seconds to produce anything a user could read, so five is below the point where
         *  the tab's status could be told apart from instant — and the gate keeps the timer off a chat that is
         *  idle or has nothing settled, which is the majority of a session's wall time. */
        const val AGENT_REVIVAL_POLL_MS = 5_000
    }
}
