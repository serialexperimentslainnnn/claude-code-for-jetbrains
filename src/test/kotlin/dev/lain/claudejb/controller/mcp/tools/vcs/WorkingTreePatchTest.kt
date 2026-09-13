package dev.lain.claudejb.controller.mcp.tools.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkingTreePatchTest {

    @Test
    fun `the IDE's patch header is dropped and the unified diff is kept whole`() {
        val patch = listOf(
            "Index: docs/A.md",
            "IDEA additional info:",
            "Subsystem: com.intellij.openapi.diff.impl.patch.CharsetEP",
            "<+>UTF-8",
            "===================================================================",
            "diff --git a/docs/A.md b/docs/A.md",
            "--- a/docs/A.md\t(revision 1)",
            "+++ b/docs/A.md\t(date 2)",
            "@@ -1,1 +1,1 @@",
            "-old",
            "+new",
        ).joinToString("\n")

        assertEquals(patch.lines().drop(5).joinToString("\n"), WorkingTreePatch.stripIdeHeaders(patch))
    }
}
