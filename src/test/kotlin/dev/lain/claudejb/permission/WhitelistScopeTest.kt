package dev.lain.claudejb.permission

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WhitelistScopeTest {

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun policy(
        global: List<String> = emptyList(),
        byCategory: Map<SecurityCategory, Set<String>> = emptyMap(),
        byRule: Map<SecurityRule, Set<String>> = emptyMap(),
    ) = SensitiveGuard.Policy(
        home = "/home/tester",
        currentUser = "tester",
        commandWhitelist = global,
        categoryWhitelist = byCategory,
        ruleWhitelist = byRule,
    )

    @Test
    fun `a rule entry lifts its own rule`() {
        val decision = SensitiveGuard.evaluate(
            bash("terraform destroy"),
            policy(byRule = mapOf(SecurityRule.DESTRUCTIVE_IAC to setOf("terraform destroy"))),
        )

        assertEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict)
    }

    @Test
    fun `a rule entry lifts nothing else`() {
        val decision = SensitiveGuard.evaluate(
            bash("terraform destroy"),
            policy(byRule = mapOf(SecurityRule.DESTRUCTIVE_CLOUD to setOf("terraform destroy"))),
        )

        assertNotEquals(
            SensitiveGuard.Verdict.ALLOW,
            decision.verdict,
            "an entry filed under one rule must not answer for another",
        )
    }

    @Test
    fun `a category entry lifts every rule of that category`() {
        val decision = SensitiveGuard.evaluate(
            bash("terraform destroy"),
            policy(byCategory = mapOf(SecurityCategory.DESTRUCTIVE_OPERATION to setOf("terraform destroy"))),
        )

        assertEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict)
    }

    @Test
    fun `a category entry lifts nothing outside its category`() {
        val decision = SensitiveGuard.evaluate(
            bash("terraform destroy"),
            policy(byCategory = mapOf(SecurityCategory.NETWORK_EGRESS to setOf("terraform destroy"))),
        )

        assertNotEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict)
    }

    @Test
    fun `the global list lifts any rule`() {
        val decision = SensitiveGuard.evaluate(
            bash("terraform destroy"),
            policy(global = listOf("terraform destroy")),
        )

        assertEquals(SensitiveGuard.Verdict.ALLOW, decision.verdict)
    }

    @Test
    fun `matching is de-obfuscated on both sides`() {
        val decision = SensitiveGuard.evaluate(
            bash("""t""" + "\"\"" + """erraform  destroy"""),
            policy(byRule = mapOf(SecurityRule.DESTRUCTIVE_IAC to setOf("terraform destroy"))),
        )

        assertEquals(
            SensitiveGuard.Verdict.ALLOW,
            decision.verdict,
            "an entry written normally must cover the same command spelled to evade it",
        )
    }

    @Test
    fun `an entry does not stretch to a command that merely starts the same way`() {
        val decision = SensitiveGuard.evaluate(
            bash("terraform destroy && rm -rf /"),
            policy(byRule = mapOf(SecurityRule.DESTRUCTIVE_IAC to setOf("terraform destroy"))),
        )

        assertNotEquals(
            SensitiveGuard.Verdict.ALLOW,
            decision.verdict,
            "authorising one command is not authorising a line that contains it",
        )
    }

    private fun firedRules(): Map<String, SecurityRule?> =
        TRIPWIRE.associateWith { SensitiveGuard.evaluate(bash(it), policy()).rule }

    @Test
    fun `every command in this table really is blocked by something`() {
        assertEquals(
            emptyList<String>(),
            firedRules().filterValues { it == null }.keys.toList(),
            "a fixture the guard shrugs at turns the test below into a green nothing",
        )
    }

    @Test
    fun `every rule these commands can reach can be whitelisted`() {
        val unliftable = firedRules().mapNotNull { (command, rule) ->
            if (rule == null) return@mapNotNull null
            val lifted = SensitiveGuard.evaluate(bash(command), policy(byRule = mapOf(rule to setOf(command))))
            if (lifted.verdict == SensitiveGuard.Verdict.ALLOW) null else "$rule via '$command'"
        }

        assertEquals(
            emptyList<String>(),
            unliftable,
            "a rule the user cannot get past is a rule that stops work they asked for",
        )
    }

    @Test
    fun `the table reaches the rules it claims to`() {
        assertEquals(
            COVERED,
            firedRules().values.filterNotNull().toSortedSet(),
            "the fixtures drifted: a rule silently dropped out of this table is a rule nobody checks",
        )
    }

    private companion object {
        val TRIPWIRE = listOf(
            "cat /home/tester/.ssh/id_rsa",
            "aws configure get aws_secret_access_key",
            "git add -f notes.txt",
            "cat /tmp/staged.tar",
            "rm notes.txt",
            "cat /home/someone-else/.bashrc",
            "cat /dev/sda",
            "terraform destroy",
            "kubectl delete namespace prod",
            "aws ec2 terminate-instances --instance-ids i-1",
            "psql -c 'DROP DATABASE prod'",
            "docker system prune",
            "git push --force",
            "rm -rf /var/lib/data",
            "npm install left-pad",
            "git config core.hooksPath /tmp/h",
            "LD_PRELOAD=/tmp/x.so ls",
            "nmap -sS 10.0.0.1",
            "find . -exec /bin/sh \\;",
        )

        val COVERED = sortedSetOf(
            SecurityRule.CREDENTIALS,
            SecurityRule.SECRET_DUMPING_COMMANDS,
            SecurityRule.VCS_PROTECTION_BYPASS,
            SecurityRule.TEMP_DIR,
            SecurityRule.SHELL_FILE_WRITE,
            SecurityRule.OTHER_USER_HOME,
            SecurityRule.SYSTEM_DEVICE,
            SecurityRule.DESTRUCTIVE_IAC,
            SecurityRule.DESTRUCTIVE_ORCHESTRATION,
            SecurityRule.DESTRUCTIVE_CLOUD,
            SecurityRule.DESTRUCTIVE_DATABASE,
            SecurityRule.DESTRUCTIVE_CONTAINER,
            SecurityRule.DESTRUCTIVE_GIT,
            SecurityRule.DESTRUCTIVE_FILESYSTEM,
            SecurityRule.PACKAGE_INSTALL_HOOK,
            SecurityRule.PERSISTENCE_MECHANISM,
            SecurityRule.CODE_INJECTION,
            SecurityRule.HACKING_TOOL,
            SecurityRule.PRIVESC_EXEC,
        )
    }
}
