package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which spellings the guard is willing to read as a **network share**, and which it must not — the third
 * incident in one family, kept in a class of its own because it is one subject and [SensitiveGuardTest] is at
 * detekt's size ceiling.
 *
 * ### The defect
 * A JavaScript or PCRE regex literal opens with `/` and its first atom is almost always an escape, so a
 * backslash sits immediately after a separator. [GuardPaths.normalize] used to decide whether a value carried
 * a UNC prefix **after** translating every backslash into a slash, which cannot tell `\\host\share` from that
 * shape — so the literal `\btype\s*:\s*` written between its slash delimiters reached the rules as
 * `//btype/s*:/s*`. Its first segment is hostname-shaped and its second is a non-empty share, so it went
 * straight through both earlier hardenings of [ForeignTerritory.isUnc] (a host that looks like a host, and a
 * share that exists) and was classified FOREIGN — **DENY for every caller, no trust exemption, no override**,
 * on a search the user asked for, with the refusal pointing them at the switch that turns a security rule off.
 *
 * ### The fix, and the shape of its risk
 * The prefix is now taken from the separators the caller wrote, which is what Microsoft states it is: a name is
 * fully qualified when it begins with "A UNC name of any format, which always start with two backslash
 * characters" (*Naming Files, Paths, and Namespaces*). A separator next to a backslash is neither that nor its
 * accepted forward-slash mirror.
 *
 * The tests that matter here are therefore **not** the ones showing the regex no longer trips. They are the
 * ones showing that a value which really is a path cannot use the same disguise: every genuine UNC spelling is
 * still refused, and a sensitive path dressed as a regex literal is still refused — by the rule that fits it,
 * because the fix withdraws a manufactured prefix and never a candidate. A dropped candidate would be an ALLOW
 * from every rule at once, which is this package's other recurring trap.
 */
class SensitiveGuardUncShapeTest {

    private val home = "/home/me"
    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = home,
        currentUser = "me",
        guardedRoots = listOf("/mnt/share", "/net/nfs"),
        wslHost = false,
        projectRoot = "/home/me/proj",
    )

    private fun read(path: String) = buildJsonObject { put("file_path", path) }
    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }
    private fun v(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    @Test
    fun `a regex literal in a command is not mistaken for a network share`() {
        assertEquals(Verdict.ALLOW, v(bash("""rg --pcre2 '/\btype\s*:\s*/' src/""")))
        assertEquals(Verdict.ALLOW, v(bash("""node -e 'console.log(/\bexport\b/.test(s))'""")))
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
            assertFalse(GuardPaths.normalize(literal, home).startsWith("//"), literal)
            assertFalse(ForeignTerritory.isUnc(GuardPaths.normalize(literal, home)), literal)
            assertEquals(Verdict.ALLOW, v(buildJsonObject { put("pattern", literal) }), literal)
            assertEquals(Verdict.ALLOW, v(bash("rg --pcre2 $literal src/")), literal)
        }
    }

    @Test
    fun `ordinary source text that canonicalisation can path-shape stays allowed`() {
        listOf(
            """grep -P '\btype\s*:' src/""",
            """python3 -c 'print("a\tb\nc")'""",
            """echo 'C:\\Users\\me\\app'""",
            """rg '// TODO: drop this' src/""",
        ).forEach { assertEquals(Verdict.ALLOW, v(bash(it)), it) }
        assertEquals(Verdict.ALLOW, v(bash("""sed -i 's/\bfoo\b/bar/g' src/App.kt""")))
        assertEquals(
            SecurityRule.SHELL_FILE_WRITE,
            SensitiveGuard.evaluate(bash("""sed -i 's/\bfoo\b/bar/g' /etc/fstab"""), policy).rule,
        )
    }

    @Test
    fun `every real UNC spelling is still foreign territory`() {
        assertEquals(Verdict.DENY, v(read("""\\server\share\file""")))
        assertEquals(Verdict.DENY, v(read("//server/share/file")))
        assertEquals(Verdict.DENY, v(read("""\\?\UNC\server\share\x""")))
        assertEquals(Verdict.DENY, v(read("""\\.\pipe\x""")))
        assertEquals(Verdict.DENY, v(bash("""cp \\fileserver\backup\dump.sql .""")))
        assertEquals(Verdict.DENY, v(bash("cp //fileserver/backup/dump.sql .")))
        assertTrue(GuardPaths.normalize("""\\server\share\file""", home).startsWith("//"))
        assertTrue(GuardPaths.normalize("//server/share/file", home).startsWith("//"))
    }

    @Test
    fun `a sensitive path dressed as a regex literal is still caught`() {
        assertEquals(Verdict.DENY, v(read("""/\home/bob/.ssh/id_rsa""")))
        assertEquals(Verdict.DENY, v(bash("""cat /\home/bob/.bashrc""")))
        assertEquals(Verdict.DENY, v(read("""C:\Users\bob\Desktop\notes.txt""")))
        assertEquals(Verdict.DENY, v(read("""/\home/me/.ssh/id_rsa""")))
        assertEquals(
            SecurityRule.CREDENTIALS,
            SensitiveGuard.evaluate(read("""/\home/me/.ssh/id_rsa"""), policy).rule,
        )
    }

    @Test
    fun `wrapping a share in regex delimiters reaches no share`() {
        assertFalse(ForeignTerritory.isUnc("""\\\server\share"""))
        assertEquals(Verdict.ALLOW, v(bash("""rg '/\\server\share/' src/""")))
        assertEquals(Verdict.DENY, v(bash("""cp \\server\share\x .""")))
    }

    @Test
    fun `the UNC prefix is read after variable expansion, never off the raw argument`() {
        val uncHome = """\\nas\users\me"""
        assertEquals("//nas/users/me/.ssh/id_rsa", GuardPaths.normalize("~/.ssh/id_rsa", uncHome))
        assertEquals("//nas/users/me/x", GuardPaths.normalize("\$HOME/x", uncHome))
        assertEquals("//nas/users/me", GuardPaths.normalize("%USERPROFILE%", uncHome))
        assertEquals("/btype/s*:/s*", GuardPaths.normalize("""/\btype\s*:\s*""", home))
        assertEquals("//server/share/x", GuardPaths.normalize("""\\server\share\x""", home))
    }
}
