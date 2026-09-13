package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.paths.CredentialPaths
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShellFileWritesWindowsTest : GuardProbe(
    SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
    ),
) {

    @Test
    fun `the Windows write verbs are file writes with no diff`() {
        listOf(
            "Set-Content C:/x/hosts 'a'",
            """copy payload.exe C:\other\p.exe""",
            """move a.txt C:\other\a.txt""",
            """del C:\other\x.log""",
            """Out-File -FilePath C:\other\o.txt""",
            """Add-Content C:\other\log.txt 'x'""",
            """New-Item C:\other\n.txt""",
        ).forEach { cmd ->
            assertEquals(Verdict.DENY, v(bash(cmd)), cmd)
            assertEquals(SecurityRule.SHELL_FILE_WRITE, rule(bash(cmd)), cmd)
        }
    }

    @Test
    fun `the Windows null sinks are benign redirect targets`() {
        listOf("Get-Process > \$null", "dir > NUL", "Get-ChildItem | Out-Null").forEach { cmd ->
            assertEquals(Verdict.ALLOW, v(bash(cmd)), why(bash(cmd)))
        }
    }

    @Test
    fun `a write inside the project is not a shell-write card`() {
        assertEquals(Verdict.ALLOW, v(bash("""Set-Content C:\proj\a.txt 'x'""")))
    }
}
