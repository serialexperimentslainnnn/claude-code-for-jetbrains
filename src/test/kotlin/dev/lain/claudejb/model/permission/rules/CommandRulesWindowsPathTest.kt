package dev.lain.claudejb.model.permission.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandRulesWindowsPathTest {

    private val home = """C:\Users\me"""

    @Test
    fun `a drive-rooted path keeps its segments through de-obfuscation`() {
        assertEquals("type C:/proj/a.txt", CommandRules.deobfuscate("""type C:\proj\a.txt"""))
    }

    @Test
    fun `a quoted assignment of a drive-rooted path is not torn into one word`() {
        val peeled = CommandRules.deobfuscate("""set "PATH=C:\build\bin;%PATH%" & git status""")
        assertTrue(peeled.contains("C:/build/bin"), peeled)
        assertFalse(peeled.contains("C:buildbin"), peeled)
    }

    @Test
    fun `a Windows environment prefix and a tilde keep their segments too`() {
        assertTrue(CommandRules.deobfuscate("""copy %APPDATA%\x .""", home).contains("C:/Users/me/AppData/Roaming/x"))
        assertTrue(CommandRules.deobfuscate("""Get-Content ~\notes.txt""", home).contains("~/notes.txt"))
    }

    @Test
    fun `a POSIX backslash escape is still peeled`() {
        assertEquals("cat ~/.ssh/id_rsa", CommandRules.deobfuscate("""c\at ~/.ssh/id_rsa"""))
    }
}
