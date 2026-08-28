package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivilegeEscalationTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        guardedRoots = emptyList(),
        wslHost = false,
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun v(cmd: String) = SensitiveGuard.evaluate(bash(cmd), policy).verdict

    private fun rule(cmd: String) = SensitiveGuard.evaluate(bash(cmd), policy).rule

    @Test
    fun `every ordinary way of becoming root is refused`() {
        listOf(
            "sudo apt update",
            "sudoedit /etc/hosts",
            "su - root",
            "doas pkg upgrade",
            "pkexec /usr/bin/id",
            "runuser -u root -- ls",
            "setpriv --reuid=0 id",
            "run0 systemctl restart nginx",
        ).forEach { assertEquals(Verdict.DENY, v(it), it) }
    }

    @Test
    fun `it is refused wherever in the line it sits, and through a path or a wrapper`() {
        listOf(
            "echo hi && sudo ls",
            "echo hi; sudo ls",
            "true | sudo tee /etc/motd",
            "/usr/bin/sudo ls",
            "if true; then sudo ls; fi",
        ).forEach { assertEquals(Verdict.DENY, v(it), it) }
    }

    @Test
    fun `the macOS and Windows equivalents are the same rule`() {
        listOf(
            """osascript -e 'do shell script "ls" with administrator privileges'""",
            "runas /user:Administrator cmd.exe",
            "Start-Process powershell -Verb RunAs",
            "psexec -s cmd.exe",
            "wsl -u root ls",
        ).forEach { assertEquals(Verdict.DENY, v(it), it) }
    }

    @Test
    fun `the rule is named, so a whitelist entry can be filed against it`() {
        assertEquals(SecurityRule.PRIVILEGE_ESCALATION, rule("sudo apt update"))
        assertEquals(SecurityCategory.SYSTEM_INTEGRITY, SecurityRule.PRIVILEGE_ESCALATION.category)
        assertEquals(true, SecurityRule.PRIVILEGE_ESCALATION.whitelistable)
    }

    @Test
    fun `naming it is not running it`() {
        listOf(
            "git commit -m 'drop sudo from the install notes'",
            "grep -rn sudo docs/",
            "cat notes-on-sudo.md",
            "npm run superbuild",
            "git status",
        ).forEach { assertEquals(Verdict.ALLOW, v(it), it) }
    }

    @Test
    fun `it is a rule about running, never about text that mentions running`() {
        val read = buildJsonObject { put("file_path", "/home/me/proj/INSTALL.md") }
        val write = buildJsonObject {
            put("file_path", "/home/me/proj/INSTALL.md")
            put("content", "Run sudo apt update before building, then doas pkg upgrade on BSD.")
        }
        val edit = buildJsonObject {
            put("file_path", "/home/me/proj/README.md")
            put("old_string", "sudo make install")
            put("new_string", "make install")
        }
        val search = buildJsonObject { put("pattern", "sudo|doas|pkexec") }

        listOf(read, write, edit, search).forEach {
            assertEquals(Verdict.ALLOW, SensitiveGuard.evaluate(it, policy).verdict, it.toString())
        }
    }

    @Test
    fun `a more specific family still gets to describe the call`() {
        assertEquals(SecurityRule.HACKING_TOOL, rule("sudo nmap -sV 10.0.0.0/24"))
    }
}
