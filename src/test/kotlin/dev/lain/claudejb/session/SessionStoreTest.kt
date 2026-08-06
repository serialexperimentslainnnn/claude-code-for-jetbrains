package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

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
        // Neutral, relative-style inputs only — no real local paths in tests.
        // Dots, underscores, spaces and traversal sequences all collapse to '-' (no separator survives).
        assertEquals("-home-u-My-Proj", SessionStore.encodePath("/home/u/My.Proj"))
        assertEquals("a-b-c", SessionStore.encodePath("a_b c"))
        assertEquals("-----", SessionStore.encodePath("/../."))
    }

    @Test
    fun `locate finds the transcript without removing anything`() {
        // What used to be the `delete` tests. The capability is gone (see the class KDoc): the store reads,
        // and this pins that a lookup leaves the whole tree exactly as it found it.
        val home = Files.createTempDirectory("claudejb-home")
        val originalHome = System.getProperty("user.home")
        try {
            val projectDir = home.resolve(".claude").resolve("projects").resolve("-tmp-proj")
            Files.createDirectories(projectDir)
            val id = "11111111-2222-3333-4444-555555555555"
            val target = projectDir.resolve("$id.jsonl").also { Files.writeString(it, "{}") }
            val sibling = projectDir.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jsonl").also { Files.writeString(it, "{}") }

            System.setProperty("user.home", home.toString())

            assertEquals(target, SessionStore.locate(id), "the targeted transcript must be found")
            assertTrue(SessionStore.exists(id))
            assertTrue(Files.exists(target), "locate must not remove the file it resolved")
            assertTrue(Files.exists(sibling), "nor any sibling")
        } finally {
            System.setProperty("user.home", originalHome)
            Files.walk(home).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
