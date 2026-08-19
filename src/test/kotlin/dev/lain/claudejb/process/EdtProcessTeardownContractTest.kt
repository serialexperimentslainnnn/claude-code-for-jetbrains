package dev.lain.claudejb.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

    private fun bodyOf(file: File, signature: String): List<String> {
        val lines = file.readLines()
        val from = lines.indexOfFirst { it.startsWith(INDENT) && it.trimStart().startsWith(signature) }
        assertTrue(from >= 0) { "no `$signature` declared at member level in ${file.path}" }
        val length = lines.drop(from).indexOfFirst { it == CLOSING_BRACE }
        assertTrue(length > 0) { "`$signature` in ${file.path} has no closing brace at member level" }
        return lines.subList(from, from + length + 1)
    }

    private companion object {

        const val DOOR = "terminate"

        const val POOLED = "executeOnPooledThread"

        val BLOCKING_CALLS = listOf("processInput?.close()", "destroyProcess()")

        val LEGACY_CALLS = listOf("closeStdin()", ".destroy()")

        val PUBLIC_KILL_DOORS = listOf("fun closeStdin(", "fun destroy(")

        const val INDENT = "    "
        const val CLOSING_BRACE = "    }"

        val CLAUDE_PROCESS = source("process/ClaudeProcess.kt")
        val CLAUDE_SESSION = source("session/ClaudeSession.kt")

        fun source(name: String): File {
            val path = "src/main/kotlin/dev/lain/claudejb/$name"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }
    }
}
