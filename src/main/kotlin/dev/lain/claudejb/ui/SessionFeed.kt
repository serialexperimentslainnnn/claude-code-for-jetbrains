package dev.lain.claudejb.ui

import dev.lain.claudejb.protocol.UsageReport
import dev.lain.claudejb.protocol.mergedOver
import dev.lain.claudejb.session.ClaudeSession
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.swing.Timer

/**
 * The per-PROCESS data the dashboard shows: the plan-limit windows, the MCP servers and the binary version.
 *
 * Extracted from `JcefChatPanel`, which is an assembler. This is the panel's only *poller*: it owns the
 * usage timer, the throttle and the cached reply, and it hands the panel a single callback for "there is
 * something new to draw". Everything here is EDT-confined, like the panel itself.
 */
internal class SessionFeed(
    private val session: ClaudeSession,
    /** Runs a snippet in the web view. */
    private val exec: (String) -> Unit,
    /** Re-draw: the dashboard bars AND the composer dots, which read the same [usage]. */
    private val onRefreshed: () -> Unit,
) {

    /**
     * The last `get_usage` reply, and when it was asked for. Cached because the dashboard is pushed on every
     * state change (many per turn) while the usage figures move on the order of minutes — re-asking each
     * time would be a round-trip per keystroke-ish event for a number that has not changed.
     */
    var usage: UsageReport? = null
        private set
    private var askedAt = 0L

    /**
     * Plan-limits poll, unconditional for the panel's whole lifetime.
     *
     * Unlike context and cost — which cannot move while the session idles, so their timer retires at turn end
     * — the quota IS shared state: other sessions, other devices and claude.ai itself consume the same
     * windows, and **a window reset is a wall-clock event that owes nothing to this IDE**.
     *
     * It used to be gated on the panel being showing, and that gate was the bug: a collapsed tool window, or
     * a chat tab that was not the selected one, stopped asking entirely — so a window could reset, or fill
     * from another device, and the panel went on displaying the last figure it happened to catch. "It only
     * updates when I talk to the agent" is exactly what a visibility-gated poll looks like from outside.
     */
    private val timer = Timer(USAGE_POLL_MS) { requestUsage() }.apply { isRepeats = true }

    fun start() = timer.start()

    fun stop() = timer.stop()

    /** Everything that is per-PROCESS rather than per-panel: asked on every launch, not just the first. */
    fun onSessionReady() {
        requestMcp()
        requestVersion()
        requestUsage()
    }

    /**
     * Refreshes the plan-limit windows.
     *
     * Called by the timer every [USAGE_POLL_MS], and directly on the event triggers (a `rate_limit_event`,
     * the dashboard opening, session ready). The throttle is burst protection for those triggers — a run of
     * rate_limit_events must not turn into a request storm — and its floor sits under the timer's period so
     * the periodic tick is never swallowed by it.
     */
    fun requestUsage() {
        val now = System.currentTimeMillis()
        // The throttle must not swallow the FIRST reading: with no data yet there is nothing to protect, and
        // waiting out the interval is the difference between the panel appearing at once and appearing later
        // for no reason the user can see.
        if (usage != null && now - askedAt < USAGE_MIN_INTERVAL_MS) return
        askedAt = now
        session.queries.requestUsage { report ->
            if (report == null) return@requestUsage
            // Merged, not replaced: when the binary's usage fetch falls back to its header-seeded object the
            // reply carries only five_hour/seven_day, and taking it literally made the per-model bars blink
            // out on that poll and back on the next. See `mergedOver`.
            usage = report.mergedOver(usage)
            onRefreshed()
        }
    }

    /** Fetch MCP server status asynchronously and hand the raw payload to the dashboard's MCP health card. */
    fun requestMcp() {
        session.queries.requestMcpStatus { json ->
            if (json != null) exec("window.cc.mcp && window.cc.mcp($json)")
        }
    }

    /** Fetch the CLI binary version once and cache it on the session so the Version row populates. */
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
        /** Period of the plan-limits poll, visible or not — a window reset happens on wall-clock time. */
        const val USAGE_POLL_MS = 30_000

        /**
         * Floor between `get_usage` round-trips — burst protection for the event-driven triggers. MUST stay
         * below [USAGE_POLL_MS], or the periodic tick is silently throttled away and the poll only *looks*
         * like it runs on its period.
         */
        const val USAGE_MIN_INTERVAL_MS = 12_000L
    }
}
