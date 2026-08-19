package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EdtAuthContractTest {

    @Test
    fun `start asks the question that cannot block`() {
        val body = bodyOf(CLAUDE_SESSION, "fun start(")

        assertTrue(body.any { HELD in it }) {
            "ClaudeSession.start runs on the EDT, so it must ask AuthGate.$HELD. It no longer does."
        }
        assertFalse(body.any { BLOCKING in it }) {
            "ClaudeSession.start runs on the EDT and calls AuthGate.$BLOCKING, which resolves an unknown " +
                "answer by spawning `claude auth status` and waiting for it. That is the UI freeze this " +
                "split exists to remove; ask $HELD and treat UNKNOWN as 'neither launch nor prompt'."
        }
    }

    @Test
    fun `the cheap answer starts no process`() {
        val body = bodyOf(AUTH_GATE, "fun $HELD(")

        SPAWN_DOORS.forEach { door ->
            assertFalse(body.any { door in it }) {
                "AuthGate.$HELD names $door, so it can start a process. It is the one question the EDT is " +
                    "allowed to ask: it may read the safe, the settings and the last cached answer, and " +
                    "nothing else. Anything that needs the binary belongs in $BLOCKING."
            }
        }
    }

    @Test
    fun `the cheap answer never sources the user's shell`() {
        val body = bodyOf(AUTH_GATE, "fun $HELD(")
        val guard = body.indexOfFirst { SCRIPT_GUARD in it }
        val env = body.indexOfFirst { LAUNCH_ENV in it }

        assertTrue(env >= 0) { "AuthGate.$HELD no longer builds the launch env; point this gate at the new shape." }
        assertTrue(guard in 0 until env) {
            "AuthGate.$HELD reaches `$LAUNCH_ENV` without `$SCRIPT_GUARD` deciding first. That call sources " +
                "the user's shell script and waits for it, so on the EDT it is the freeze this split exists " +
                "to remove — with a shell on the other end instead of the binary."
        }
    }

    @Test
    fun `the blocking answer is the cheap one plus the probe`() {
        val body = bodyOf(AUTH_GATE, "fun $BLOCKING(")

        assertTrue(body.any { HELD in it }) {
            "AuthGate.$BLOCKING no longer delegates to $HELD, so the two now decide what counts as an " +
                "identity separately. They will disagree, and the EDT and the pooled path will then draw " +
                "different screens from the same safe."
        }
    }

    @Test
    fun `losing the binary reaches the EDT in one hop`() {
        val body = bodyOf(CLAUDE_SESSION, "fun refreshBootState(")
        val head = body.takeWhile { NULL_BINARY_RETURN !in it }

        assertTrue(head.size < body.size) {
            "refreshBootState no longer returns on `$NULL_BINARY_RETURN`, so this gate cannot find the " +
                "region it is about. Point it at the new shape rather than deleting it."
        }
        assertEquals(1, head.count { EDT_HOP in it }) {
            "Stopping the vanished binary's session and publishing `binaryMissing` must happen in ONE `$EDT_HOP` " +
                "block. Split across two, the first one pushes `binaryMissing: true` while the process is " +
                "still up and the page draws the install card over a chat that is still answering."
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

        const val HELD = "heldCredential"
        const val BLOCKING = "hasCredential"

        val SPAWN_DOORS = listOf("AuthCli.", "ClaudeBinaryLocator.")

        const val LAUNCH_ENV = "resolveEnv()"
        const val SCRIPT_GUARD = "sourceScript"

        const val NULL_BINARY_RETURN = "if (binary == null) return"
        const val EDT_HOP = "edt {"

        const val INDENT = "    "
        const val CLOSING_BRACE = "    }"

        val CLAUDE_SESSION = source("ClaudeSession.kt")
        val AUTH_GATE = source("AuthGate.kt")

        fun source(name: String): File {
            val path = "src/main/kotlin/dev/lain/claudejb/session/$name"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }
    }
}
