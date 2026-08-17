package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Three properties of the boot path that **only the source can state**, because none of them is observable
 * from a test that drives the code.
 *
 * 1. *Which question the EDT asks.* Both spellings return an answer; the difference is that one of them can
 *    spawn `claude auth status` and wait for it. A test that called it would either freeze or, on a machine
 *    with a fresh cached answer, pass while proving nothing.
 * 2. *That the cheap question stays cheap.* Its value is that it never starts a process — and "it did not
 *    spawn one this time" is not the same claim.
 * 3. *That losing the binary is ONE hop to the EDT.* Two hops publish a state that cannot exist, `running`
 *    together with `binaryMissing`, for as long as the event queue takes. The window is real and the page
 *    draws the install card over a live chat inside it, but its width is a scheduling accident, so asserting
 *    on it would mean asserting on a race.
 *
 * The behaviour on the other side of these — that an undecidable answer draws no card — is
 * `AuthGateCredentialHeadlessTest`.
 */
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

    /**
     * The spawn this one is about hides behind an innocent name: building the launch env runs the user's
     * source script through their shell. It is legitimate here only while no script is configured, so what
     * has to hold is the ORDER — the guard decides before the call is reachable.
     */
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

    /**
     * The lines of a member function, from its signature down to its own closing brace.
     *
     * Relies on the four-space member indentation the formatter enforces, and fails loudly when the
     * signature or the closing brace is not where it expects — a slice that quietly came back empty would
     * make every assertion above pass on nothing.
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

        /** The EDT-safe question, and the one that resolves its third answer by running the binary. */
        const val HELD = "heldCredential"
        const val BLOCKING = "hasCredential"

        /** The two ways out of `AuthGate` that end in a spawned process. */
        val SPAWN_DOORS = listOf("AuthCli.", "ClaudeBinaryLocator.")

        /** The third one, which does not look like one: `resolveEnv` sources the configured shell script. */
        const val LAUNCH_ENV = "resolveEnv()"
        const val SCRIPT_GUARD = "sourceScript"

        const val NULL_BINARY_RETURN = "if (binary == null) return"
        const val EDT_HOP = "edt {"

        const val INDENT = "    "
        const val CLOSING_BRACE = "    }"

        val CLAUDE_SESSION = source("ClaudeSession.kt")
        val AUTH_GATE = source("AuthGate.kt")

        /** Resolves a session source whether the test runs from the module directory or the repository root. */
        fun source(name: String): File {
            val path = "src/main/kotlin/dev/lain/claudejb/session/$name"
            return sequenceOf(File(path), File("../$path")).firstOrNull { it.isFile }
                ?: error("could not locate $path from ${File("").absolutePath}")
        }
    }
}
