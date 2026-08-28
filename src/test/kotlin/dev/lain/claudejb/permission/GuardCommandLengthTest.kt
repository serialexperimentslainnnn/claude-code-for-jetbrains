package dev.lain.claudejb.permission

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

class GuardCommandLengthTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun repeatTo(unit: String, length: Int): String {
        val builder = StringBuilder(length + unit.length)
        while (builder.length < length) builder.append(unit)
        return builder.substring(0, length)
    }

    private val budgetMs = 5_000L

    private val pathological: List<Pair<String, String>> = listOf(
        "orchestration anchor repeated" to "kubectl " + repeatTo("delete ", 64 * 1024),
        "iac anchor repeated" to "terraform " + repeatTo("apply -x ", 64 * 1024),
        "cloud anchor repeated" to "aws s3 " + repeatTo("rm ", 64 * 1024),
        "container anchor repeated" to "docker " + repeatTo("volume ", 64 * 1024),
        "pipe-into-shell prefix" to "curl " + repeatTo("a", 64 * 1024),
        "credential anchor repeated" to "cat " + repeatTo("id_rsa ", 64 * 1024),
        "plain blob" to repeatTo("a", 64 * 1024),
    )

    @Test
    fun `a pathological command cannot stall the thread that reads the binary's stdout`() {
        SensitiveGuard.evaluate(bash("kubectl delete namespace prod"), policy)
        for ((label, command) in pathological) {
            val elapsed = measureTimeMillis { SensitiveGuard.evaluate(bash(command), policy) }
            assertTrue(elapsed < budgetMs, "$label took ${elapsed}ms, over the ${budgetMs}ms budget")
        }
    }

    @Test
    fun `padding in front of a destructive command does not hide it`() {
        val padded = repeatTo("echo hello ", 64 * 1024) + "; kubectl delete namespace prod"
        val decision = SensitiveGuard.evaluate(bash(padded), policy)
        assertEquals(SecurityRule.DESTRUCTIVE_ORCHESTRATION, decision.rule)
        assertEquals(SensitiveGuard.Verdict.DENY, decision.verdict)
    }

    @Test
    fun `padding behind a destructive command does not hide it`() {
        val padded = "kubectl delete namespace prod; " + repeatTo("echo hello ", 64 * 1024)
        val decision = SensitiveGuard.evaluate(bash(padded), policy)
        assertEquals(SecurityRule.DESTRUCTIVE_ORCHESTRATION, decision.rule)
        assertEquals(SensitiveGuard.Verdict.DENY, decision.verdict)
    }

    @Test
    fun `an ordinary long command is still judged whole`() {
        val message = repeatTo("word ", 2_000)
        val decision = SensitiveGuard.evaluate(bash("git commit -m '$message' && rm -rf /etc"), policy)
        assertEquals(SecurityRule.DESTRUCTIVE_FILESYSTEM, decision.rule)
    }
}
