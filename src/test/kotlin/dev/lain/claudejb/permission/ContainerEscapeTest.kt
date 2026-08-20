package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ContainerEscapeTest {

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
    fun `escaping onto the host is refused`() {
        listOf(
            "nsenter -t 1 -m -u -i -n -p bash",
            "nsenter --target 1 --mount --pid -- bash",
            "nsenter --mount=/proc/1/ns/mnt -- /bin/bash",
            "docker run -v /:/host alpine",
            "docker run --volume=/:/mnt ubuntu",
            "podman run --mount type=bind,source=/,target=/host img",
            "docker run --privileged alpine",
            "podman run --rm --privileged img",
            "docker run -v /var/run/docker.sock:/var/run/docker.sock img",
            "kubectl run x --overrides '{\"spec\":{\"hostPID\":true}}'",
            "kubectl run p --overrides '{\"spec\":{\"securityContext\":{\"privileged\":true}}}'",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.CONTAINER_ESCAPE, rule(bash(it)), it)
        }
    }

    @Test
    fun `ordinary container and namespace use is not touched`() {
        listOf(
            "docker run -v /home/me/proj:/app node npm test",
            "docker run -v /var/run/postgres:/data postgres",
            "nsenter -t 4321 -n ip addr",
            "docker build -t app .",
            "docker compose up -d",
            "kubectl get pods -n prod",
        ).forEach { assertNotEquals(SecurityRule.CONTAINER_ESCAPE, rule(bash(it)), it) }
    }
}
