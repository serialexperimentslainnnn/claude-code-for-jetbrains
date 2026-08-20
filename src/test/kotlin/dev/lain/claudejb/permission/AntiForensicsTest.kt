package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AntiForensicsTest {

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = "/home/me",
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }

    private fun read(path: String) = buildJsonObject { put("file_path", path) }

    private fun v(input: kotlinx.serialization.json.JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    private fun rule(input: kotlinx.serialization.json.JsonObject) = SensitiveGuard.evaluate(input, policy).rule

    @Test
    fun `clearing the trail is refused as anti-forensic`() {
        listOf(
            "history -c",
            "unset HISTFILE",
            "set +o history",
            "HISTFILE=/dev/null bash",
            "journalctl --vacuum-time=1s",
            "journalctl --vacuum-size=1M",
            "Clear-History",
            "Set-PSReadlineOption -HistorySaveStyle SaveNothing",
            "wevtutil cl System",
            "Clear-EventLog -LogName Security",
            "touch -t 197001010000 a.txt",
            "touch -r ref.txt target.txt",
            "touch -acmr ref.txt target.txt",
            "touch -d 2020-01-01 a.txt",
            "SetFile -m 01/01/2020 a.txt",
        ).forEach {
            assertEquals(Verdict.DENY, v(bash(it)), it)
            assertEquals(SecurityRule.ANTI_FORENSIC, rule(bash(it)), it)
        }
    }

    @Test
    fun `it fires after a separator, not only at the very start`() {
        val chained = "make build; history -c"
        assertEquals(Verdict.DENY, v(bash(chained)), chained)
        assertEquals(SecurityRule.ANTI_FORENSIC, rule(bash(chained)))
    }

    @Test
    fun `ordinary history and log inspection is not touched`() {
        listOf(
            "history",
            "history 20",
            "journalctl -u myapp -n 100",
            "journalctl --since today",
            "set -o pipefail",
            "Set-PSReadlineOption -EditMode Emacs",
            "cat ~/.bash_history",
            "touch newfile.txt",
            "touch -c existing.txt",
        ).forEach { assertNotEquals(SecurityRule.ANTI_FORENSIC, rule(bash(it)), it) }
    }

    @Test
    fun `a commit message that merely mentions the technique is not a match`() {
        val commit = "git commit -m \"document how to run history -c to clean up\""
        assertNotEquals(SecurityRule.ANTI_FORENSIC, rule(bash(commit)), commit)
    }

    @Test
    fun `reading a file that documents the technique is not a match`() {
        assertNotEquals(SecurityRule.ANTI_FORENSIC, rule(read("/home/me/proj/notes/history -c.md")))
    }
}
