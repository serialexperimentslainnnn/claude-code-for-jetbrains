package dev.lain.claudejb.controller.session.turn

import dev.lain.claudejb.model.protocol.models.UsageReport
import dev.lain.claudejb.util.PluginLog
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

class QuotaWarnings(private val log: PluginLog, private val announce: Announce) {

    class Announce(
        val inTranscript: (String) -> Unit,
        val asNotification: (String) -> Unit,
    )

    private val announced = HashMap<String, Int>()

    fun onReport(report: UsageReport) {
        report.windows.forEach { (key, w) ->
            w.utilizationPercent()?.let { pct ->
                log.debug { "usage window $key: utilization=${w.utilization} -> $pct%" }
                warnOnCrossing(key, w.title(key), pct)
            }
        }
    }

    private fun warnOnCrossing(window: String, label: String, pct: Int) {
        val already = announced[window] ?: 0
        val crossed = THRESHOLDS.lastOrNull { pct >= it } ?: 0
        if (crossed <= already) {
            if (crossed < already) announced[window] = crossed
            return
        }
        announced[window] = crossed
        val message = "$label quota at $pct%."
        announce.inTranscript(message)
        if (crossed >= HIGH) announce.asNotification(message)
    }

    fun logReply(payload: JsonObject?) {
        val limits = payload?.get("rate_limits")
        if (limits == null || limits is JsonNull) {
            logOnce("get_usage: rate_limits=null (available=${payload?.get("rate_limits_available")})")
            return
        }
        logOnce("get_usage: ${limits.toString().take(LOG_CHARS)}")
    }

    private var lastLogged: String? = null

    private fun logOnce(line: String) {
        if (line == lastLogged) return
        lastLogged = line
        log.debug { line }
    }

    private companion object {
        val THRESHOLDS = listOf(65, 85)
        const val HIGH = 85

        const val LOG_CHARS = 2000
    }
}
