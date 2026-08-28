package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ResourceHijackingTest {

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
    fun `mining binaries and the pool scheme are refused`() {
        listOf(
            "xmrig -o pool.example:3333 -u wallet",
            "./xmrig --coin monero",
            "/tmp/minerd -a scrypt",
            "ethminer -P stratum://x",
            "t-rex -a kawpow",
            "cpuminer -o pool:3333",
            "make build; xmrig -o pool:3333",
            "xmrig-proxy -o pool:3333",
            "cpuminer-opt -a scrypt",
            "srbminer-multi --algorithm randomx",
            "nanominer -c config.ini",
            "./worker -o stratum+tcp://pool.example:3333",
            "miner --url stratum+ssl://pool.example:443",
            "./worker -o stratum2+tcp://pool.example:3333",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.RESOURCE_HIJACKING, rule(bash(it)), it)
        }
    }

    @Test
    fun `ordinary development is not mistaken for mining`() {
        listOf(
            "npm run build",
            "docker build -t app .",
            "cargo build --release",
            "echo xmrig is a miner",
            "grep -r stratum src/",
            "grep -r cpuminer-opt docs/",
            "git commit -m \"add ethminer support notes\"",
        ).forEach { assertNotEquals(SecurityRule.RESOURCE_HIJACKING, rule(bash(it)), it) }
    }
}
