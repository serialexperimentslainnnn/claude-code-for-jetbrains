package dev.lain.claudejb.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.TimedDeadzone
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The chat tab strip, owned by the plugin instead of by the tool window.
 *
 * **Why not the tool window's own tabs.** The platform lays tool-window content tabs out with
 * `TabContentLayout`, which does not scroll: once the labels no longer fit it simply stops drawing the
 * earliest ones and buries them behind a `⌄` popup. With a handful of chats open the first ones vanish, which
 * is the bug this class exists to fix. `JBTabs` — the same widget the editor uses — does scroll: its
 * `createRowLayout()` returns a `ScrollableSingleRowLayout` whenever the tab list is single-row (verified
 * against the platform, IU-262). So the tool window now holds ONE content, and every chat is a [TabInfo] in
 * here.
 *
 * The surface is deliberately the small subset of `ContentManager` the tool window factory actually used
 * (add / select / selected / list / listen), so the factory's logic — restore, attention badges, rename,
 * fork — reads exactly as it did before and only the object it talks to changed.
 *
 * Disposal: each tab's panel is disposed when its tab is closed, and all of them when this panel is
 * ([Disposable] — registered as the single content's disposer).
 */
internal class ChatTabsPanel(project: Project, parent: Disposable) :
    JBPanel<ChatTabsPanel>(BorderLayout()), Disposable {

    private val tabs: JBTabs = JBTabsFactory.createTabs(project, parent)

    /** What to run when a tab is closed by the user — the factory drops the session there. */
    private var onClosed: (TabInfo) -> Unit = {}

    private var onSelected: (TabInfo?) -> Unit = {}

    val selected: TabInfo? get() = tabs.selectedInfo

    /** The selected tab's chat panel, or null when the selected tab is not a chat (e.g. Diff History). */
    val selectedChat: JcefChatPanel? get() = tabs.selectedInfo?.component as? JcefChatPanel

    init {
        // NB no Disposer.register here: this panel is the single content's disposer
        // (`Content.setDisposer`), and registering it under the tool window as well would give one object two
        // parents. [parent] is only what the tab widget itself is tied to.
        add(tabs.component, BorderLayout.CENTER)
        tabs.presentation.setSingleRow(true) // the scrolling layout; see the class doc
        // The close button, ALWAYS drawn. `JBTabs` hides per-tab actions until the pointer is over the label by
        // default, which on a chat strip reads as "the tabs have no close button" — you have to already know it
        // is there to find it. The editor's own tabs show theirs unconditionally; so do these.
        tabs.presentation.setTabLabelActionsAutoHide(false)
        tabs.presentation.setTabLabelActionsMouseDeadzone(TimedDeadzone.NULL)
        tabs.presentation.setTabDraggingEnabled(true)
        // Compression OFF so a full strip cannot take the button away: `TabLabelLayout` drops the EAST component
        // — which IS the action panel — whenever it has to fit a label into less than its preferred width
        // (`layoutCompressible` bounds it to 0×0). Without compression the single-row layout scrolls instead,
        // which is the whole reason this class uses `JBTabs`; see the class doc.
        tabs.presentation.setSupportsCompression(false)
        tabs.addListener(
            object : TabsListener {
                override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                    // A selected chat has no badge to show, and the keyboard focus belongs in its composer.
                    // The ContentManager used to do both as part of the selection; here it is explicit.
                    newSelection?.setIcon(null)
                    (newSelection?.component as? JcefChatPanel)?.focusInput()
                    onSelected(newSelection)
                }
            },
        )
        // Middle-click closes, the way every other tab strip in the IDE behaves.
        tabs.addTabMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (e.button != java.awt.event.MouseEvent.BUTTON2) return
                    tabs.findInfo(e)?.let { close(it) }
                }
            },
        )
    }

    /** Registers the selection/close callbacks. Called once, by the factory, right after construction. */
    fun onEvents(selected: (TabInfo?) -> Unit, closed: (TabInfo) -> Unit) {
        onSelected = selected
        onClosed = closed
    }

    /**
     * Adds a tab for [component] and returns its handle.
     *
     * [disposer] is disposed when the tab is closed — the same contract as `Content.setDisposer`, and the
     * reason a closed chat's JCEF browser and session actually go away instead of leaking.
     */
    fun add(component: JComponent, title: String, tooltip: String, disposer: Disposable?): TabInfo {
        val info = TabInfo(component).setText(title)
        info.setObject(disposer)
        info.setTabLabelActions(DefaultActionGroup(CloseTabAction(info)), TAB_ACTION_PLACE)
        tabs.addTab(info)
        applyTooltip(info, tooltip)
        return info
    }

    /** Selects [info], moving the keyboard focus into it (the selection listener does the focus transfer). */
    fun select(info: TabInfo) {
        tabs.select(info, true)
    }

    /** Closes [info]: removes the tab, fires the close callback and disposes whatever it carried. */
    fun close(info: TabInfo) {
        tabs.removeTab(info)
        onClosed(info)
        (info.`object` as? Disposable)?.let { Disposer.dispose(it) }
    }

    fun all(): List<TabInfo> = tabs.tabs

    fun relabel(info: TabInfo, title: String, tooltip: String) {
        info.setText(title)
        applyTooltip(info, tooltip)
    }

    /**
     * The full title, on the tab's own label rather than through `TabInfo.setTooltipText`.
     *
     * That setter has two overloads and neither is usable across the supported range: the `String` one is
     * DEPRECATED from 262, and the `HtmlChunk` one does not exist at the 251 floor — calling it would be a
     * `NoSuchMethodError` on the oldest IDEs we claim to support. `TabLabel` falls through to
     * `JPanel.getToolTipText`, so setting the label's own tooltip is the same result by a supported route.
     */
    private fun applyTooltip(info: TabInfo, tooltip: String) {
        (tabs.getTabLabel(info) as? JComponent)?.toolTipText = tooltip
    }

    /** The attention badge. Ignored for the tab that is already on screen — it has nothing to catch up on. */
    fun badge(info: TabInfo, icon: Icon?) {
        if (info !== tabs.selectedInfo) info.setIcon(icon)
    }

    override fun dispose() {
        tabs.tabs.forEach { info -> (info.`object` as? Disposable)?.let { Disposer.dispose(it) } }
    }

    private inner class CloseTabAction(private val info: TabInfo) :
        AnAction("Close Chat", "Close this conversation", AllIcons.Actions.Close) {
        override fun actionPerformed(e: AnActionEvent) = close(info)

        /**
         * EDT, and NOT because this action is slow: `ActionPanel` — the thing that turns a tab's action group
         * into the little button — builds its buttons through a traverser that
         * `filter { it.actionUpdateThread == ActionUpdateThread.EDT }`. `AnAction` answers `BGT` by default, so
         * an action that does not say this is dropped on the floor and the tab is simply drawn without a close
         * button, at any width, hovered or not. The platform's own editor-tab `CloseTab` declares it too.
         */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private companion object {
        /** Action place for the per-tab close button; any stable, plugin-owned string will do. */
        const val TAB_ACTION_PLACE = "ClaudeChatTabs"
    }
}
