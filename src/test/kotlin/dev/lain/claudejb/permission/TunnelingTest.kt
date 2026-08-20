package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TunnelingTest {

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
    fun `tunnels and anonymisers are refused`() {
        listOf(
            "ssh -R 8080:localhost:80 user@host",
            "ssh -D 1080 user@host",
            "ssh -NL 5432:db:5432 user@host",
            "ngrok http 3000",
            "cloudflared tunnel run mytunnel",
            "frpc -c frpc.toml",
            "localtunnel --port 8000",
            "lt --port 8000",
            "iodine -f -P secret t.example.com",
            "dnscat2 example.com",
            "proxychains curl https://x",
            "tor",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.TUNNELING, rule(bash(it)), it)
        }
    }

    @Test
    fun `ordinary ssh and unrelated commands are not touched`() {
        listOf(
            "ssh -l deploy host uptime",
            "ssh user@host uptime",
            "ssh -i key.pem user@host",
            "git push origin main",
            "npm run build",
            "history 20",
        ).forEach { assertNotEquals(SecurityRule.TUNNELING, rule(bash(it)), it) }
    }
}
