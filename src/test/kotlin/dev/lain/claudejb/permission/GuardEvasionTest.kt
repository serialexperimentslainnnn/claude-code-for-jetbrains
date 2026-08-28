package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class GuardEvasionTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun v(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    private fun rule(input: JsonObject) = SensitiveGuard.evaluate(input, policy).rule

    private fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    private fun hex(s: String): String = s.toByteArray().joinToString("") { "%02x".format(it) }

    @Test
    fun `quoting and escaping do not hide a disguised payload`() {
        listOf(
            "c'a't /etc/shadow",
            "c\"a\"t /etc/shadow",
            "ca\\t /etc/shadow",
            "cat\$IFS/etc/shadow",
            "a=cat; \$a /etc/shadow",
            "\$'\\x63\\x61\\x74' /etc/shadow",
            "{cat,/etc/shadow}",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it)), it) }
    }

    @Test
    fun `a base64 payload is decoded before it is judged`() {
        val payload = b64("cat /etc/shadow")
        val cmd = "echo $payload | base64 -d | sh"
        assertEquals(Verdict.DENY, v(bash(cmd)), cmd)
        assertEquals(SecurityRule.CREDENTIALS, rule(bash(cmd)), cmd)
    }

    @Test
    fun `a doubly encoded base64 payload is decoded through both layers`() {
        val payload = b64(b64("cat /etc/shadow"))
        val cmd = "echo $payload | base64 -d | base64 -d | sh"
        assertEquals(Verdict.DENY, v(bash(cmd)), cmd)
        assertEquals(SecurityRule.CREDENTIALS, rule(bash(cmd)), cmd)
    }

    @Test
    fun `a hex payload is decoded before it is judged`() {
        val payload = hex("cat /etc/shadow")
        val cmd = "echo $payload | xxd -r -p | sh"
        assertEquals(Verdict.DENY, v(bash(cmd)), cmd)
        assertEquals(SecurityRule.CREDENTIALS, rule(bash(cmd)), cmd)
    }

    @Test
    fun `a reversed payload is read back before it is judged`() {
        val cmd = "echo '${"cat /etc/shadow".reversed()}' | rev | sh"
        assertEquals(Verdict.DENY, v(bash(cmd)), cmd)
        assertEquals(SecurityRule.CREDENTIALS, rule(bash(cmd)), cmd)
    }

    @Test
    fun `ordinary long tokens are not mistaken for an encoded payload`() {
        listOf(
            "git checkout 5f2e9c1a4b7d3e8f0a6c2b9d1e4f7a3c5b8d0e2f",
            "curl -H 'Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345' https://api.example.com/v1/ping",
            "docker pull app@sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "npm test -- --grep deadbeefdeadbeefdeadbeef",
        ).forEach { assertNotEquals(Verdict.DENY, v(bash(it)), it) }
    }

    @Test
    fun `decoding does not make an ordinary build command dangerous`() {
        listOf(
            "npm run build",
            "./gradlew check",
            "git log --oneline | rev",
        ).forEach { assertNotEquals(Verdict.DENY, v(bash(it)), it) }
    }

    @Test
    fun `a long padded command is still judged in reasonable time`() {
        val padded = "cat /etc/shadow ; " + "a".repeat(64 * 1024)
        val started = System.nanoTime()
        val verdict = v(bash(padded))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(Verdict.DENY, verdict)
        assert(elapsedMs < 5_000) { "evaluate took ${elapsedMs}ms" }
    }
}
