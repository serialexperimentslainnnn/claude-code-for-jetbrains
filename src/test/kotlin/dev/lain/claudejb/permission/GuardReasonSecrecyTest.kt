package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardReasonSecrecyTest {

    private val token = "ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"

    private val apiKey = "sk-ant-api03-ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"

    private val env = mapOf(
        "GITHUB_TOKEN" to token,
        "ANTHROPIC_API_KEY" to apiKey,
        "AWS_SECRET_ACCESS_KEY" to "wJalrXUtnFEMI7K7MDENGbPxRfiCYEXAMPLEKEY",
        "LANG" to "C",
        "HOME" to "/home/me",
        "EDITOR" to "vim",
    )

    private val policy = SensitiveGuard.Policy(
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
        envValues = env,
    )

    private fun decide(input: kotlinx.serialization.json.JsonObject) = SensitiveGuard.evaluate(input, policy)

    private fun read(path: String) = buildJsonObject { put("file_path", path) }

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    @Test
    fun `a secret expanded into a refused path never comes back in the reason`() {
        val decision = decide(read("/etc/\$GITHUB_TOKEN"))

        assertEquals(Verdict.DENY, decision.verdict, "reaching outside the project is still refused")
        assertFalse(decision.reason.orEmpty().contains(token), "the denial goes back to the model: it cannot carry the token")
        assertFalse(decision.detail.orEmpty().contains(token), "the detail is stored in the alert log and the transcript")
    }

    @Test
    fun `every sensitive variable is covered, in any spelling that expands`() {
        listOf(
            "/etc/\$GITHUB_TOKEN" to token,
            "/etc/\${GITHUB_TOKEN}" to token,
            "/etc/\$ANTHROPIC_API_KEY" to apiKey,
            "/etc/\$AWS_SECRET_ACCESS_KEY" to env.getValue("AWS_SECRET_ACCESS_KEY"),
        ).forEach { (path, secret) ->
            val decision = decide(read(path))
            assertFalse(decision.reason.orEmpty().contains(secret), "leaked via $path")
            assertFalse(decision.detail.orEmpty().contains(secret), "leaked via $path (detail)")
        }
    }

    @Test
    fun `a secret named inside a command is not echoed either`() {
        val decision = decide(bash("cat /etc/\$GITHUB_TOKEN"))
        assertFalse(decision.reason.orEmpty().contains(token))
    }

    @Test
    fun `the reason still says what was wrong`() {
        val decision = decide(read("/etc/\$GITHUB_TOKEN"))
        val reason = decision.reason.orEmpty()
        assertTrue(reason.contains("outside the project"), reason)
        assertEquals(SecurityRule.OUTSIDE_PROJECT, decision.rule)
    }

    @Test
    fun `an ordinary variable is left readable — redaction is for secrets, not for noise`() {
        val decision = decide(read("/etc/\$LANG/x"))
        assertEquals(Verdict.DENY, decision.verdict)
        assertFalse(decision.reason.orEmpty().contains("REDACTED"), decision.reason.orEmpty())
    }

    @Test
    fun `a home-anchored path stays legible`() {
        val decision = decide(read("/home/me/other/notes.txt"))
        assertTrue(decision.reason.orEmpty().contains("/home/me/other"), decision.reason.orEmpty())
    }
}
