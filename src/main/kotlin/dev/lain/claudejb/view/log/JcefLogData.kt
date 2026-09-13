package dev.lain.claudejb.view.log

import dev.lain.claudejb.util.LogRing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

object JcefLogData {

    fun logJson(snapshot: LogRing.Snapshot, debug: Boolean, max: Int = LogRing.MAX_LINES): JsonObject = buildJsonObject {
        put("debug", debug)
        put("reset", snapshot.reset)
        put(
            "ring",
            buildJsonObject {
                put("max", max)
                put("dropped", snapshot.dropped)
            },
        )
        put("lines", buildJsonArray { snapshot.lines.forEach { add(lineJson(it)) } })
    }

    fun levelOf(wire: String?): LogRing.Level =
        LogRing.Level.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: LogRing.Level.DEBUG

    fun reportText(lines: List<LogRing.Line>, header: List<String>): String = buildString {
        header.forEach { appendLine(it) }
        appendLine()
        lines.forEach { appendLine(reportLine(it)) }
    }

    private fun reportLine(line: LogRing.Line): String =
        "${Instant.ofEpochMilli(line.at)} ${line.level.name.padEnd(LEVEL_WIDTH)} ${line.category} — ${line.text}"

    private fun lineJson(line: LogRing.Line): JsonObject = buildJsonObject {
        put("seq", line.seq)
        put("at", line.at)
        put("level", line.level.name.lowercase())
        put("category", line.category)
        put("text", line.text)
    }

    private const val LEVEL_WIDTH = 5
}
