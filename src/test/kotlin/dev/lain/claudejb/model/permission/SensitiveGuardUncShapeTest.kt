package dev.lain.claudejb.model.permission

import dev.lain.claudejb.model.permission.GuardFixture.HOME
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.paths.ForeignTerritory
import dev.lain.claudejb.model.permission.paths.GuardPaths
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitiveGuardUncShapeTest :
    GuardProbe(GuardFixture.basePolicy().copy(guardedRoots = GuardFixture.GUARDED_ROOTS, wslHost = false)) {

    @Test
    fun `a regex literal in a command is not mistaken for a network share`() {
        assertEquals(Verdict.DENY, v(bash("""rg --pcre2 '/\btype\s*:\s*/' src/""")))
        assertEquals(Verdict.DENY, v(bash("""node -e 'console.log(/\bexport\b/.test(s))'""")))
    }

    @Test
    fun `no regex literal reaches the rules wearing a UNC prefix`() {
        for (literal in listOf(
            """/\btype\s*:\s*/""",
            """/\bfoo\b/""",
            """/\bTODO\b/""",
            """/\d\.\d/""",
            """/\d+/""",
            """/\s*foo\s*/""",
            """/\w+\.txt/""",
        )) {
            assertFalse(GuardPaths.normalize(literal, HOME).startsWith("//"), literal)
            assertFalse(ForeignTerritory.isUnc(GuardPaths.normalize(literal, HOME)), literal)
            assertEquals(Verdict.ALLOW, v(buildJsonObject { put("pattern", literal) }), literal)
            assertEquals(Verdict.DENY, v(bash("rg --pcre2 $literal src/")), literal)
        }
    }

    @Test
    fun `ordinary source text that canonicalisation can path-shape stays allowed`() {
        listOf(
            """grep -P '\btype\s*:' src/""",
            """python3 -c 'print("a\tb\nc")'""",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
        assertEquals(Verdict.DENY, v(bash("""rg '// TODO: drop this' src/""")))
        assertEquals(Verdict.ALLOW, v(bash("""echo 'C:\\Users\\me\\app'""")))
        assertEquals(Verdict.ALLOW, v(bash("""sed -i 's/\bfoo\b/bar/g' src/App.kt""")))
        assertEquals(SecurityRule.SHELL_FILE_WRITE, rule(bash("""sed -i 's/\bfoo\b/bar/g' /etc/fstab""")))
    }

    @Test
    fun `every real UNC spelling is still foreign territory`() {
        assertEquals(Verdict.DENY, v(read("""\\server\share\file""")))
        assertEquals(Verdict.DENY, v(read("//server/share/file")))
        assertEquals(Verdict.DENY, v(read("""\\?\UNC\server\share\x""")))
        assertEquals(Verdict.DENY, v(read("""\\.\pipe\x""")))
        assertEquals(Verdict.DENY, v(bash("""cp \\fileserver\backup\dump.sql .""")))
        assertEquals(Verdict.DENY, v(bash("cp //fileserver/backup/dump.sql .")))
        assertTrue(GuardPaths.normalize("""\\server\share\file""", HOME).startsWith("//"))
        assertTrue(GuardPaths.normalize("//server/share/file", HOME).startsWith("//"))
    }

    @Test
    fun `a sensitive path dressed as a regex literal is still caught`() {
        assertEquals(Verdict.DENY, v(read("""/\home/bob/.ssh/id_rsa""")))
        assertEquals(Verdict.DENY, v(bash("""cat /\home/bob/.bashrc""")))
        assertEquals(Verdict.DENY, v(read("""C:\Users\bob\Desktop\notes.txt""")))
        assertEquals(Verdict.DENY, v(read("""/\home/me/.ssh/id_rsa""")))
        assertEquals(SecurityRule.CREDENTIALS, rule(read("""/\home/me/.ssh/id_rsa""")))
    }

    @Test
    fun `wrapping a share in regex delimiters reaches no share`() {
        assertFalse(ForeignTerritory.isUnc("""\\\server\share"""))
        assertEquals(Verdict.DENY, v(bash("""rg '/\\server\share/' src/""")))
        assertEquals(Verdict.DENY, v(bash("""cp \\server\share\x .""")))
    }

    @Test
    fun `the UNC prefix is read after variable expansion, never off the raw argument`() {
        val uncHome = """\\nas\users\me"""
        assertEquals("//nas/users/me/.ssh/id_rsa", GuardPaths.normalize("~/.ssh/id_rsa", uncHome))
        assertEquals("//nas/users/me/x", GuardPaths.normalize("\$HOME/x", uncHome))
        assertEquals("//nas/users/me", GuardPaths.normalize("%USERPROFILE%", uncHome))
        assertEquals("/btype/s*:/s*", GuardPaths.normalize("""/\btype\s*:\s*""", HOME))
        assertEquals("//server/share/x", GuardPaths.normalize("""\\server\share\x""", HOME))
    }
}
