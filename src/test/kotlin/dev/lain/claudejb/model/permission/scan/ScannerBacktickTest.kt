package dev.lain.claudejb.model.permission.scan

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScannerBacktickTest : GuardProbe() {

    @Test
    fun `a lone backtick no longer swallows the path that follows it`() {
        val cmd = "Get-Content src/x `\n/home/bob/.ssh/id_rsa"
        assertEquals(Verdict.DENY, v(bash(cmd)))
        assertEquals(SecurityRule.OTHER_USER_HOME, rule(bash(cmd)))
    }

    @Test
    fun `a backtick command substitution is still read as the command it runs`() {
        assertEquals(Verdict.DENY, v(bash("echo `cat ~/.ssh/id_rsa`")))
    }
}
