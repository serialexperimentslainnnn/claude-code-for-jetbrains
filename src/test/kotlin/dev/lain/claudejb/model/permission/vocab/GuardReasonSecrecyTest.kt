package dev.lain.claudejb.model.permission.vocab

import dev.lain.claudejb.model.permission.GuardFixture.HOME
import dev.lain.claudejb.model.permission.GuardFixture.PROJECT
import dev.lain.claudejb.model.permission.GuardFixture.USER
import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TOKEN = "ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"

private const val API_KEY = "sk-ant-api03-ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"

private val ENV = mapOf(
    "GITHUB_TOKEN" to TOKEN,
    "ANTHROPIC_API_KEY" to API_KEY,
    "AWS_SECRET_ACCESS_KEY" to "wJalrXUtnFEMI7K7MDENGbPxRfiCYEXAMPLEKEY",
    "LANG" to "C",
    "HOME" to "/home/me",
    "EDITOR" to "vim",
)

class GuardReasonSecrecyTest :
    GuardProbe(SensitiveGuard.Policy(home = HOME, currentUser = USER, projectRoot = PROJECT, envValues = ENV)) {

    private fun decide(input: JsonObject) = SensitiveGuard.evaluate(input, policy)

    @Test
    fun `a secret expanded into a refused path never comes back in the reason`() {
        val decision = decide(read("/etc/\$GITHUB_TOKEN"))

        assertEquals(Verdict.DENY, decision.verdict, "reaching outside the project is still refused")
        assertFalse(decision.reason.orEmpty().contains(TOKEN), "the denial goes back to the model: it cannot carry the token")
        assertFalse(decision.detail.orEmpty().contains(TOKEN), "the detail is stored in the alert log and the transcript")
    }

    @Test
    fun `every sensitive variable is covered, in any spelling that expands`() {
        listOf(
            "/etc/\$GITHUB_TOKEN" to TOKEN,
            "/etc/\${GITHUB_TOKEN}" to TOKEN,
            "/etc/\$ANTHROPIC_API_KEY" to API_KEY,
            "/etc/\$AWS_SECRET_ACCESS_KEY" to ENV.getValue("AWS_SECRET_ACCESS_KEY"),
        ).forEach { (path, secret) ->
            val decision = decide(read(path))
            assertFalse(decision.reason.orEmpty().contains(secret), "leaked via $path")
            assertFalse(decision.detail.orEmpty().contains(secret), "leaked via $path (detail)")
        }
    }

    @Test
    fun `a secret named inside a command is not echoed either`() {
        val decision = decide(bash("cat /etc/\$GITHUB_TOKEN"))
        assertFalse(decision.reason.orEmpty().contains(TOKEN))
    }

    @Test
    fun `the reason still says what was wrong`() {
        val decision = decide(read("/etc/\$GITHUB_TOKEN"))
        val reason = decision.reason.orEmpty()
        assertTrue(reason.contains(GuardReasonWords.OUTSIDE), reason)
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
