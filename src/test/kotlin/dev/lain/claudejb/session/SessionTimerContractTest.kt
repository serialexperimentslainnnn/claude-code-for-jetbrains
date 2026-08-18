package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Every repeating timer a session owns must retire on its own AND be stopped when the session is disposed.**
 *
 * Both halves, because they fail differently and neither covers the other. A timer that is not stopped on
 * dispose outlives the tab that owned it: a `javax.swing.Timer` is held by the EDT's own queue, so the
 * closed session, its transcript and its project stay reachable and the ticking carries on against them. A
 * timer that is only stopped on dispose never retires while the tab is open, so an idle chat pays its cost
 * for the whole session — and these poll the filesystem and re-parse transcripts.
 *
 * The three timers themselves live in [PollSchedule] (`ClaudeSession` delegates to `poll`), so the stop-on-
 * dispose half of the property now spans two files: `ClaudeSession.dispose()` must still call
 * `poll.stopAll()`, and [PollSchedule.stopAll] must still stop every one of them. Splitting that check across
 * both files is what keeps this gate meaningful after the extraction — checking only one half would pass a
 * `dispose()` that forgot to call `stopAll()` at all, or a `stopAll()` that forgot a timer.
 *
 * A source gate rather than a test that drives a session, because the failure is invisible from the inside:
 * the leaked timer keeps working perfectly, and asserting that one stopped would mean waiting on a clock,
 * which is how a suite acquires a flaky test.
 */
class SessionTimerContractTest {

    private val pollLines: List<String> = pollSource().readLines()
    private val sessionLines: List<String> = sessionSource().readLines()

    /** The line indices of `PollSchedule.stopAll`, from its signature down to its own closing brace. */
    private val stopAll: IntRange = stopAllRange()

    /** The names of the repeating timers [PollSchedule] declares. */
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

        /** How many the file declares today. A gate that finds none passes silently, which is the failure. */
        const val KNOWN_TIMERS = 3

        val DECLARATION = Regex("""^\s*(?:private )?val (\w+) = javax\.swing\.Timer\(""")

        /** Resolves a source under `session/`, whether the test runs from the module directory or the repo root. */
        fun resolve(name: String): File {
            val path = "src/main/kotlin/dev/lain/claudejb/session/$name"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }

        fun pollSource(): File = resolve(POLL_SOURCE_NAME)
        fun sessionSource(): File = resolve(SESSION_SOURCE_NAME)
    }
}
