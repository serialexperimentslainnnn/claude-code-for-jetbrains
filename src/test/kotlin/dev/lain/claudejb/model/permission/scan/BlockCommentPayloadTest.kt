package dev.lain.claudejb.model.permission.scan

import dev.lain.claudejb.model.permission.GuardFixture
import dev.lain.claudejb.model.permission.GuardProbe
import dev.lain.claudejb.model.permission.SensitiveGuard.Verdict
import dev.lain.claudejb.model.permission.vocab.SecurityRule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockCommentPayloadTest : GuardProbe() {

    private fun edit(oldString: String, newString: String = "unchanged") =
        edit("/home/me/proj/src/Main.kt", oldString, newString)

    @Test
    fun `a bare doc-comment opener in a payload is allowed`() {
        val opener = "/" + "**"

        assertEquals(Verdict.ALLOW, v(edit(oldString = opener)))
        assertEquals(Verdict.ALLOW, v(edit(oldString = "old", newString = opener)))
    }

    @Test
    fun `a whole multi-line doc comment in a payload is allowed`() {
        val kdoc = "/" + "**\n * Why this exists, and what it costs.\n */"

        assertEquals(Verdict.ALLOW, v(edit(oldString = kdoc)))
    }

    @Test
    fun `a plain block comment is allowed too, not only the doc form`() {
        assertEquals(Verdict.ALLOW, v(edit(oldString = "/" + "* one line */")))
    }

    @Test
    fun `a recursive glob with a path after it is still a candidate`() {
        val glob = "/" + "**/id_rsa"

        assertEquals(Verdict.DENY, v(edit(oldString = glob)))
    }

    @Test
    fun `a line comment is not a block comment`() {
        assertEquals(Verdict.DENY, v(edit(oldString = "//nolint:unused")))
        assertEquals(Verdict.DENY, v(edit(oldString = "// jump-to-code links (jb://open)")))
    }

    @Test
    fun `a comment with a credential path inside it still trips the credential rule`() {
        val commented = "/" + "* see ${GuardFixture.HOME}/.ssh/id_rsa */"

        assertEquals(SecurityRule.CREDENTIALS, rule(edit(oldString = commented)))
    }

    @Test
    fun `the same shape in file_path is still judged`() {
        val asDestination = buildJsonObject {
            put("file_path", "/" + "**")
            put("old_string", "a")
            put("new_string", "b")
        }

        assertEquals(Verdict.DENY, v(asDestination))
    }

    @Test
    fun `a command that is a block comment is still tokenised as a command`() {
        val cmd = bash("cat /home/bob/.ssh/id_rsa /" + "* sneaky */")

        assertEquals(Verdict.DENY, v(cmd))
    }

    @Test
    fun `an ordinary payload outside the project is still denied`() {
        assertEquals(Verdict.DENY, v(edit(oldString = "/home/bob/.cache/app")))
    }
}
