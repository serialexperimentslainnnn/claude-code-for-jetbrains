package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
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
 * **ONE TAB PER SESSION, and that is the invariant everything below rests on.** A tab is a chat; an agent, a
 * subagent and a background task are *transcripts of* a chat, switched inside that chat's one browser
 * (`app-tabs.js` → `selectAgent`), and none of them is ever a tab of its own. So a tab is the only thing
 * holding its session, [close] disposes it unconditionally, and there is no arrangement in which closing what
 * the user is reading can kill a process something else is still painting. It was possible once: a second
 * `JcefChatPanel` over the SAME session could be added as a tab, and closing that tab disposed the session of
 * the chat that had spawned the agent — leaving that chat's own tab open over a dead process and dropping it
 * from the restorable set. The guarantee is structural rather than careful, and `ToolWindowWiringContractTest`
 * pins the two facts it is made of: one construction site for [JcefChatPanel], one caller of [add].
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
         * The session this tab draws; null for a tab that is not a chat panel.
         *
         * No other tab holds it — see the class doc. That is what lets [close] dispose without asking.
         */
        val session: ClaudeSession? get() = (component as? JcefChatPanel)?.session
    }

    private val cards = CardLayout()
    private val content = JPanel(cards)

    private val tabs = ArrayList<ChatTab>()
    private var selectedTab: ChatTab? = null
    private var seq = 0

    private var onClosed: (ChatTab) -> Unit = {}
    private var onSelected: (ChatTab?) -> Unit = {}

    /**
     * True once [dispose] has run, so a deferred [replaceLastChat] does not open a chat into a dead strip.
     *
     * A flag rather than `Disposer.isDisposed(this)`: this panel is registered as its `Content`'s disposer by
     * the factory, and an instance built outside a tool window is in no `Disposer` tree at all — where that
     * question answers "not disposed" forever, including after [dispose] has already run.
     */
    private var disposed = false

    val selected: ChatTab? get() = selectedTab

    /** The selected tab's chat panel, or null when the selected tab is not a chat. */
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
     *
     * **One call site, and that is a contract** ([ClaudeToolWindowFactory.openChat]). A second one is how a
     * second tab over a live session comes back, and with it the close that kills somebody else's process —
     * see the class doc.
     */
    fun add(component: JComponent, title: String, tooltip: String, disposer: Disposable?): ChatTab {
        val tab = ChatTab("chat-${seq++}", component, title, tooltip, disposer)
        tabs += tab
        content.add(component, tab.id)
        pushChats()
        return tab
    }

    /**
     * Selects [tab], shows its component and moves the keyboard focus into it.
     *
     * **The bar is pushed BEFORE the card is shown, and that ordering is the ghost tab.** Each chat's page
     * draws the whole bar from the last list it was pushed, so a page that is made visible first is visible
     * drawing the list it had *before* this call — which on the close path still contains the chat being
     * closed, unselected, exactly as it was reported. Pushing first gives the page the whole duration of the
     * switch to apply the new list instead of starting from the stale one.
     */
    fun select(tab: ChatTab) {
        if (tab !in tabs) return
        selectedTab = tab
        tab.attention = false
        pushChats()
        cards.show(content, tab.id)
        (tab.component as? JcefChatPanel)?.let { panel ->
            // Selecting a chat means "show me this chat" — including when an agent's transcript is what is
            // currently painted in it. Without this there is NO WAY BACK from an agent's subtab.
            //
            // BOTH CALLS REACH A BROWSER, and a throw from either used to take the rest of this method with it:
            // `onSelected` never ran, so the selection was half applied — the card had moved, the listener that
            // settles `active` and the dashboard had not — and the only visible result was a tab that did not
            // seem to respond. An exception inside a bridge callback reaches no `error` event and no log either,
            // so it was invisible twice over. The selection is not abandoned for a page that cannot draw yet:
            // the failure is recorded and the rest of the switch completes.
            runCatching { panel.transcript.showTranscript(null) }
                .onFailure { LOG.warn("Claude Code tabs: showing '${tab.title}' failed to reset its transcript", it) }
            runCatching { panel.focusInput() }
                .onFailure { LOG.warn("Claude Code tabs: showing '${tab.title}' failed to take focus", it) }
        }
        onSelected(tab)
    }

    /** The chat panel behind tab [id], for a message that names the chat it belongs to (Workloads does). */
    fun panelOf(id: String): JcefChatPanel? =
        tabs.firstOrNull { it.id == id }?.component as? JcefChatPanel

    /**
     * The tab showing [session] — there is exactly one (see the class doc).
     * Asked of the strip rather than remembered in a map that outlived it (see [ChatTab.lastNotified]).
     */
    fun tabFor(session: ClaudeSession): ChatTab? =
        tabs.firstOrNull { (it.component as? JcefChatPanel)?.session === session }

    /**
     * A click on a chat pill, by the id the page was drawn with.
     *
     * **A miss is LOGGED, and that is the whole point of this shape.** The page draws whatever list it was last
     * pushed and hands the id back verbatim, so a pill whose tab is gone sends an id that resolves to nothing —
     * and a bare `?.let` then does nothing at all, with no exception and no trace: a tab you can see, click, and
     * get no response from. That is exactly how the ghost-tab report reads from the user's chair, and it is
     * indistinguishable from twenty other causes without this line. Naming the id and what DOES exist is what
     * makes the next report answerable from a log instead of from a reproduction.
     */
    fun selectById(id: String) {
        val tab = tabs.firstOrNull { it.id == id }
        if (tab == null) {
            LOG.warn(unknownTab("selectChat", id))
            return
        }
        select(tab)
    }

    /** As [selectById]: a close aimed at a tab that is already gone is silent otherwise. */
    fun closeById(id: String) {
        val tab = tabs.firstOrNull { it.id == id }
        if (tab == null) {
            LOG.warn(unknownTab("closeChat", id))
            return
        }
        close(tab)
    }

    /** What a gesture aimed at a tab that does not exist should say: the id asked for, and the ids that exist. */
    private fun unknownTab(gesture: String, id: String): String =
        "Claude Code tabs: $gesture named '$id', which is not an open tab — the page is drawing a list this " +
            "strip no longer has. Open now: ${tabs.map { it.id to it.title }}"

    /**
     * Closes [tab]: shows something else, removes it, fires the close callback and disposes what it carried.
     *
     * The callback disposes the tab's `claude` process, and it may do that unconditionally because no other
     * tab holds this session — the class doc says why that is structural rather than a hope.
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
     *
     * **What is drawn is settled before anything expensive is asked for, and that is the second ordering
     * rule.** [onClosed] used to be the first line, and it disposes the tab's `claude` — which used to kill a
     * process tree on the EDT (see [dev.lain.claudejb.process.ClaudeProcess.terminate]). So the push that
     * takes the pill off the bar was emitted *behind* seconds of blocking I/O, in the same event, and the
     * browser teardown came after it: the press produced nothing, the closed chat's pill stayed on screen,
     * and the whole thing then happened at once. Both halves are fixed — the kill left the EDT, and the
     * consequences the user is waiting to see are now sequenced in front of the teardown rather than behind
     * it. Removing the tab from [tabs] stays the first line either way: it is what makes this call idempotent
     * and what everything below reads.
     */
    fun close(tab: ChatTab) {
        if (!tabs.remove(tab)) return
        if (selectedTab === tab) {
            selectedTab = null
            // Never leave the area blank: show whatever is left, BEFORE the card goes.
            tabs.firstOrNull()?.let { select(it) } ?: pushChats()
        } else {
            pushChats()
        }
        // The session, once the bar and the card already agree the tab is gone. It also settles `active` in
        // the right order: the survivor has been selected by now, so removing the closed session no longer
        // has to guess which chat inherits it.
        onClosed(tab)
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
     * With no [commands] there is nothing to open a chat WITH, and the strip has no other way to make one. It
     * says so instead of returning quietly: the empty tool window this method exists to prevent is exactly
     * what the user gets, and a silent `?.` leaves no trace anywhere of why. The factory publishes the field
     * before it opens anything ([ClaudeToolWindowFactory.createToolWindowContent]), so reaching this branch in
     * a running IDE means that wiring is broken rather than merely late.
     *
     * **The replacement is opened on the NEXT event, not in the middle of the close.** Opening a chat builds a
     * whole `JBCefBrowser` and hands it the document to assemble, and that cannot leave the EDT — Swing and
     * JCEF both require it. What it can leave is *this* event: run inline, closing your only chat meant one
     * EDT event that tore a Chromium down and stood another one up before a single frame could be painted, so
     * the press appeared to do nothing at all until the whole sequence was over. Deferred, the close paints
     * first — the pill goes, the card goes — and the fresh chat arrives behind it. The cost is one event with
     * no card in the [CardLayout]; the thing this method exists to prevent is being left with none *for good*.
     *
     * Re-checked when it runs, not only when it is queued: [dispose] can have happened (the tool window
     * closed), and a chat can have been opened in between by anything else, and either one turns the
     * replacement into a chat nobody asked for.
     */
    private fun replaceLastChat() {
        if (tabs.isNotEmpty()) return
        val open = commands
        if (open == null) {
            LOG.warn(
                "The last chat was closed with no tab commands wired, so no replacement was opened: the tool " +
                    "window is left with no chats and no control to create one.",
            )
            return
        }
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed && tabs.isEmpty()) open.newChat() },
            ModalityState.defaultModalityState(),
        )
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
        JcefTabsData.Chat(it.id, it.title, it === selectedTab, it.attention)
    }

    /**
     * Every open chat with the session behind it, for the dashboard's Workloads diagram.
     *
     * Workloads is about what is RUNNING, and what is running does not belong to the chat you happen to be
     * looking at: agents and background tasks keep going in the other tabs, and a view that showed only the
     * selected one answered "what is running?" with a fraction of the truth. The bar's second row is the
     * per-chat view; this is the whole picture.
     *
     * Ordered as the tabs are, so the diagram reads in the same order as the bar above it. One entry per
     * chat, and therefore one per session ([ChatTab.session]).
     */
    fun workloads(): List<JcefSessionData.Workload> = tabs.mapNotNull { tab ->
        (tab.component as? JcefChatPanel)?.let { panel ->
            JcefSessionData.Workload(tab.id, tab.title, tab === selectedTab, panel.session)
        }
    }

    override fun dispose() {
        disposed = true
        tabs.forEach { tab -> tab.disposer?.let { Disposer.dispose(it) } }
    }

    private companion object {
        private val LOG = logger<ChatTabsPanel>()
    }
}
