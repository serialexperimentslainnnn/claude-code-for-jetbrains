package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.WindowsVectorCorpus
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class GuardWindowsContainmentTest {

    private val windows = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
        fileReader = null,
    )

    private fun read(path: String) = buildJsonObject { put("file_path", path) }

    private fun v(input: kotlinx.serialization.json.JsonObject, p: SensitiveGuard.Policy = windows) =
        SensitiveGuard.evaluate(input, p).verdict

    private fun rule(input: kotlinx.serialization.json.JsonObject, p: SensitiveGuard.Policy = windows) =
        SensitiveGuard.evaluate(input, p).rule

    @ParameterizedTest
    @MethodSource("vectors")
    fun `every Windows vector gives its verdict, and its rule where one is named`(vector: WindowsVectorCorpus.Vector) {
        val decision = SensitiveGuard.evaluate(vector.input, windows)
        assertEquals(vector.verdict, decision.verdict, vector.label)
        if (vector.rule != null) assertEquals(vector.rule, decision.rule, vector.label)
    }

    @Test
    fun `a drive-rooted project folds case, so a differently-cased spelling is still inside`() {
        assertEquals(Verdict.ALLOW, v(read("C:/proj/src/Foo.kt")))
        assertEquals(Verdict.ALLOW, v(read("c:/proj/src/Foo.kt")))
        assertEquals(Verdict.ALLOW, v(read("C:/Proj/src/Foo.kt")))
    }

    @Test
    fun `a sibling of a drive-rooted project is still outside`() {
        assertEquals(Verdict.DENY, v(read("C:/other/x.kt")))
        assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(read("C:/other/x.kt")))
    }

    @Test
    fun `case folding for a non-drive root is the flag, not the host`() {
        val insensitive = SensitiveGuard.Policy(home = "/home/me", currentUser = "me", projectRoot = "/home/me/proj", caseInsensitivePaths = true)
        val sensitive = insensitive.copy(caseInsensitivePaths = false)
        assertEquals(Verdict.ALLOW, v(read("/home/me/PROJ/src/Foo.kt"), insensitive))
        assertEquals(Verdict.DENY, v(read("/home/me/PROJ/src/Foo.kt"), sensitive))
    }

    @Test
    fun `a UNC project root contains its own subtree, and no other share`() {
        val unc = windows.copy(projectRoot = """\\nas\team\proj""")
        assertEquals(Verdict.ALLOW, v(read("""\\nas\team\proj\src\A.kt"""), unc))
        assertEquals(Verdict.DENY, v(read("""\\nas\team\other\x"""), unc))
        assertEquals(SecurityRule.NETWORK_MOUNT, rule(read("""\\nas\team\other\x"""), unc))
    }

    @Test
    fun `the Windows environment spellings normalize against a backslash home`() {
        val home = """C:\Users\me"""
        assertEquals("C:/Users/me/AppData/Local/x", GuardPaths.normalize("""%LOCALAPPDATA%\x""", home))
        assertEquals("C:/Users/me/AppData/Roaming/x", GuardPaths.normalize("""%APPDATA%\x""", home))
        assertEquals("C:/Users/me/x", GuardPaths.normalize("""%HOMEPATH%\x""", home))
        assertEquals("C:/Users/me/x", GuardPaths.normalize("""%USERPROFILE%\x""", home))
        assertEquals("C:/Users/me/x", GuardPaths.normalize("""${'$'}env:USERPROFILE\x""", home))
        assertEquals("C:/Users/me/x", GuardPaths.normalize("""~\x""", home))
    }

    companion object {
        @JvmStatic
        fun vectors(): List<WindowsVectorCorpus.Vector> = WindowsVectorCorpus.all
    }
}
