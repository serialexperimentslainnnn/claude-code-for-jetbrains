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
    fun `delete removes only the targeted UUID-named transcript and is confined to the projects tree`() {
        val home = Files.createTempDirectory("claudejb-home")
        val originalHome = System.getProperty("user.home")
        try {
            // Build ~/.claude/projects/<proj>/ with a session file and an unrelated sibling that must survive.
            val projectDir = home.resolve(".claude").resolve("projects").resolve("-tmp-proj")
            Files.createDirectories(projectDir)
            val id = "11111111-2222-3333-4444-555555555555"
            val target = projectDir.resolve("$id.jsonl").also { Files.writeString(it, "{}") }
            val sibling = projectDir.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jsonl").also { Files.writeString(it, "{}") }
            // A would-be victim outside the projects tree: a traversal must never reach it.
            val outside = home.resolve("secret.jsonl").also { Files.writeString(it, "do not delete") }

            // Point SessionStore at the temp home, then exercise delete.
            System.setProperty("user.home", home.toString())

            // Traversal / non-UUID ids are rejected before any FS access — nothing is deleted.
            assertFalse(SessionStore.delete("../../secret"), "traversal id must be rejected")
            assertFalse(SessionStore.delete("$id.jsonl"), "id carrying a dot must be rejected")
            assertTrue(Files.exists(outside), "file outside the projects tree must survive")

            // A genuine delete removes exactly the target.
            assertTrue(SessionStore.delete(id), "valid UUID delete should succeed")
            assertFalse(Files.exists(target), "targeted transcript must be gone")
            assertTrue(Files.exists(sibling), "unrelated session must survive")

            // Deleting again (now absent) is a no-op false.
            assertFalse(SessionStore.delete(id), "deleting an absent session returns false")
        } finally {
            System.setProperty("user.home", originalHome)
            Files.walk(home).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `delete is a no-op when no projects tree exists`() {
        // Sanity: with no projects tree present, even a well-formed id deletes nothing (locate short-circuits).
        val home = Files.createTempDirectory("claudejb-empty-home")
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.toString())
            assertFalse(SessionStore.delete("11111111-2222-3333-4444-555555555555"))
        } finally {
            System.setProperty("user.home", originalHome)
            Files.deleteIfExists(home)
        }
    }
}
