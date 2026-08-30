package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.ui.ChatTabsPanel
import dev.lain.claudejb.ui.TabSessionCommands
import javax.swing.JPanel

class ChatTabsCloseHeadlessTest : BasePlatformTestCase() {

    private lateinit var tabs: ChatTabsPanel
    private lateinit var closed: MutableList<String>

    private lateinit var shownWhenClosed: MutableList<String?>

    private lateinit var opened: MutableList<String>

    override fun setUp() {
        super.setUp()
        tabs = ChatTabsPanel()
        closed = mutableListOf()
        shownWhenClosed = mutableListOf()
        opened = mutableListOf()
        tabs.onEvents(
            selected = {},
            closed = { tab ->
                closed += tab.title
                shownWhenClosed += tabs.selected?.title
            },
        )
    }

    override fun tearDown() {
        try {
            if (::tabs.isInitialized) tabs.dispose()
            val manager = ChatSessionManager.getInstance(project)
            manager.all().forEach { runCatching { manager.remove(it) } }
        } finally {
            super.tearDown()
        }
    }

    private fun open(title: String) = tabs.add(JPanel(), title, title, null)

    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    private fun wireCommands() {
        tabs.commands = TabSessionCommands(project, tabs) { _, select ->
            val tab = tabs.add(JPanel(), "Replacement", "Replacement", null)
            opened += tab.title
            if (select) tabs.select(tab)
        }
    }

    fun `test closing one tab leaves the others alone`() {
        val a = open("A")
        open("B")
        open("C")
        tabs.select(a)

        tabs.close(a)

        assertEquals(listOf("B", "C"), tabs.all().map { it.title })
        assertEquals("the close callback fires for the closed tab, once", listOf("A"), closed)
    }

    fun `test closing an unselected tab does not change which one is on screen`() {
        val a = open("A")
        val b = open("B")
        tabs.select(a)

        tabs.close(b)

        assertEquals("closing B must leave A", listOf("A"), tabs.all().map { it.title })
        assertEquals("and must not fire a close for anything else", listOf("B"), closed)
    }

    fun `test closing the selected tab shows another one rather than nothing`() {
        val a = open("A")
        val b = open("B")
        tabs.select(a)

        tabs.close(a)

        assertEquals(listOf("B"), tabs.all().map { it.title })
        assertNotNull("the area must never be left blank while a tab remains", tabs.selectedChat ?: b)
    }

    fun `test closing a tab twice is not two closes`() {
        val a = open("A")
        open("B")

        tabs.close(a)
        tabs.close(a)

        assertEquals(listOf("A"), closed)
        assertEquals(listOf("B"), tabs.all().map { it.title })
    }

    fun `test closing the last tab leaves the strip empty rather than half torn down`() {
        val a = open("A")
        tabs.select(a)

        tabs.close(a)

        assertTrue(tabs.all().isEmpty())
        assertEquals(listOf("A"), closed)
    }

    fun `test the last tab is hidden before it leaves the deck, never shown again`() {
        val a = open("A")
        tabs.select(a)

        tabs.close(a)

        assertFalse(
            "closing the only card must not leave the card layout re-showing the torn down chat",
            a.component.isVisible,
        )
        assertNull("and the card must be out of the deck", a.component.parent)
    }

    fun `test closing the selected tab hides it and shows the survivor`() {
        val a = open("A")
        val b = open("B")
        tabs.select(a)

        tabs.close(a)

        assertFalse(a.component.isVisible)
        assertTrue(b.component.isVisible)
    }

    fun `test the closed tab is out of the model when close returns`() {
        val a = open("A")
        open("B")
        tabs.select(a)

        tabs.close(a)

        assertNull("no event may stand between the press and the tab going", tabs.all().firstOrNull { it === a })
    }

    fun `test the survivor is on screen before the session teardown is asked for`() {
        val a = open("A")
        open("B")
        tabs.select(a)

        tabs.close(a)

        assertEquals(listOf("B"), shownWhenClosed)
    }

    fun `test closing a background tab settles the bar before the teardown too`() {
        val a = open("A")
        val b = open("B")
        tabs.select(a)

        tabs.close(b)

        assertEquals(listOf("A"), shownWhenClosed)
    }

    fun `test closing the last chat opens exactly one replacement, on a later event`() {
        wireCommands()
        val a = open("A")
        tabs.select(a)

        tabs.close(a)

        assertTrue("the replacement must not be built inside the close", tabs.all().isEmpty())
        assertEquals("and nothing was opened before the queue was drained", emptyList<String>(), opened)

        flush()

        assertEquals(listOf("Replacement"), tabs.all().map { it.title })
        flush()
        assertEquals("exactly one, however many events are pumped", listOf("Replacement"), opened)
        assertEquals(listOf("Replacement"), tabs.all().map { it.title })
    }

    fun `test a strip disposed before the replacement runs opens nothing`() {
        wireCommands()
        val a = open("A")
        tabs.select(a)

        tabs.close(a)
        tabs.dispose()
        flush()

        assertEquals(emptyList<String>(), opened)
    }

    fun `test a chat opened while the replacement is queued suppresses it`() {
        wireCommands()
        val a = open("A")
        tabs.select(a)

        tabs.close(a)
        open("Restored")
        flush()

        assertEquals(listOf("Restored"), tabs.all().map { it.title })
        assertEquals(emptyList<String>(), opened)
    }
}
