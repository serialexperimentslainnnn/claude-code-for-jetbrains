package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AntiForensicsTest : GuardProbe() {

    @Test
    fun `clearing the trail is refused as anti-forensic`() {
        listOf(
            "history -c",
            "unset HISTFILE",
            "set +o history",
            "HISTFILE=/dev/null bash",
            "export HISTFILESIZE=0",
            "HISTSIZE=0",
            "journalctl --vacuum-time=1s",
            "journalctl --vacuum-size=1M",
            "Clear-History",
            "Set-PSReadlineOption -HistorySaveStyle SaveNothing",
            "Set-PSReadlineOption -AddToHistoryHandler { return \$false }",
            "Remove-Item (Get-PSReadlineOption).HistorySavePath",
            "truncate --size=0 /var/log/auth.log",
            "cat /dev/null > /var/log/wtmp",
            "echo > /var/log/auth.log",
            ": > /var/log/btmp",
            "wevtutil cl System",
            "Clear-EventLog -LogName Security",
            "Remove-EventLog -LogName Security",
            "fsutil usn deletejournal /d C:",
            "log erase --all",
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
    fun `emptying the shell history file is blocked`() {
        listOf(
            "cat /dev/null > ~/.bash_history",
            ": > ~/.zsh_history",
            "echo -n > /home/me/.bash_history",
            "ln -sf /dev/null ~/.bash_history",
            "cp /dev/null ~/.zsh_history",
            "truncate -s0 ~/.bash_history",
        ).forEach { assertEquals(Verdict.DENY, v(bash(it)), it) }
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
            "history -a",
            "journalctl -u myapp -n 100",
            "journalctl --since today",
            "set -o pipefail",
            "Set-PSReadlineOption -EditMode Emacs",
            "cat ~/.bash_history",
            "cat /var/log/auth.log",
            "git log --oneline",
            "truncate -s 100M disk.img",
            "echo build done > /var/log/myapp.log",
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
