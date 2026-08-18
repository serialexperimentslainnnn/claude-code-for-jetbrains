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

    /**
     * REGRESSION: the fallback title numbered the chats ever CREATED, not the ones open.
     *
     * Closing the last chat opens a replacement (`ChatTabsPanel.replaceLastChat`), so a session spent closing
     * and reopening one conversation climbed to `Chat 47` while never holding more than one — a number counting
     * something the user cannot see. It is the lowest free number now.
     */
    fun `test the fallback title reuses the lowest number no open chat is using`() {
        val first = manager.create()
        assertEquals("Chat 1", first.title)
        val second = manager.create()
        assertEquals("Chat 2", second.title)

        // Close them both and the numbering starts over, which is the reported bug.
        manager.remove(first)
        manager.remove(second)
        assertEquals("Chat 1", manager.create().title)
    }

    fun `test a freed number is reused rather than skipped, so two chats never share a title`() {
        val one = manager.create()
        val two = manager.create()
        val three = manager.create()
        assertEquals(listOf("Chat 1", "Chat 2", "Chat 3"), manager.all().map { it.title })

        // Closing from the MIDDLE is the case a count-based name gets wrong: `size + 1` would say "Chat 3"
        // while `three` already holds it.
        manager.remove(two)
        val replacement = manager.create()
        assertEquals("Chat 2", replacement.title)
        assertEquals(manager.all().size, manager.all().map { it.title }.toSet().size)
        assertTrue(one.title == "Chat 1" && three.title == "Chat 3")
    }

    fun `test a renamed chat frees its number, because the number only tells unnamed chats apart`() {
        val first = manager.create()
        assertEquals("Chat 1", first.title)
        first.title = "Release notes"
        assertEquals("Chat 1", manager.create().title)
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
