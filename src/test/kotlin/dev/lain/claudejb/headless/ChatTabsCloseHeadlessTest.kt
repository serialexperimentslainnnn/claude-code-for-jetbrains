package dev.lain.claudejb.headless

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.session.ChatSessionManager
import dev.lain.claudejb.ui.ChatTabsPanel
import dev.lain.claudejb.ui.TabSessionCommands
import javax.swing.JPanel

/**
 * Closing ONE tab closes one tab, and it does it **now**.
 *
 * That sentence needed a test because the close path is the most tangled method in the strip: it removes from
 * a list, fires a callback that disposes a `claude` process, swaps the `CardLayout` to a survivor, disposes a
 * JCEF browser, and opens a fresh chat when nothing is left. Five things, in an order that matters, and the
 * only one of them a reader can verify by eye is the first. Three failures came out of it: a `CardLayout` that
 * validated a disposed browser and threw out of `Container.remove`, taking every line after it with it (so the
 * pill stayed and the button looked dead); a report that closing one chat closed them all; and the reason this
 * file grew — the whole teardown ran in ONE event on the EDT, so the press produced no visible change at all
 * until it was over, and the closed chat's pill sat on the bar, greyed, for the duration.
 *
 * The `WHEN` half is what the new tests are about, and it is worth naming because it does not look like a
 * behaviour: *what the user is waiting to see is settled before anything expensive is asked for*. The tab is
 * out of the model and the survivor is on screen before the session is torn down, and the replacement chat
 * that a last close owes them is opened on a LATER event rather than inside this one.
 *
 * What is NOT here is the reason the callback may dispose that process without asking — that a tab is a chat
 * and no second tab holds its session. It cannot be: a session needs a real `JcefChatPanel`, and that needs a
 * browser. It is pinned as a source contract instead (`ToolWindowWiringContractTest`), over the one place a
 * chat panel is built. Neither is *where* the process teardown runs: no double reaches through a real session,
 * so that is `EdtProcessTeardownContractTest`, over the source.
 *
 * Plain `JPanel`s, deliberately: what is under test is the bookkeeping — which tabs survive, which callbacks
 * fire, when, and with which tab — and a real `JcefChatPanel` would drag a browser and a session into a test
 * about a list. The paths that need those have their own tests.
 */
class ChatTabsCloseHeadlessTest : BasePlatformTestCase() {

    private lateinit var tabs: ChatTabsPanel
    private lateinit var closed: MutableList<String>

    /** The tab on screen at the instant each close callback ran — the ordering the ghost pill was about. */
    private lateinit var shownWhenClosed: MutableList<String?>

    /** Tabs the wired [TabSessionCommands] opened, so a replacement can be told from a survivor. */
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
            // Guarded: a failure in `super.setUp()` leaves this uninitialised, and an exception thrown here
            // would replace the real one in the report.
            if (::tabs.isInitialized) tabs.dispose()
            // Sessions a wired `newChat` created are real ones; drop them before the project goes.
            val manager = ChatSessionManager.getInstance(project)
            manager.all().forEach { runCatching { manager.remove(it) } }
        } finally {
            super.tearDown()
        }
    }

    private fun open(title: String) = tabs.add(JPanel(), title, title, null)

    /**
     * Runs whatever is queued on the event queue and nothing else — the house pump
     * ([ClaudeSessionEventSurfacingHeadlessTest] and three others spell it the same way).
     *
     * It is what makes "in this call" and "in the next event" two different observable states, so every
     * assertion below is placed deliberately on one side of it: a pump before the state it is about would turn
     * this file from "the tab is gone" into "the tab will be gone", which is the claim that was never in doubt.
     * It waits for nothing — no process, no timer, no page — so nothing here can pass by outlasting a race.
     */
    private fun flush() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    /**
     * Wires the commands the strip needs to replace a last closed chat, with the tool window's own job — build
     * a panel — swapped for a `JPanel`. `newChat()` still goes through [ChatSessionManager], so the
     * replacement is a real session in the real service, exactly as in a running IDE.
     */
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
        // With no `commands` wired there is nothing to open a replacement with, which is the state this test
        // wants: it asserts the TEARDOWN is complete and consistent. That a replacement appears when there IS
        // something to open one with is the three tests below.
        val a = open("A")
        tabs.select(a)

        tabs.close(a)

        assertTrue(tabs.all().isEmpty())
        assertEquals(listOf("A"), closed)
    }

    /**
     * The model is right when the call returns — not eventually.
     *
     * The bar is drawn from [ChatTabsPanel.chatList], which reads this list, so anything that made the removal
     * asynchronous would put a pill on screen for a chat that no longer exists and leave no way to tell which
     * of the two states is the true one. Asserted with no [flush] on purpose: pumping here would hide exactly
     * the defect it is guarding against.
     */
    fun `test the closed tab is out of the model when close returns`() {
        val a = open("A")
        open("B")
        tabs.select(a)

        tabs.close(a)

        assertNull("no event may stand between the press and the tab going", tabs.all().firstOrNull { it === a })
    }

    /**
     * The survivor is already on screen by the time the session is asked to go — the ghost-pill ordering.
     *
     * `onClosed` is what disposes the tab's `claude`, and it used to be the FIRST line of the close. So every
     * consequence the user is waiting for — the pill leaving the bar, the survivor's card being shown — was
     * sequenced behind it, and behind the browser teardown that follows. This asserts the order that fixed it
     * from the one angle a headless test can see it: what the strip considers "on screen" at the instant the
     * callback runs.
     */
    fun `test the survivor is on screen before the session teardown is asked for`() {
        val a = open("A")
        open("B")
        tabs.select(a)

        tabs.close(a)

        assertEquals(listOf("B"), shownWhenClosed)
    }

    /** The same order when the closed tab was NOT the one on screen: nothing moves, and it moves first. */
    fun `test closing a background tab settles the bar before the teardown too`() {
        val a = open("A")
        val b = open("B")
        tabs.select(a)

        tabs.close(b)

        assertEquals(listOf("A"), shownWhenClosed)
    }

    /**
     * Closing the last chat leaves exactly one new one — and does not build it inside the close.
     *
     * Both halves matter and they fail differently. No replacement at all is an empty tool window with no
     * control anywhere to make a chat, which is what this behaviour exists to prevent. A replacement built
     * *inline* is a whole second Chromium stood up in the same EDT event that has just torn one down, which is
     * the freeze: the press paints nothing until all of it is over, so it reads as a button that does not
     * work.
     */
    fun `test closing the last chat opens exactly one replacement, on a later event`() {
        wireCommands()
        val a = open("A")
        tabs.select(a)

        tabs.close(a)

        assertTrue("the replacement must not be built inside the close", tabs.all().isEmpty())
        assertEquals("and nothing was opened before the queue was drained", emptyList<String>(), opened)

        flush()

        assertEquals(listOf("Replacement"), tabs.all().map { it.title })
        // Draining again must find nothing left to run: the queued block re-checks that the strip is still
        // empty, so a second pass cannot open a second chat over the one it just made.
        flush()
        assertEquals("exactly one, however many events are pumped", listOf("Replacement"), opened)
        assertEquals(listOf("Replacement"), tabs.all().map { it.title })
    }

    /**
     * A strip torn down while the replacement was queued opens nothing.
     *
     * Deferring buys an event in which the tool window can close, and a chat opened into a disposed strip is a
     * `JBCefBrowser` and a `claude` process nothing will ever dispose.
     */
    fun `test a strip disposed before the replacement runs opens nothing`() {
        wireCommands()
        val a = open("A")
        tabs.select(a)

        tabs.close(a)
        tabs.dispose()
        flush()

        assertEquals(emptyList<String>(), opened)
    }

    /** And a chat that arrived in that same window is the replacement: the strip is not empty any more. */
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
