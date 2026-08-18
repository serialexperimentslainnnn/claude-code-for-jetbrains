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
        blockForeignWslMounts = false,
        projectRoot = "/home/me/proj",
    )

    private fun read(path: String) = buildJsonObject { put("file_path", path) }
    private fun bash(cmd: String) = buildJsonObject { put("command", cmd) }
    private fun v(tool: String, input: JsonObject) = SensitiveGuard.verdict(tool, input, policy)

    // ── the reported call, both routes a regex literal takes into the guard ───────────────────────────────

    @Test
    fun `a regex literal in a command is not mistaken for a network share`() {
        assertEquals(Verdict.ALLOW, v("Bash", bash("""rg --pcre2 '/\btype\s*:\s*/' src/""")))
        assertEquals(Verdict.ALLOW, v("Bash", bash("""node -e 'console.log(/\bexport\b/.test(s))'""")))
    }

    // The FAMILY, not the instance: every regex literal whose first atom is an escape acquired a `//` prefix
    // under the old ordering. WHICH of them actually fired is an asymmetry worth naming rather than glossing —
    // only the ones whose escape is followed by literal word characters, because that is what leaves a
    // hostname-shaped first segment. `\d+`, `\s*` and `\w+` glue a `+` or a `*` to the escape letter, and
    // `ForeignTerritory.UNC_HOST` already rejected those, so they never reported. They are pinned all the
    // same: they were one character away from firing, and a manufactured prefix is the defect whether or not
    // the rule downstream of it happened to catch the result.
    @Test
    fun `no regex literal reaches the rules wearing a UNC prefix`() {
        for (literal in listOf(
            """/\btype\s*:\s*/""", // the reported one
            """/\bfoo\b/""", // same shape: `bfoo` is a host, `b` is a share — refused before the fix
            """/\bTODO\b/""", // likewise
            """/\d\.\d/""", // a one-character host and a `.` share — also refused before the fix
            """/\d+/""", // never fired: `d+` is not hostname-shaped
            """/\s*foo\s*/""", // never fired: `s*foo` is not hostname-shaped
            """/\w+\.txt/""", // never fired: `w+` is not hostname-shaped
        )) {
            assertFalse(GuardPaths.normalize(literal, home).startsWith("//"), literal)
            assertFalse(ForeignTerritory.isUnc(GuardPaths.normalize(literal, home)), literal)
            assertEquals(Verdict.ALLOW, v("Grep", buildJsonObject { put("pattern", literal) }), literal)
            assertEquals(Verdict.ALLOW, v("Bash", bash("rg --pcre2 $literal src/")), literal)
        }
    }

    // The sweep past regex literals: the other things an ordinary programming language puts into a command
    // whose spelling canonicalisation can turn path-shaped — a substitution expression, a source-level escaped
    // Windows path, an escape sequence that is not a separator, a line comment. None may cost an unoverridable
    // refusal, which is what any of them being read as a location would produce.
    @Test
    fun `ordinary source text that canonicalisation can path-shape stays allowed`() {
        listOf(
            """sed -i 's/\bfoo\b/bar/g' src/App.kt""",
            """grep -P '\btype\s*:' src/""",
            """python3 -c 'print("a\tb\nc")'""",
            """echo 'C:\\Users\\me\\app'""",
            """rg '// TODO: drop this' src/""",
        ).forEach { assertEquals(Verdict.ALLOW, v("Bash", bash(it)), it) }
    }

    // ── the half that matters: the fix withdraws no verdict from anything that is a path ──────────────────

    @Test
    fun `every real UNC spelling is still foreign territory`() {
        assertEquals(Verdict.DENY, v("Read", read("""\\server\share\file""")))
        assertEquals(Verdict.DENY, v("Read", read("//server/share/file")))
        assertEquals(Verdict.DENY, v("Read", read("""\\?\UNC\server\share\x""")))
        assertEquals(Verdict.DENY, v("Read", read("""\\.\pipe\x""")))
        assertEquals(Verdict.DENY, v("Bash", bash("""cp \\fileserver\backup\dump.sql .""")))
        assertEquals(Verdict.DENY, v("Bash", bash("cp //fileserver/backup/dump.sql .")))
        // …and the prefix still SURVIVES normalization, which is the form `isUnc` is actually handed.
        assertTrue(GuardPaths.normalize("""\\server\share\file""", home).startsWith("//"))
        assertTrue(GuardPaths.normalize("//server/share/file", home).startsWith("//"))
    }

    @Test
    fun `a sensitive path dressed as a regex literal is still caught`() {
        // The inverse trap, and the assertion this whole change rests on. Nothing is DROPPED — a dropped
        // candidate is no match from any rule at once — so a value that really is a path merely loses a prefix
        // it never had and is then judged by every rule, including the one that actually fits it.
        // `/\home/bob/…` was refused as a network share (the right answer for the wrong reason, and out of
        // reach of the anchored `HOME_SEGMENT`); it is refused as another user's home now.
        assertEquals(Verdict.DENY, v("Read", read("""/\home/bob/.ssh/id_rsa""")))
        assertEquals(Verdict.DENY, v("Bash", bash("""cat /\home/bob/.bashrc""")))
        assertEquals(Verdict.DENY, v("Read", read("""C:\Users\bob\Desktop\notes.txt""")))
        assertEquals(Verdict.ASK, v("Read", read("""/\home/me/.ssh/id_rsa"""))) // own home: credential, not foreign
        assertEquals(Verdict.DENY, v("mcp__fs__get", read("""/\home/me/.ssh/id_rsa""")))
    }

    @Test
    fun `wrapping a share in regex delimiters reaches no share`() {
        // The obvious attempt at turning the fix into the hole: dress `\\server\share` as a regex literal. The
        // definition is anchored at the FIRST two characters, and a third separator there already names no
        // host, so `\\\server\share` resolves nowhere on Windows while `/\\server\share` is a directory
        // literally called `\\server\share` on POSIX. No reachable location is lost by declining both — and
        // the spelling that DOES reach the share is still refused, which is the load-bearing assertion.
        assertFalse(ForeignTerritory.isUnc("""\\\server\share"""))
        assertEquals(Verdict.ALLOW, v("Bash", bash("""rg '/\\server\share/' src/""")))
        assertEquals(Verdict.DENY, v("Bash", bash("""cp \\server\share\x .""")))
    }

    @Test
    fun `the UNC prefix is read after variable expansion, never off the raw argument`() {
        // WHERE the shape test sits relative to canonicalisation is the whole of it, and this package has been
        // burned by that inversion before: measuring before normalising is what let a `/.`-padded credential
        // path slip out of `MAX_PATH_LEN`. Here the same inversion fails in the other direction — a Windows
        // home can itself be a share, so `~` and `%USERPROFILE%` acquire the prefix only from the value
        // substituted into them, and a test applied to the caller's raw argument would throw it away.
        val uncHome = """\\nas\users\me"""
        assertEquals("//nas/users/me/.ssh/id_rsa", GuardPaths.normalize("~/.ssh/id_rsa", uncHome))
        assertEquals("//nas/users/me/x", GuardPaths.normalize("\$HOME/x", uncHome))
        assertEquals("//nas/users/me", GuardPaths.normalize("%USERPROFILE%", uncHome))
        // …while reading it AFTER the backslash translation is precisely the inversion that produced this bug.
        assertEquals("/btype/s*:/s*", GuardPaths.normalize("""/\btype\s*:\s*/""", home))
        assertEquals("//server/share/x", GuardPaths.normalize("""\\server\share\x""", home))
    }
}
