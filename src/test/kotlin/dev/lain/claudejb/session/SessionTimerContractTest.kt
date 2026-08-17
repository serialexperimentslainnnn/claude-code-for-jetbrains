package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Every repeating timer a session owns must retire on its own AND be stopped when the session is disposed.**
 *
 * Both halves, because they fail differently and neither covers the other. A timer that is not stopped in
 * `dispose` outlives the tab that owned it: a `javax.swing.Timer` is held by the EDT's own queue, so the
 * closed session, its transcript and its project stay reachable and the ticking carries on against them. A
 * timer that is only stopped in `dispose` never retires while the tab is open, so an idle chat pays its cost
 * for the whole session — and these poll the filesystem and re-parse transcripts.
 *
 * A source gate rather than a test that drives a session, because the failure is invisible from the inside:
 * the leaked timer keeps working perfectly, and asserting that one stopped would mean waiting on a clock,
 * which is how a suite acquires a flaky test.
 */
class SessionTimerContractTest {

    private val lines: List<String> = source().readLines()

    /** The line indices of `dispose`, from its signature down to its own closing brace. */
    private val dispose: IntRange = disposeRange()

    /** The names of the repeating timers the session declares. */
    private val timers: List<String> = lines.mapNotNull { DECLARATION.find(it)?.groupValues?.get(1) }

    @Test
    fun `the scan finds the timers it is about`() {
        assertTrue(timers.size >= KNOWN_TIMERS) {
            "Found $timers in $SOURCE_NAME. This gate has stopped recognising how a timer is declared here, " +
                "so it would pass whatever the file did."
        }
    }

    @Test
    fun `every timer is stopped when the session is disposed`() {
        timers.forEach { timer ->
            assertTrue(dispose.any { stops(timer, it) }) {
                "`$timer` is never stopped in `dispose`. The EDT's timer queue holds it, so it keeps the " +
                    "disposed session — and the project behind it — alive and ticking after the tab is gone."
            }
        }
    }

    @Test
    fun `every timer also retires itself`() {
        timers.forEach { timer ->
            assertTrue(lines.indices.any { it !in dispose && stops(timer, it) }) {
                "`$timer` is stopped only in `dispose`, so it runs for the whole life of the tab whatever " +
                    "the session is doing. Each of these polls something real; what makes an idle chat free " +
                    "is the timer stopping the moment its own reason to run goes away."
            }
        }
    }

    private fun stops(timer: String, line: Int): Boolean = "$timer.stop()" in lines[line]

    private fun disposeRange(): IntRange {
        val from = lines.indexOfFirst { it.trimStart().startsWith("override fun dispose(") }
        assertTrue(from >= 0) { "no `dispose` declared in $SOURCE_NAME" }
        val length = lines.drop(from).indexOfFirst { it == CLOSING_BRACE }
        assertTrue(length > 0) { "`dispose` in $SOURCE_NAME has no closing brace at member level" }
        return from..from + length
    }

    private companion object {

        const val SOURCE_NAME = "ClaudeSession.kt"
        const val CLOSING_BRACE = "    }"

        /** How many the file declares today. A gate that finds none passes silently, which is the failure. */
        const val KNOWN_TIMERS = 3

        val DECLARATION = Regex("""^\s*(?:private )?val (\w+) = javax\.swing\.Timer\(""")

        /** Resolves the source whether the test runs from the module directory or the repository root. */
        fun source(): File {
            val path = "src/main/kotlin/dev/lain/claudejb/session/$SOURCE_NAME"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }
    }
}
