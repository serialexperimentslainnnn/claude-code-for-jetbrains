package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests [SessionStore]'s security-relevant, filesystem-independent behaviour: the path-traversal guard
 * (a non-UUID session id never resolves a path, short-circuiting before any FS access) and the pure cwd
 * folder-name encoding the `claude` binary uses.
 */
class SessionStoreTest {

    @Test
    fun `locate rejects ids that aren't plain UUID-like tokens`() {
        // Each contains a char outside [A-Za-z0-9-], so SAFE_ID.matches() fails and locate short-circuits to null
        // (the `||` returns before touching the filesystem) — no traversal possible.
        listOf(
            "../etc/passwd",
            "..",
            "a/b",
            "a\\b",
            "foo.jsonl",
            "with.dot",
            "with space",
            "",
        ).forEach { malicious ->
            assertNull(SessionStore.locate(malicious), "locate must reject '$malicious'")
            assertFalse(SessionStore.exists(malicious), "exists must be false for '$malicious'")
        }
    }

    @Test
    fun `encodePath maps every non-alphanumeric char to a dash`() {
        assertEquals(
            "-home-dexperiments-PycharmProjects-claude-code-for-jebtrains",
            SessionStore.encodePath("/home/dexperiments/PycharmProjects/claude-code-for-jebtrains"),
        )
        // Dots, underscores, spaces and traversal sequences all collapse to '-' (no separator survives).
        assertEquals("-home-u-My-Proj", SessionStore.encodePath("/home/u/My.Proj"))
        assertEquals("a-b-c", SessionStore.encodePath("a_b c"))
        assertEquals("-----", SessionStore.encodePath("/../."))
    }
}
