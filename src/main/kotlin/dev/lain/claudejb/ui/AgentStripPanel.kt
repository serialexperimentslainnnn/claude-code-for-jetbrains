package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.TimedDeadzone
import dev.lain.claudejb.session.AgentNode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.Timer

/**
 * One row of agent tabs — the `Agents` strip under the chats, or the `Subagents` strip under it.
 *
 * **Header only.** Unlike the chat strip, these tabs own no content: the transcript is painted by the chat's
 * single JCEF browser, which simply switches which transcript it shows. That is what keeps a session with
 * eighty agents affordable — one Chromium per chat, not one per agent. So every [TabInfo] carries an empty
 * placeholder component and the panel reports only the height of the tab labels.
 *
 * Selecting a tab reports the agent id; closing one reports it too, and the caller persists that (a closed
 * tab stays closed across restarts, and the transcript card is the way back).
 *
 * **A row appears only when it has something in it.** Standing rows on a chat that has never spawned an
 * agent are two lines of chrome asking a question nobody asked; the design started with them always visible
 * to keep the layout still, and seeing it proved the opposite — an empty `Agents` row above every fresh chat
 * reads as broken UI. The header carries the tree connector of its depth, so when a row does appear it says
 * what it hangs off.
 */
internal class AgentStripPanel(
    project: Project,
    parent: Disposable,
    /** `Agents` / `Subagents` / `Background tasks` — the row's own name, drawn at the left. */
    private val title: String,
    /** How deep this row hangs: 1 = off the chat, 2 = off the selected agent. Drawn as `|_` connectors. */
    private val depth: Int = 1,
) : JBPanel<AgentStripPanel>(BorderLayout()) {

    private val tabs: JBTabs = JBTabsFactory.createTabs(project, parent)

    /** agentId → its tab, so the caller can select, relabel, blink or close one by id. */
    private val tabOf = LinkedHashMap<String, TabInfo>()

    private var onSelected: (String?) -> Unit = {}
    private var onClosed: (String) -> Unit = {}

    /** Suppresses the selection callback while the strip is being rebuilt from a scan. */
    private var rebuilding = false

    /** The row's own header, whose text carries the tree branch (`├─ Agents`, `│  └─ Subagents`). */
    private val header = JBLabel(title).apply { border = JBUI.Borders.empty(0, 8, 0, 6) }

    init {
        add(header, BorderLayout.WEST)
        add(tabs.component, BorderLayout.CENTER)
        isVisible = false // nothing in it yet; `render` decides
        // Same presentation as the chat strip, for the "same format as the chat tabs" the design asks for:
        // a single scrolling row whose close buttons are always drawn.
        tabs.presentation.setSingleRow(true)
        tabs.presentation.setTabLabelActionsAutoHide(false)
        tabs.presentation.setTabLabelActionsMouseDeadzone(TimedDeadzone.NULL)
        tabs.presentation.setSupportsCompression(false)
        tabs.addListener(
            object : TabsListener {
                override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                    if (rebuilding) return
                    newSelection?.setIcon(null)
                    onSelected(agentIdOf(newSelection))
                }
            },
        )
        tabs.addTabMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (e.button == java.awt.event.MouseEvent.BUTTON2) tabs.findInfo(e)?.let { closeTab(it) }
                }
            },
        )
    }

    fun onEvents(selected: (String?) -> Unit, closed: (String) -> Unit) {
        onSelected = selected
        onClosed = closed
    }

    /** The agent whose tab is selected, or null when the strip is empty. */
    val selectedAgentId: String? get() = agentIdOf(tabs.selectedInfo)

    /** One tab of a strip: an agent, or a background task. Both are "things that hang off this row". */
    data class Item(val id: String, val label: String, val tooltip: String, val closable: Boolean = true)

    /**
     * Rebuilds the strip to show exactly [items], keeping the selection when that item is still there, and
     * **hides the whole row when there is nothing in it**.
     *
     * Rebuilding rather than diffing is deliberate: a scan can add, remove and re-parent agents at once on a
     * heavy session, and a strip of at most a few dozen labels is cheap to lay out. What must NOT be lost is
     * the user's selection, so it is restored explicitly and the listener is muted meanwhile — otherwise
     * every scan would look like the user had clicked a tab and would repaint the transcript underneath.
     */
    fun render(items: List<Item>) {
        val keepSelected = selectedAgentId
        rebuilding = true
        try {
            tabs.removeAllTabs()
            tabOf.clear()
            for (item in items) {
                val info = TabInfo(JPanel()).setText(item.label).setObject(item.id)
                if (item.closable) {
                    info.setTabLabelActions(DefaultActionGroup(CloseAgentTabAction(info)), TAB_ACTION_PLACE)
                }
                tabs.addTab(info)
                (tabs.getTabLabel(info) as? javax.swing.JComponent)?.toolTipText = item.tooltip
                tabOf[item.id] = info
            }
            tabOf[keepSelected]?.let { tabs.select(it, false) }
        } finally {
            rebuilding = false
        }
        isVisible = items.isNotEmpty()
        revalidate()
        repaint()
    }

    /**
     * Sets the tree branch drawn before the row's name, e.g. `├─ ` or `│  └─ `.
     *
     * Computed by the owner rather than fixed here, because a branch depends on which rows are **currently
     * visible** — the last one drawn ends the tree with `└─`, and rows come and go as agents spawn and
     * finish. A row that decided its own branch would draw a `├─` pointing at nothing.
     */
    fun setBranch(branch: String) {
        header.text = branch + title
    }

    /** Convenience for the agent rows: [Item]s built from the registry's nodes. */
    fun renderAgents(nodes: List<AgentNode>, relativeDepth: Int = 1) = render(
        nodes.map {
            Item(it.agentId, AgentTabLabels.tab(it, relativeDepth), AgentTabLabels.tooltip(it))
        },
    )

    /** Selects the tab of [agentId], transferring focus like a manual click. No-op when it is not there. */
    fun select(agentId: String) {
        tabOf[agentId]?.let { tabs.select(it, true) }
    }

    fun has(agentId: String): Boolean = agentId in tabOf

    /**
     * Two soft orange pulses on a newly-spawned agent's tab, then back to normal.
     *
     * The point is peripheral vision: on a session spawning agents constantly, a permanent colour would be
     * noise and a notification per agent would be a storm (they are batched elsewhere). Two pulses say
     * "something appeared here" and then get out of the way.
     */
    fun blink(agentId: String) {
        val info = tabOf[agentId] ?: return
        var remaining = BLINK_PULSES * 2
        val timer = Timer(BLINK_INTERVAL_MS, null)
        timer.addActionListener {
            info.setTabColor(if (remaining % 2 == 0) BLINK_COLOR else null)
            remaining--
            if (remaining < 0) {
                info.setTabColor(null)
                timer.stop()
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    private fun closeTab(info: TabInfo) {
        val id = agentIdOf(info) ?: return
        tabs.removeTab(info)
        tabOf.remove(id)
        onClosed(id)
    }

    private fun agentIdOf(info: TabInfo?): String? = info?.`object` as? String

    /** Header height only: these tabs have no content of their own (see the class doc). */
    override fun getPreferredSize(): Dimension {
        val height = tabs.selectedInfo?.let { tabs.getTabLabel(it)?.preferredSize?.height }
            ?: JBUI.scale(DEFAULT_STRIP_HEIGHT)
        return Dimension(super.getPreferredSize().width, height + JBUI.scale(STRIP_PADDING))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private inner class CloseAgentTabAction(private val info: TabInfo) :
        AnAction("Close ${title.dropLast(1)} Tab", "Close this tab — the transcript stays on disk", com.intellij.icons.AllIcons.Actions.Close) {
        override fun actionPerformed(e: AnActionEvent) = closeTab(info)

        /** EDT for the same reason the chat strip's close action declares it: BGT actions are dropped. */
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private companion object {
        const val TAB_ACTION_PLACE = "ClaudeAgentTabs"
        const val DEFAULT_STRIP_HEIGHT = 26
        const val STRIP_PADDING = 4
        const val BLINK_PULSES = 2
        const val BLINK_INTERVAL_MS = 260

        /** Soft orange — visible against both light and dark themes without shouting. */
        val BLINK_COLOR: Color = Color(0xE8, 0x8C, 0x30)
    }
}
