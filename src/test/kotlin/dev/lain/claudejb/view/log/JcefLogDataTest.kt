package dev.lain.claudejb.view.log

import dev.lain.claudejb.util.LogRing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JcefLogDataTest {

    private val lines = listOf(
        LogRing.Line(seq = 7, at = 1_700_000_000_000, level = LogRing.Level.WARN, category = "process.ClaudeProcess", text = "stderr: x"),
        LogRing.Line(seq = 8, at = 1_700_000_001_000, level = LogRing.Level.DEBUG, category = "session.SessionGuard", text = "trace"),
    )

    @Test
    fun `the payload carries the switch, the cursor semantics and every line field the page draws`() {
        val json = JcefLogData.logJson(LogRing.Snapshot(lines, reset = true, dropped = 3), debug = true)

        assertTrue((json["debug"] as JsonPrimitive).boolean)
        assertTrue((json["reset"] as JsonPrimitive).boolean)
        val ring = json["ring"] as JsonObject
        assertEquals(LogRing.MAX_LINES, ring["max"]!!.jsonPrimitive.int)
        assertEquals(3L, ring["dropped"]!!.jsonPrimitive.long)

        val first = (json["lines"] as JsonArray)[0] as JsonObject
        assertEquals(7L, first["seq"]!!.jsonPrimitive.long)
        assertEquals(1_700_000_000_000L, first["at"]!!.jsonPrimitive.long)
        assertEquals("warn", first["level"]!!.jsonPrimitive.content)
        assertEquals("process.ClaudeProcess", first["category"]!!.jsonPrimitive.content)
        assertEquals("stderr: x", first["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an incremental answer is not a reset and may carry no lines`() {
        val json = JcefLogData.logJson(LogRing.Snapshot(emptyList(), reset = false, dropped = 0), debug = false)

        assertFalse((json["reset"] as JsonPrimitive).boolean)
        assertFalse((json["debug"] as JsonPrimitive).boolean)
        assertEquals(0, (json["lines"] as JsonArray).size)
    }

    @Test
    fun `the wire level names the floor, and anything unknown means everything`() {
        assertEquals(LogRing.Level.WARN, JcefLogData.levelOf("warn"))
        assertEquals(LogRing.Level.INFO, JcefLogData.levelOf("INFO"))
        assertEquals(LogRing.Level.DEBUG, JcefLogData.levelOf("all"))
        assertEquals(LogRing.Level.DEBUG, JcefLogData.levelOf(null))
    }

    @Test
    fun `the report is the header, a blank line, then one timestamped line per entry`() {
        val text = JcefLogData.reportText(lines, listOf("Claude Code Native 6.0.0", "debug on"))

        assertEquals(
            listOf(
                "Claude Code Native 6.0.0",
                "debug on",
                "",
                "2023-11-14T22:13:20Z WARN  process.ClaudeProcess — stderr: x",
                "2023-11-14T22:13:21Z DEBUG session.SessionGuard — trace",
            ),
            text.trimEnd().lines(),
        )
    }
}
