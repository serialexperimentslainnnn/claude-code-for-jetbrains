package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **The thread that repaints never waits for `claude` to die.**
 *
 * Only the source can state this, which is why it is a contract and not a driven test. Every door into the
 * teardown needs a `KillableProcessHandler`, and that only exists after a real spawn — so a test that could
 * observe the thread would have to start a process, and one that stubs the process asserts on its own stub.
 *
 * What the teardown costs, and why it is not allowed on the EDT. Closing stdin flushes a pipe and takes the
 * same write lock a producer blocked on a full pipe is already holding. Destroying the process runs
 * `KillableProcessHandler.destroyProcessImpl`, which flushes that pipe again and then, because the handler is
 * built with `setShouldDestroyProcessRecursively(true)`, walks and signals the whole process TREE — which
 * every OS answers by enumerating its process table. The platform's threading model puts all of it off limits
 * on the EDT ("don't traverse VFS, parse PSI, resolve references, or query indexes"; blocking I/O and waiting
 * on external processes belong on a background thread), and `BaseOSProcessHandler.checkEdtAndReadAction`
 * exists precisely to log an error when someone waits for a process handler there.
 *
 * The symptom it produced is the reason for the gate rather than a story about it: every caller of the
 * teardown arrives from the UI — the Stop button, a settings change that restarts, a tab closing — so seconds
 * of it landed in the event that was supposed to redraw the tab bar. Pressing × on a chat painted nothing at
 * all until the kill returned, and the pill of the chat that had just been closed stayed on screen, greyed,
 * for the duration. The three separate symptoms that were reported are that one wait.
 *
 * The runtime half — that the tab leaves the model in the same call, and that the bar is settled before the
 * teardown is even asked for — is `ChatTabsCloseHeadlessTest`.
 *
 * Platform rule: [threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html).
 */
class EdtProcessTeardownContractTest {

    @Test
    fun `terminate hands the teardown to a background thread and does none of it itself`() {
        val body = bodyOf(CLAUDE_PROCESS, "fun $DOOR(")

        assertTrue(body.any { POOLED in it }) {
            "ClaudeProcess.$DOOR no longer names `$POOLED`, so the process teardown is back on the caller's " +
                "thread — which is the EDT for every caller it has. That is the freeze."
        }
        BLOCKING_CALLS.forEach { call ->
            assertFalse(body.any { line -> call in line && POOLED !in line }) {
                "ClaudeProcess.$DOOR performs `$call` in its own body. Both halves of the teardown belong " +
                    "inside the block handed to `$POOLED`; run here they block whoever pressed the button."
            }
        }
    }

    /**
     * One door, so a caller cannot pick the blocking one by accident.
     *
     * The two halves used to be public and were spelled out at each call site in the order they must happen —
     * EOF, then kill — which is a sequence that drifts the moment one site is edited. They are private now and
     * `terminate` is the only way in; this fails if a second public one reappears.
     */
    @Test
    fun `the blocking halves are private and reached only through terminate`() {
        val lines = CLAUDE_PROCESS.readLines()

        BLOCKING_CALLS.forEach { call ->
            assertEquals(1, lines.count { call in it && !it.trimStart().startsWith("*") }) {
                "`$call` appears more than once in ClaudeProcess. It belongs in exactly one place, the " +
                    "private helper `$DOOR` dispatches to a background thread."
            }
        }
        PUBLIC_KILL_DOORS.forEach { signature ->
            assertFalse(lines.any { it.startsWith("$INDENT$signature") }) {
                "ClaudeProcess declares a public `$signature`. A public door onto the teardown is a door " +
                    "onto doing it on the EDT; `$DOOR` is the one that exists, and it is not blocking."
            }
        }
    }

    /**
     * And the two callers that run on the EDT use it.
     *
     * `stop()` is the Stop button and every restart; `dispose()` is a closed tab. Both used to spell out
     * `closeStdin()` then `destroy()` inline, which is what put the kill on the EDT in the first place.
     */
    @Test
    fun `stop and dispose end the process through the one door`() {
        listOf("fun stop(", "override fun dispose(").forEach { signature ->
            val body = bodyOf(CLAUDE_SESSION, signature)

            assertTrue(body.any { "$DOOR()" in it }) {
                "ClaudeSession.$signature no longer ends the process through `$DOOR()`. It runs on the EDT, " +
                    "so whatever it does instead is a UI freeze for as long as `claude` takes to die."
            }
            LEGACY_CALLS.forEach { call ->
                assertFalse(body.any { call in it }) {
                    "ClaudeSession.$signature calls `$call` directly. That is the blocking half, on the EDT."
                }
            }
        }
    }

    /**
     * The lines of a member function, from its signature down to its own closing brace.
     *
     * Relies on the four-space member indentation the formatter enforces, and fails loudly when the signature
     * or the closing brace is not where it expects — a slice that quietly came back empty would make every
     * assertion above pass on nothing.
     */
    private fun bodyOf(file: File, signature: String): List<String> {
        val lines = file.readLines()
        val from = lines.indexOfFirst { it.startsWith(INDENT) && it.trimStart().startsWith(signature) }
        assertTrue(from >= 0) { "no `$signature` declared at member level in ${file.path}" }
        val length = lines.drop(from).indexOfFirst { it == CLOSING_BRACE }
        assertTrue(length > 0) { "`$signature` in ${file.path} has no closing brace at member level" }
        return lines.subList(from, from + length + 1)
    }

    private companion object {

        /** The one public way to end the process. */
        const val DOOR = "terminate"

        /** How it leaves the caller's thread. */
        const val POOLED = "executeOnPooledThread"

        /** The two halves that block: the pipe, then the process table. */
        val BLOCKING_CALLS = listOf("processInput?.close()", "destroyProcess()")

        /** What `stop()`/`dispose()` used to call inline, and must not again. */
        val LEGACY_CALLS = listOf("closeStdin()", ".destroy()")

        /** Public signatures that would re-open a blocking route from the outside. */
        val PUBLIC_KILL_DOORS = listOf("fun closeStdin(", "fun destroy(")

        const val INDENT = "    "
        const val CLOSING_BRACE = "    }"

        val CLAUDE_PROCESS = source("process/ClaudeProcess.kt")
        val CLAUDE_SESSION = source("session/ClaudeSession.kt")

        /** Resolves a source whether the test runs from the module directory or the repository root. */
        fun source(name: String): File {
            val path = "src/main/kotlin/dev/lain/claudejb/$name"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }
    }
}
