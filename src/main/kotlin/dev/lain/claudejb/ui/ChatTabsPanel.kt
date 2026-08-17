package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.ui.jcef.JcefSessionData
import dev.lain.claudejb.ui.jcef.JcefTabsData
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Holds the chats and switches between them. **It draws nothing.**
 *
 * The tab bar itself is part of the web app ([JcefChatPanel] → `app-tabs.js`), which is where the whole chat
 * UI has lived since 4.0.0. A Swing strip above the page cannot share its accent, type scale, transitions or
 * SVG, so keeping one meant approximating the page's look by hand in another toolkit — and looking like it.
 * What remains here is the part that genuinely is not UI: which chats exist, which one is on screen, and the
 * disposal contract.
 *
 * Every chat's page renders the whole chat list and marks its own entry ([pushChats]); a click comes back as
 * a `selectChat` message and lands in [selectById]. Switching swaps browsers, and because both pages draw
 * the same bar the swap is invisible.
 *
 * The content is switched with a [CardLayout] rather than by adding and removing components: a chat's JCEF
 * browser stays in the hierarchy for the whole life of its tab, which is the cheapest possible answer to
 * "does switching tabs disturb Chromium".
 *
 * Disposal: each tab's panel is disposed when its tab is closed, and all of them when this panel is
 * ([Disposable] — registered as the single content's disposer).
 */
internal class ChatTabsPanel : JBPanel<ChatTabsPanel>(BorderLayout()), Disposable {

    /** One chat: its stable id, its component, its title and whatever must be disposed with it. */
    internal class ChatTab(
        val id: String,
        val component: JComponent,
        var title: String,
        var tooltip: String,
        val disposer: Disposable?,
    ) {
        /** The attention badge, as a flag rather than an icon — the page decides how to draw it. */
        var attention: Boolean = false

        /**
         * When this tab last raised an attention notification, for [ClaudeToolWindowFactory]'s throttle.
         *
         * On the TAB, not in a map on the factory: a `ToolWindowFactory` is an APPLICATION-level extension —
         * one instance for every project, for the life of the IDE — so a `Map<ClaudeSession, …>` there was
         * keyed by an object holding a `Project`, and nothing cleared it on project close (the strip is
         * disposed, but no tab is "closed"). Here it dies with the tab, by construction.
         */
        var lastNotified: Long = 0L

        /**
         * What this tab is PINNED to: an agent id, a background-task id, or neither (an ordinary chat).
         *
         * Selecting a chat means "show me the chat", so [select] resets its panel to the chat's own
         * transcript — which would immediately undo a pin. This is what makes the exception explicit rather
         * than making the reset conditional on something the tab does not know.
         */
        var pinnedAgent: String? = null
        var pinnedTask: String? = null

        /**
         * True when this tab is a second VIEW of a chat, not a chat of its own.
         *
         * A pinned tab holds another `JcefChatPanel` over the SAME [ClaudeSession] (see [pin]), so everything
         * that used to be safe while one panel meant one session has to ask this first — closing it must not
         * dispose the session, or closing an agent's tab kills the chat that spawned it.
         */
        val isPinnedView: Boolean get() = pinnedAgent != null || pinnedTask != null

        /** The session this tab draws, chat or pinned view; null for a tab that is not a chat panel. */
        val session: ClaudeSession? get() = (component as? JcefChatPanel)?.session
    }

    private val cards = CardLayout()
    private val content = JPanel(cards)

    private val tabs = ArrayList<ChatTab>()
    private var selectedTab: ChatTab? = null
    private var seq = 0

    private var onClosed: (ChatTab) -> Unit = {}
    private var onSelected: (ChatTab?) -> Unit = {}

    val selected: ChatTab? get() = selectedTab

    /** The selected tab's chat panel, or null when the selected tab is not a chat (e.g. Diff History). */
    val selectedChat: JcefChatPanel? get() = selectedTab?.component as? JcefChatPanel

    /**
     * The tab-level commands — opening a chat, the Git chat, restore, fork — set by the factory, which is
     * where they are constructed.
     *
     * It is held here because this panel is what a caller OUTSIDE the tool window can already reach
     * ([ClaudeToolWindowFactory.tabsPanel]), and the composer's action buttons are exactly such a caller: they
     * are drawn inside a chat, and asking that chat's own session to make a new tab is not something a session
     * can do. Reaching them any other way means guessing at the shape of the tool window's content, which is
     * the cast that silently returned null for a whole release.
     *
     * Null until the tool window's content is built; a caller with nothing here has no tabs to talk to either.
     */
    var commands: TabSessionCommands? = null

    init {
        add(content, BorderLayout.CENTER)
    }

    /** Registers the selection/close callbacks. Called once, by the factory, right after construction. */
    fun onEvents(selected: (ChatTab?) -> Unit, closed: (ChatTab) -> Unit) {
        onSelected = selected
        onClosed = closed
    }

    /**
     * Adds a tab for [component] and returns its handle.
     *
     * [disposer] is disposed when the tab is closed — the same contract as `Content.setDisposer`, and the
     * reason a closed chat's JCEF browser and session actually go away instead of leaking.
     */
    fun add(component: JComponent, title: String, tooltip: String, disposer: Disposable?): ChatTab {
        val tab = ChatTab("chat-${seq++}", component, title, tooltip, disposer)
        tabs += tab
        content.add(component, tab.id)
        pushChats()
        return tab
    }

    /** Selects [tab], shows its component and moves the keyboard focus into it. */
    fun select(tab: ChatTab) {
        if (tab !in tabs) return
        selectedTab = tab
        tab.attention = false
        cards.show(content, tab.id)
        (tab.component as? JcefChatPanel)?.let {
            when {
                // A PINNED tab is that agent's (or task's) tab: selecting it shows what it is pinned to.
                tab.pinnedAgent != null -> it.transcript.showTranscript(tab.pinnedAgent)

                tab.pinnedTask != null -> it.transcript.showBackgroundTask(tab.pinnedTask!!)

                // Selecting a chat means "show me this chat" — including when an agent's transcript is what
                // is currently painted in it. Without this there is NO WAY BACK from an agent tab.
                else -> it.transcript.showTranscript(null)
            }
            it.focusInput()
        }
        pushChats()
        onSelected(tab)
    }

    /**
     * Opens [agentId] (or [taskId]) as a tab of its own, on the SAME session, and selects it.
     *
     * Same session on purpose: an agent is not a separate conversation, it is part of this one — it shares
     * the process, the credentials and the transcript store. What the new tab owns is a second view of it,
     * pinned so that selecting the tab always lands on that transcript. [panel] is built by the caller, which
     * is the only place that holds the `Project` a JCEF panel needs.
     *
     * Pinning the same thing twice just selects the tab that already exists; two tabs showing one agent
     * would be two things to close and no way to tell them apart.
     */
    fun pin(panel: JcefChatPanel, agentId: String?, taskId: String?, title: String): ChatTab {
        tabs.firstOrNull { it.pinnedAgent == agentId && it.pinnedTask == taskId && (agentId ?: taskId) != null }
            ?.let {
                select(it)
                return it
            }
        val tab = add(panel, title, title, panel)
        tab.pinnedAgent = agentId
        tab.pinnedTask = taskId
        select(tab)
        return tab
    }

    /** The chat panel behind tab [id], for a message that names the chat it belongs to (Workloads does). */
    fun panelOf(id: String): JcefChatPanel? =
        tabs.firstOrNull { it.id == id }?.component as? JcefChatPanel

    /**
     * The tab showing [session] — the chat's own, since it was added before any [pin]ned view of the same one.
     * Asked of the strip rather than remembered in a map that outlived it (see [ChatTab.lastNotified]).
     */
    fun tabFor(session: ClaudeSession): ChatTab? =
        tabs.firstOrNull { (it.component as? JcefChatPanel)?.session === session }

    fun selectById(id: String) {
        tabs.firstOrNull { it.id == id }?.let { select(it) }
    }

    fun closeById(id: String) {
        tabs.firstOrNull { it.id == id }?.let { close(it) }
    }

    /**
     * Closes [tab]: shows something else, removes it, fires the close callback and disposes what it carried.
     *
     * Closing a CHAT takes its pinned views with it, first: they are second panels over that chat's session
     * ([ChatTab.isPinnedView]), so leaving them behind leaves tabs painting a transcript whose session the
     * close is about to dispose. They cannot cascade further — a pinned view is never pinned to.
     *
     * **The ORDER of the three middle steps is the whole of this method, and getting it wrong is what made
     * the close button do nothing at all.** Removing the card that is currently SHOWN makes `CardLayout` pick
     * the next one itself and validate the container on the spot — which reshapes a `JBCefOsrComponent`, which
     * schedules on an `Alarm`. Reached from a close that has already disposed a browser, that alarm is gone
     * and the platform logs `Already disposed` — a Throwable, thrown out of `Container.remove`, so every line
     * after it was skipped: no `pushChats`, so the pill stayed in the bar, so the button looked dead. The tab
     * really had been closed; the only thing missing was every consequence of it.
     *
     * So: move the display to a survivor FIRST, while every component involved is still alive; only then
     * remove the dead card, which is no longer the one on screen and therefore no longer something the layout
     * has to make a decision about; and dispose last, once the container has settled.
     */
    fun close(tab: ChatTab) {
        if (!tabs.remove(tab)) return
        if (!tab.isPinnedView) {
            val session = tab.session
            if (session != null) {
                tabs.filter { it.isPinnedView && it.session === session }.forEach { close(it) }
            }
        }
        onClosed(tab)
        if (selectedTab === tab) {
            selectedTab = null
            // Never leave the area blank: show whatever is left, BEFORE the card goes.
            tabs.firstOrNull()?.let { select(it) } ?: pushChats()
        } else {
            pushChats()
        }
        content.remove(tab.component)
        tab.disposer?.let { Disposer.dispose(it) }
        content.revalidate()
        content.repaint()
        replaceLastChat()
    }

    /**
     * Closing the LAST chat opens a fresh one, so the tool window is never empty.
     *
     * "Never leave the area blank" above only ever meant "show another tab", and with none left it fell
     * through to a `CardLayout` with no card: an empty grey panel, no chats, no composer, and no control
     * anywhere to make one — the whole plugin looked broken, and the only way out was closing and reopening
     * the tool window. Closing your last conversation is a reasonable thing to do; being left with nothing is
     * not what it means.
     *
     * Only for real chats. A pinned view closing is a view closing, and the chat behind it is still open —
     * counting them here would make the last VIEW of a chat conjure a second conversation.
     */
    private fun replaceLastChat() {
        if (tabs.any { !it.isPinnedView }) return
        commands?.newChat()
    }

    fun all(): List<ChatTab> = tabs.toList()

    fun relabel(tab: ChatTab, title: String, tooltip: String) {
        tab.title = title
        tab.tooltip = tooltip
        pushChats()
    }

    /** The attention badge. Ignored for the tab already on screen — it has nothing to catch up on. */
    fun badge(tab: ChatTab, attention: Boolean) {
        if (tab === selectedTab) return
        tab.attention = attention
        pushChats()
    }

    /**
     * Pushes the chat list into EVERY chat's page.
     *
     * All of them, not just the selected one: a page that is off screen now is the page that will be on
     * screen the moment the user switches to it, and re-rendering it only then would show a stale bar for a
     * frame — or, if the switch is what changed the list, the wrong bar entirely.
     */
    private fun pushChats() {
        val list = chatList()
        tabs.forEach { (it.component as? JcefChatPanel)?.agentTabs?.setChats(list) }
    }

    /**
     * The chat list as the bar draws it — **asked for, not remembered**.
     *
     * This is the strip, so this is the answer; every page that draws a tab bar gets it from here. It used to
     * exist only as a push, with each panel caching the last one it received, and a cache is a second copy
     * that can be empty when the original is not: a panel built before the first push, or one whose push was
     * dropped, rendered a bar with no chats in it — for good, because nothing pushes again until the LIST
     * changes, and on a project with one restored chat it never does. What that looks like is a tool window
     * with no tabs at all, which is how it was reported.
     *
     * The push stays: it is what makes the OTHER pages repaint when this one changes something. What changed
     * is that a page can also just ask, which is what it does when it comes up ([ChatAgentTabs.render]).
     */
    fun chatList(): List<JcefTabsData.Chat> = tabs.map {
        JcefTabsData.Chat(it.id, it.title, it === selectedTab, it.attention, it.pinnedAgent)
    }

    /**
     * Every open chat with the session behind it, for the dashboard's Workloads diagram.
     *
     * Workloads is about what is RUNNING, and what is running does not belong to the chat you happen to be
     * looking at: agents and background tasks keep going in the other tabs, and a view that showed only the
     * selected one answered "what is running?" with a fraction of the truth. The bar's own popup is the
     * per-chat view; this is the whole picture.
     *
     * Ordered as the tabs are, so the diagram reads in the same order as the bar above it.
     *
     * EVERY tab, pinned ones included: the tab bar needs each tab's tree to answer its own ⋮. The diagram
     * is the one that must not draw the same chat twice — [pin] adds a second tab over the SAME panel, a
     * VIEW of one agent rather than another workload — and that is deduplicated by session where it is
     * drawn ([JcefSessionData.sessionJson]).
     */
    fun workloads(): List<JcefSessionData.Workload> = tabs.mapNotNull { tab ->
        (tab.component as? JcefChatPanel)?.let { panel ->
            JcefSessionData.Workload(tab.id, tab.title, tab === selectedTab, panel.session)
        }
    }

    override fun dispose() {
        tabs.forEach { tab -> tab.disposer?.let { Disposer.dispose(it) } }
    }
}
