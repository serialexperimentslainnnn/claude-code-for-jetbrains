package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRulesCaretTest : GuardProbe() {

    @Test
    fun `a cmd caret split inside a keyword is read as the keyword`() {
        val cmd = bash("p^owershell -e^nc QUFBQUFBQUFBQUFB")
        assertEquals(Verdict.DENY, v(cmd))
        assertEquals(SecurityRule.SECRET_DUMPING_COMMANDS, rule(cmd))
        assertTrue(CommandRules.deobfuscate("cat^ /etc/shadow").contains("cat /etc/shadow"))
    }

    @Test
    fun `an ordinary caret in git or a regex does not create a refusal`() {
        assertEquals(Verdict.ALLOW, v(bash("git log HEAD^ --oneline")))
        assertEquals(Verdict.ALLOW, v(bash("grep '^import' src/App.kt")))
    }
}
