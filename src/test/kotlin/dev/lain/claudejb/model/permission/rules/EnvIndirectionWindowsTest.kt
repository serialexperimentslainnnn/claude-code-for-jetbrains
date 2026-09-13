package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.paths.CredentialPaths
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnvIndirectionWindowsTest : GuardProbe(
    SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\build""",
        caseInsensitivePaths = true,
    ),
) {

    @Test
    fun `cmd set binds its variable, so a later reference is not an unresolved destination`() {
        assertEquals(Verdict.ALLOW, v(bash("""set "X=C:/build" & copy a %X%""")))
    }

    @Test
    fun `a variable nothing here can resolve still cards`() {
        assertEquals(Verdict.DENY, v(bash("""cat %NOWHERE%\x""")))
        assertEquals(SecurityRule.UNRESOLVED_VARIABLE, rule(bash("""cat %NOWHERE%\x""")))
        assertEquals(Verdict.DENY, v(bash("cat \$CREDS")))
        assertEquals(SecurityRule.UNRESOLVED_VARIABLE, rule(bash("cat \$CREDS")))
    }
}
