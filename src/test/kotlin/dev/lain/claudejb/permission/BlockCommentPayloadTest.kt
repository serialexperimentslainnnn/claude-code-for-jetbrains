package dev.lain.claudejb.permission

import dev.lain.claudejb.permission.SensitiveGuard.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The ONE shape a payload may be without answering "is this outside the project": a whole block comment.
 *
 * A Kotlin doc-comment opener begins with a slash, so editing a doc block reached OUTSIDE_PROJECT as an absolute
 * path at the filesystem root — a match made of pure syntax, exactly like the `/pattern/` and command-token
 * cases the neighbouring exclusions already exist for. This file is the boundary of that carve-out, asserted
 * from both sides, because an exclusion nobody bounds is a hole nobody notices.
 *
 * **Both bounds are load-bearing and both are tested here.** The KEY must be a payload, so the call's real
 * destination is still judged; and the SHAPE must be a comment and nothing else, so a recursive glob with a path
 * after it stays a candidate. Neither bound alone would be enough: the shape alone would exempt a glob in
 * `file_path`, and the key alone would exempt every path ever quoted in a document.
 */
class BlockCommentPayloadTest {

    private val home = "/home/me"

    private val policy = SensitiveGuard.Policy(
        globs = CredentialPaths.SENSITIVE_GLOBS,
        home = home,
        currentUser = "me",
        projectRoot = "/home/me/proj",
    )

    /** An `Edit` as the tool sends one: destination in `file_path`, payload in the string pair. */
    private fun edit(oldString: String, newString: String = "unchanged") = buildJsonObject {
        put("file_path", "/home/me/proj/src/Main.kt")
        put("old_string", oldString)
        put("new_string", newString)
    }

    private fun verdict(input: JsonObject) = SensitiveGuard.evaluate(input, policy).verdict

    private fun rule(input: JsonObject) = SensitiveGuard.evaluate(input, policy).rule

    // ── the carve-out ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a bare doc-comment opener in a payload is allowed`() {
        // THE REPORTED CASE. Three characters, in the replacement text of an edit to a Kotlin file.
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

    // ── the shape bound: what still fires ────────────────────────────────────────────────────────────────

    @Test
    fun `a recursive glob with a path after it is still a candidate`() {
        // Opens like a comment and closes like nothing. `/**/id_rsa` names files; a comment does not.
        val glob = "/" + "**/id_rsa"

        assertEquals(Verdict.DENY, verdict(edit(oldString = glob)))
    }

    @Test
    fun `a line comment is not a block comment`() {
        // Two slashes, not slash-star: on Windows this shape is a UNC share, which is why it must keep firing.
        // The frozen suite pins both of these already; they are here as the boundary of THIS change.
        assertEquals(Verdict.DENY, verdict(edit(oldString = "//nolint:unused")))
        assertEquals(Verdict.DENY, verdict(edit(oldString = "// jump-to-code links (jb://open)")))
    }

    @Test
    fun `a comment with a credential path inside it still trips the credential rule`() {
        // The exclusion is scoped to ONE rule's candidate list. `pathCandidates` is untouched, so the walls
        // still see the payload — a comment is not a way to smuggle a path past them.
        val commented = "/" + "* see $home/.ssh/id_rsa */"

        assertEquals(SecurityRule.CREDENTIALS, rule(edit(oldString = commented)))
    }

    // ── the key bound: what still fires ──────────────────────────────────────────────────────────────────

    @Test
    fun `the same shape in file_path is still judged`() {
        // The carve-out is keyed on the payload. A destination that happens to look like a comment is still a
        // destination, and this is the assertion that stops the shape test from becoming a general exemption.
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
        // Nothing about the accepted cost documented on CONTENT_KEY changed: a payload that quotes a foreign
        // path is still a candidate. Only the comment shape was carved out.
        assertEquals(Verdict.DENY, verdict(edit(oldString = "/home/bob/.cache/app")))
    }
}
