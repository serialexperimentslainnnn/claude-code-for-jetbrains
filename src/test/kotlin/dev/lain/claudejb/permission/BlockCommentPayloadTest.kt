package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockCommentPayloadTest {

    private val home = "/home/me"

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = home,
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    private fun edit(oldString: String, newString: String = "unchanged") = buildJsonObject {
        put("file_path", "/home/me/proj/src/Main.kt")
        put("old_string", oldString)
        put("new_string", newString)
    }

    private fun verdict(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    private fun rule(input: JsonObject) = SensitiveGuard.evaluate(input, policy).rule

    @Test
    fun `a bare doc-comment opener in a payload is allowed`() {
        val opener = "/" + "**"

        assertEquals(Verdict.ALLOW, verdict(edit(oldString = opener)))
        assertEquals(Verdict.ALLOW, verdict(edit(oldString = "old", newString = opener)))
    }

    @Test
    fun `a whole multi-line doc comment in a payload is allowed`() {
        val kdoc = "/" + "**\n * Why this exists, and what it costs.\n */"

        assertEquals(Verdict.ALLOW, verdict(edit(oldString = kdoc)))
    }

    @Test
    fun `a plain block comment is allowed too, not only the doc form`() {
        assertEquals(Verdict.ALLOW, verdict(edit(oldString = "/" + "* one line */")))
    }

    @Test
    fun `a recursive glob with a path after it is still a candidate`() {
        val glob = "/" + "**/id_rsa"

        assertEquals(Verdict.DENY, verdict(edit(oldString = glob)))
    }

    @Test
    fun `a line comment is not a block comment`() {
        assertEquals(Verdict.DENY, verdict(edit(oldString = "//nolint:unused")))
        assertEquals(Verdict.DENY, verdict(edit(oldString = "// jump-to-code links (jb://open)")))
    }

    @Test
    fun `a comment with a credential path inside it still trips the credential rule`() {
        val commented = "/" + "* see $home/.ssh/id_rsa */"

        assertEquals(SecurityRule.CREDENTIALS, rule(edit(oldString = commented)))
    }

    @Test
    fun `the same shape in file_path is still judged`() {
        val asDestination = buildJsonObject {
            put("file_path", "/" + "**")
            put("old_string", "a")
            put("new_string", "b")
        }

        assertEquals(Verdict.DENY, verdict(asDestination))
    }

    @Test
    fun `a command that is a block comment is still tokenised as a command`() {
        val cmd = buildJsonObject { put("command", "cat /home/bob/.ssh/id_rsa /" + "* sneaky */") }

        assertEquals(Verdict.DENY, verdict(cmd))
    }

    @Test
    fun `an ordinary payload outside the project still asks`() {
        assertEquals(Verdict.DENY, verdict(edit(oldString = "/home/bob/.cache/app")))
    }
}
