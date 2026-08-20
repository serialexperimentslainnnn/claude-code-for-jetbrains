package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DisableDefencesTest {

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
    fun `turning off a security defence is refused`() {
        listOf(
            "setenforce 0",
            "systemctl stop auditd",
            "systemctl disable firewalld",
            "service apparmor stop",
            "ufw disable",
            "iptables -F",
            "nft flush ruleset",
            "auditctl -e 0",
            "spctl --master-disable",
            "csrutil disable",
            "Set-MpPreference -DisableRealtimeMonitoring \$true",
            "Add-MpPreference -ExclusionPath C:\\payload",
            "netsh advfirewall set allprofiles state off",
            "auditpol /clear /y",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.DISABLE_DEFENCES, rule(bash(it)), it)
        }
    }

    @Test
    fun `ordinary service and firewall inspection is not touched`() {
        listOf(
            "systemctl stop myapp",
            "systemctl status firewalld",
            "service nginx restart",
            "iptables -L -n",
            "ufw status",
            "spctl --status",
            "Set-MpPreference -MAPSReporting Advanced",
        ).forEach { assertNotEquals(SecurityRule.DISABLE_DEFENCES, rule(bash(it)), it) }
    }
}
