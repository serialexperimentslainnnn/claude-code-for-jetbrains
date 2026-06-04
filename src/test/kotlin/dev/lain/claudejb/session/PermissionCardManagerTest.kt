package dev.lain.claudejb.session

import dev.lain.claudejb.permission.PendingPermission
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the permission-card queue semantics extracted from `ClaudeSession`'s EDT-confined
 * `pending: LinkedHashMap`: present adds + fires onChanged, remove returns and detaches, all preserves
 * insertion order, clear empties (firing only when non-empty). PendingPermission is a plain data class, so
 * this runs as a pure-JVM unit test with no IDE.
 */
class PermissionCardManagerTest {

    private fun perm(id: String, tool: String = "Edit") =
        PendingPermission(
            requestId = id,
            toolName = tool,
            input = JsonObject(emptyMap()),
            title = "t",
            summary = "s",
            reviewable = false,
        )

    @Test
    fun `present adds the request and fires onChanged`() {
        var fired = 0
        val m = PermissionCardManager { fired++ }
        val p = perm("r1")
        m.present(p)
        assertEquals(1, fired)
        assertSame(p, m.get("r1"))
        assertEquals(listOf(p), m.all())
    }

    @Test
    fun `present by same requestId replaces the entry`() {
        var fired = 0
        val m = PermissionCardManager { fired++ }
        m.present(perm("r1", tool = "Edit"))
        val replacement = perm("r1", tool = "Write")
        m.present(replacement)
        assertEquals(2, fired)
        assertEquals(1, m.all().size)
        assertSame(replacement, m.get("r1"))
    }

    @Test
    fun `remove returns the request and detaches it`() {
        var fired = 0
        val m = PermissionCardManager { fired++ }
        val p = perm("r1")
        m.present(p)
        fired = 0
        val removed = m.remove("r1")
        assertSame(p, removed)
        // remove does NOT fire onChanged (the caller fires firePermissions itself after answering).
        assertEquals(0, fired)
        assertNull(m.get("r1"))
        assertTrue(m.all().isEmpty())
    }

    @Test
    fun `remove of unknown id returns null`() {
        val m = PermissionCardManager { }
        assertNull(m.remove("ghost"))
    }

    @Test
    fun `all preserves insertion order`() {
        val m = PermissionCardManager { }
        val a = perm("a")
        val b = perm("b")
        val c = perm("c")
        m.present(a); m.present(b); m.present(c)
        assertEquals(listOf("a", "b", "c"), m.all().map { it.requestId })
    }

    @Test
    fun `all is a decoupled snapshot`() {
        val m = PermissionCardManager { }
        m.present(perm("a"))
        val snapshot = m.all()
        m.present(perm("b"))
        assertEquals(1, snapshot.size)
        assertEquals(2, m.all().size)
    }

    @Test
    fun `clear empties and fires when non-empty`() {
        var fired = 0
        val m = PermissionCardManager { fired++ }
        m.present(perm("a"))
        m.present(perm("b"))
        fired = 0
        m.clear()
        assertEquals(1, fired)
        assertTrue(m.all().isEmpty())
    }

    @Test
    fun `clear is a no-op (no fire) when already empty`() {
        var fired = 0
        val m = PermissionCardManager { fired++ }
        m.clear()
        assertEquals(0, fired)
        assertTrue(m.all().isEmpty())
    }
}
