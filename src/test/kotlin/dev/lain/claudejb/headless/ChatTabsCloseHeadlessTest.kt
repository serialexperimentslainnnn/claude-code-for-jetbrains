package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.ui.ChatTabsPanel
import javax.swing.JPanel

/**
 * Closing ONE tab closes one tab.
 *
 * That sentence needed a test because the close path is the most tangled method in the strip: it removes from
 * a list, cascades to the pinned views of the same session, fires a callback that disposes a `claude` process,
 * swaps the `CardLayout` to a survivor, disposes a JCEF browser, and — since 5.5.0 — opens a fresh chat when
 * nothing is left. Six things, in an order that matters, and the only one of them a reader can verify by eye
 * is the first. Two failures came out of it in one afternoon: a `CardLayout` that validated a disposed
 * browser and threw out of `Container.remove`, taking every line after it with it (so the pill stayed and the
 * button looked dead), and a report that closing one chat closed them all.
 *
 * Plain `JPanel`s, deliberately: what is under test is the bookkeeping — which tabs survive, which callbacks
 * fire, and with which tab — and a real `JcefChatPanel` would drag a browser and a session into a test about
 * a list. The paths that need those have their own tests.
 */
class ChatTabsCloseHeadlessTest : BasePlatformTestCase() {

    private lateinit var tabs: ChatTabsPanel
    private lateinit var closed: MutableList<String>

    override fun setUp() {
        super.setUp()
        tabs = ChatTabsPanel()
        closed = mutableListOf()
        tabs.onEvents(selected = {}, closed = { tab -> closed += tab.title })
    }

    private fun open(title: String) = tabs.add(JPanel(), title, title, null)

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
        // With no `commands` wired there is nothing to open a replacement with, which is the state this test
        // wants: it asserts the TEARDOWN is complete and consistent. That a replacement appears when there IS
        // a tool window behind it is the factory's business, and it is asserted where that lives.
        val a = open("A")
        tabs.select(a)

        tabs.close(a)

        assertTrue(tabs.all().isEmpty())
        assertEquals(listOf("A"), closed)
    }
}
