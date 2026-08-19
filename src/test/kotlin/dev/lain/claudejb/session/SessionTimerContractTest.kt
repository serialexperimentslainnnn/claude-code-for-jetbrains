package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SessionTimerContractTest {

    private val pollLines: List<String> = pollSource().readLines()
    private val sessionLines: List<String> = sessionSource().readLines()

    private val stopAll: IntRange = stopAllRange()

    private val timers: List<String> = pollLines.mapNotNull { DECLARATION.find(it)?.groupValues?.get(1) }

    @Test
    fun `the scan finds the timers it is about`() {
        assertTrue(timers.size >= KNOWN_TIMERS) {
            "Found $timers in $POLL_SOURCE_NAME. This gate has stopped recognising how a timer is declared here, " +
                "so it would pass whatever the file did."
        }
    }

    @Test
    fun `dispose still hands off to PollSchedule#stopAll`() {
        assertTrue(sessionLines.any { it.trim() == "poll.stopAll()" }) {
            "$SESSION_SOURCE_NAME's dispose() no longer calls `poll.stopAll()` — a closed tab would leak every " +
                "timer PollSchedule owns."
        }
    }

    @Test
    fun `every timer is stopped by PollSchedule#stopAll`() {
        timers.forEach { timer ->
            assertTrue(stopAll.any { stops(timer, it) }) {
                "`$timer` is never stopped in `PollSchedule.stopAll()`. The EDT's timer queue holds it, so it " +
                    "keeps the disposed session — and the project behind it — alive and ticking after the tab is gone."
            }
        }
    }

    @Test
    fun `every timer also retires itself`() {
        timers.forEach { timer ->
            assertTrue(pollLines.indices.any { it !in stopAll && stops(timer, it) }) {
                "`$timer` is stopped only in `stopAll()`, so it runs for the whole life of the tab whatever " +
                    "the session is doing. Each of these polls something real; what makes an idle chat free " +
                    "is the timer stopping the moment its own reason to run goes away."
            }
        }
    }

    private fun stops(timer: String, line: Int): Boolean = "$timer.stop()" in pollLines[line]

    private fun stopAllRange(): IntRange {
        val from = pollLines.indexOfFirst { it.trimStart().startsWith("fun stopAll(") }
        assertTrue(from >= 0) { "no `stopAll` declared in $POLL_SOURCE_NAME" }
        val length = pollLines.drop(from).indexOfFirst { it == CLOSING_BRACE }
        assertTrue(length > 0) { "`stopAll` in $POLL_SOURCE_NAME has no closing brace at member level" }
        return from..from + length
    }

    private companion object {

        const val POLL_SOURCE_NAME = "PollSchedule.kt"
        const val SESSION_SOURCE_NAME = "ClaudeSession.kt"
        const val CLOSING_BRACE = "    }"

        const val KNOWN_TIMERS = 3

        val DECLARATION = Regex("""^\s*(?:private )?val (\w+) = javax\.swing\.Timer\(""")

        fun resolve(name: String): File {
            val path = "src/main/kotlin/dev/lain/claudejb/session/$name"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }

        fun pollSource(): File = resolve(POLL_SOURCE_NAME)
        fun sessionSource(): File = resolve(SESSION_SOURCE_NAME)
    }
}
