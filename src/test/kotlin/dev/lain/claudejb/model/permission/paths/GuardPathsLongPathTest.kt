package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuardPathsLongPathTest : GuardProbe(
    SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
    ),
) {

    @Test
    fun `the local long-path prefix is spelling, not a share`() {
        assertEquals("C:/proj/src/A.kt", GuardPaths.normalize("""\\?\C:\proj\src\A.kt""", null))
        assertEquals("C:/proj/src/A.kt", GuardPaths.normalize("""//?/C:/proj/src/A.kt""", null))
        assertEquals("C:/proj/src/A.kt", GuardPaths.normalize("""\\?C:/proj/src/A.kt""", null))
    }

    @Test
    fun `a project file spelled with the long-path prefix is inside the project`() {
        assertEquals(Verdict.ALLOW, v(read("""\\?\C:\proj\src\A.kt""")))
    }

    @Test
    fun `the long-path prefix does not launder a path outside the project`() {
        assertEquals(Verdict.DENY, v(read("""\\?\D:\other\x""")))
        assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(read("""\\?\D:\other\x""")))
    }

    @Test
    fun `the UNC long-path prefix is still a share`() {
        assertEquals(Verdict.DENY, v(read("""\\?\UNC\nas\share\x""")))
        assertEquals(SecurityRule.NETWORK_MOUNT, rule(read("""\\?\UNC\nas\share\x""")))
    }
}
