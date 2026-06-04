package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.SessionHistory

/**
 * Headless: the [ChatSessionManager] project service owns the set of open chat tabs.
 * Tests only the in-memory session bookkeeping — never [ClaudeSession.start], which would spawn the binary.
 */
class ChatSessionManagerHeadlessTest : BasePlatformTestCase() {

    private val manager get() = ChatSessionManager.getInstance(project)

    override fun tearDown() {
        // Dispose any sessions created during the test before the platform tears the project down.
        try {
            manager.all().forEach { runCatching { manager.remove(it) } }
        } finally {
            super.tearDown()
        }
    }

    fun `test getInstance returns the project service`() {
        assertNotNull(manager)
        assertSame(manager, ChatSessionManager.getInstance(project))
    }

    fun `test create returns a session and adds it to all`() {
        val session = manager.create()
        assertNotNull(session)
        assertInstanceOf(session, ClaudeSession::class.java)
        assertTrue(session in manager.all())
        assertEquals(1, manager.all().size)
    }

    fun `test create marks the new session active`() {
        val first = manager.create()
        assertSame(first, manager.active)
        val second = manager.create()
        assertSame(second, manager.active)
    }

    fun `test remove drops the session and reassigns active`() {
        val first = manager.create()
        val second = manager.create()
        assertSame(second, manager.active)
        manager.remove(second)
        assertFalse(second in manager.all())
        assertTrue(first in manager.all())
        // active falls back to the last remaining session.
        assertSame(first, manager.active)
    }

    fun `test remove keeps SessionHistory open ids in sync`() {
        val first = manager.create()
        val second = manager.create()
        manager.remove(second)
        // Sessions were never started, so their sessionId is null → no ids persisted.
        assertEquals(emptyList<String>(), SessionHistory.getInstance(project).openSessions())
        manager.remove(first)
        assertEquals(emptyList<String>(), SessionHistory.getInstance(project).openSessions())
    }
}
