package dev.lain.claudejb.session

import com.intellij.openapi.diagnostic.Logger
import dev.lain.claudejb.protocol.UsageReport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Telling the user their quota is running out, once per threshold.
 *
 * Its own subject, not a corner of the usage query: the query is a round-trip, this is a policy about when
 * a number is worth interrupting somebody for. Kept apart so the rule can be read — and changed — without
 * touching the request that happens to trigger it.
 *
 * EDT-confined: [onReport] runs from the usage callback, which has already hopped.
 */
class QuotaWarnings(private val log: Logger, private val announce: Announce) {

    /**
     * The two ways a warning is told, as one parameter.
     *
     * They are a pair by construction: the transcript row is always written and the IDE notification is the
     * escalation for the second threshold. Two same-shaped lambdas side by side in a parameter list are
     * exactly the kind of thing that gets swapped in a refactor and still compiles.
     */
    class Announce(
        /** A line in the transcript — the warning is about the session, not a dialog. */
        val inTranscript: (String) -> Unit,
        /** An IDE notification, for the threshold the user may be looking away from the chat for. */
        val asNotification: (String) -> Unit,
    )

    /** Window → the highest threshold already announced for it. */
    private val announced = HashMap<String, Int>()

    /** Every window in a fresh report: logged, and announced if it has just crossed a threshold. */
    fun onReport(report: UsageReport) {
        report.windows.forEach { (key, w) ->
            // Logged at INFO, and not as noise: when this fired a false "quota at 100%" there was nothing in
            // idea.log to check it against, because only the EVENT path was traced. A wrong number the user
            // can see must leave the raw value behind that produced it.
            w.utilizationPercent()?.let { pct ->
                log.info("usage window $key: utilization=${w.utilization} -> $pct%")
                warnOnCrossing(key, w.title(key), pct)
            }
        }
    }

    /**
     * Announces the first time a window crosses 65%, and again at 85%.
     *
     * ONCE per threshold per window, and only on the way UP: this is checked on every usage refresh, and a
     * warning that repeats every thirty seconds is one the user learns to ignore — which costs exactly the
     * warning that mattered. The record is cleared when the figure falls back below a threshold, so the next
     * billing window warns again.
     *
     * 85% also raises an IDE notification, not just a transcript row: by then the user may be watching the
     * editor rather than the chat, and the point of the second threshold is that the wall is close enough to
     * change what they do next.
     *
     * [window] is the record's key and [label] what the user reads: they diverge for the per-model windows,
     * whose key is synthesised (`model_scoped:Fable`) precisely because the server names them and nothing
     * else does. Titling from the key would announce that synthetic string verbatim.
     */
    private fun warnOnCrossing(window: String, label: String, pct: Int) {
        val already = announced[window] ?: 0
        val crossed = THRESHOLDS.lastOrNull { pct >= it } ?: 0
        if (crossed <= already) {
            // Dropped below a threshold (the window reset, or the API revised it down): re-arm.
            if (crossed < already) announced[window] = crossed
            return
        }
        announced[window] = crossed
        val message = "$label quota at $pct%."
        announce.inTranscript(message)
        if (crossed >= HIGH) announce.asNotification(message)
    }

    /**
     * Logs what a usage reply actually came back with, once per poll, at INFO.
     *
     * The derived per-window lines cannot answer the question that keeps coming up — *is the figure on
     * screen stale, or is the server really still saying that?* — because a window the reply omits leaves no
     * line at all, and a carried-forward one is indistinguishable from a fresh one. This prints the wire.
     * It is what told us the binary was not caching, and that the replies during a two-hour exhausted window
     * were complete rather than the header-seeded fallback.
     */
    fun logReply(payload: JsonObject?) {
        val limits = payload?.get("rate_limits")
        if (limits == null || limits is JsonNull) {
            // NOT the same as an empty object: the binary sends null when plan limits do not apply at all
            // (API key, Bedrock, Vertex) or when its own fetch had nothing to fall back on.
            log.info("get_usage: rate_limits=null (available=${payload?.get("rate_limits_available")})")
            return
        }
        log.info("get_usage: ${limits.toString().take(LOG_CHARS)}")
    }

    private companion object {
        /**
         * Quota levels worth interrupting the user about, ascending. They match the composer dot's colour
         * scale exactly (blue → amber → red), so the warning and the indicator always agree.
         */
        val THRESHOLDS = listOf(65, 85)
        const val HIGH = 85

        /** Truncation for the reply trace — a bound, since the payload is not ours to size. */
        const val LOG_CHARS = 2000
    }
}
