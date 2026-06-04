package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.SessionHistory

/** Headless: the [SessionHistory] persistent component round-trips the open-tab id list through workspace.xml. */
class SessionHistoryHeadlessTest : BasePlatformTestCase() {

    private val history get() = SessionHistory.getInstance(project)

    override fun setUp() {
        super.setUp()
        history.setOpenSessions(emptyList())
    }

    fun `test getInstance returns the project service`() {
        assertNotNull(history)
        assertSame(history, SessionHistory.getInstance(project))
    }

    fun `test setOpenSessions then openSessions preserves order`() {
        history.setOpenSessions(listOf("a", "b"))
        assertEquals(listOf("a", "b"), history.openSessions())
    }

    fun `test getState loadState round-trip survives a simulated reload`() {
        history.setOpenSessions(listOf("x", "y", "z"))
        val saved = history.getState()
        // Simulate the platform reloading persisted state into a fresh component.
        val reloaded = SessionHistory()
        reloaded.loadState(saved)
        assertEquals(listOf("x", "y", "z"), reloaded.openSessions())
    }

    fun `test blank ids are filtered out`() {
        history.setOpenSessions(listOf("a", "", "  ", "b"))
        assertEquals(listOf("a", "b"), history.openSessions())
    }
}
