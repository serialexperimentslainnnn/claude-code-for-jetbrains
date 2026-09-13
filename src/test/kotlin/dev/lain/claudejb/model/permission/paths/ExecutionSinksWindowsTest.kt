package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionSinksWindowsTest : GuardProbe(
    SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
    ),
) {

    private fun write(path: String, content: String): JsonObject = buildJsonObject {
        put("file_path", path)
        put("content", content)
    }

    @Test
    fun `a file written into the Startup folder is judged by what it will run`() {
        val input = write(
            """%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\x.bat""",
            "gpg --export-secret-keys --armor",
        )
        assertEquals(Verdict.DENY, v(input))
        assertEquals(SecurityRule.SECRET_DUMPING_COMMANDS, rule(input))
        assertTrue(why(input).contains("runs when it is used"), why(input))
    }

    @Test
    fun `a PowerShell profile is judged by what it will run, wherever it sits`() {
        val input = write("""C:\proj\scripts\Microsoft.PowerShell_profile.ps1""", "gpg --export-secret-keys --armor")
        assertEquals(Verdict.DENY, v(input))
        assertEquals(SecurityRule.SECRET_DUMPING_COMMANDS, rule(input))
    }

    @Test
    fun `a clean profile inside the project is ordinary work`() {
        assertEquals(Verdict.ALLOW, v(write("""C:\proj\scripts\profile.ps1""", "Set-Alias ll Get-ChildItem")))
    }
}
