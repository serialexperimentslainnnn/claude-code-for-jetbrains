package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardPathsDriveRelativeTest : GuardProbe() {

    private val windows = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = """C:\Users\me""",
        currentUser = "me",
        projectRoot = """C:\proj""",
        caseInsensitivePaths = true,
    )

    @Test
    fun `a drive-relative path resolves against a per-drive working directory nobody here knows, so it is outside`() {
        assertEquals(Verdict.DENY, v(read("C:foo/bar")))
        assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(read("C:foo/bar")))
    }

    @Test
    fun `the same spelling is outside a Windows project on that very drive`() {
        assertEquals(Verdict.DENY, v(read("C:foo/bar"), windows))
        assertEquals(SecurityRule.OUTSIDE_PROJECT, rule(read("C:foo/bar"), windows))
        assertEquals(Verdict.ALLOW, v(read("C:/proj/foo/bar"), windows))
    }

    @Test
    fun `folding keeps the drive of a drive-relative spelling instead of anchoring it in the project`() {
        assertTrue(GuardPaths.isAbsolute("C:foo"))
        assertTrue(GuardPaths.isAbsolute("c:"))
        assertFalse(GuardPaths.isAbsolute("foo:bar"))
        assertFalse(GuardPaths.isAbsolute("12:30"))
        assertEquals("C:foo/bar", GuardPaths.fold("C:foo/./bar"))
        assertEquals("C:x", GuardPaths.fold("C:../x"))
    }
}
