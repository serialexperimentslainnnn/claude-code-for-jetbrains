package dev.lain.claudejb.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import dev.lain.claudejb.protocol.BackgroundTaskInfo
import dev.lain.claudejb.session.AgentRegistry
import java.awt.BorderLayout
import javax.swing.BoxLayout

/**
 * The rows under a chat's tab, as a **stack of levels** rather than a fixed pair of rows.
 *
 * Drilling down is the whole point: a chat spawns agents, an agent spawns agents of its own, and so can
 * each of those. So every level you select opens the level below it —
 *
 * ```
 * Chat 1
 * ├─ Agents               [ A ][ B ]        ← agents of the chat
 * ├─ Background tasks     [ npm run dev ]   ← background tasks of the chat
 * │  ├─ Subagents         [ A1 ][ A2 ]      ← agents of A, because A is selected
 * │  └─ Background tasks  [ tail -f log ]   ← background tasks of A
 * │     └─ Subagents      [ A1a ]           ← agents of A1, because A1 is selected
 * ```
 *
 * — and selecting elsewhere collapses everything below it. A fixed "Agents + Subagents" pair could only ever
 * show two levels of a tree the protocol does not bound, and it put background tasks nowhere except the top.
 *
 * **A row is drawn only when it has something in it**, so the stack is exactly as tall as the work is deep.
 * Standing empty rows turned every fresh chat into bars asking a question nobody asked.
 */
internal class AgentTabsPanel(
    private val project: Project,
    private val parent: Disposable,
) : JBPanel<AgentTabsPanel>(BorderLayout()) {

    /** One level of the drill-down: the agents hanging off [parentId], and the background tasks that do. */
    private class Level(
        val parentId: String?,
        val agents: AgentStripPanel,
        val background: AgentStripPanel,
    )

    private val rows = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val levels = ArrayList<Level>()

    /** Told which agent's transcript to show — null means the chat's own. */
    var onShowTranscript: (String?) -> Unit = {}

    /** Told that the user closed an agent's tab, so the close can be remembered across restarts. */
    var onTabClosed: (String) -> Unit = {}

    private var registry: AgentRegistry? = null
    private var tasks: List<BackgroundTaskInfo> = emptyList()
    private var ownerOfTask: (String) -> String? = { null }
    private var hidden: Set<String> = emptySet()

    init {
        add(rows, BorderLayout.CENTER)
    }

    /**
     * Re-renders the stack.
     *
     * [hiddenAgents] are tabs the user closed — hidden, not deleted: the transcript is the binary's file and
     * the card in the main transcript reopens it. [ownerOf] maps a background task to the agent running it,
     * when that is knowable at all; `background_tasks_changed` carries no parent, so it often is not, and
     * those tasks stay at the chat's level rather than being guessed into someone's row.
     */
    fun render(
        registry: AgentRegistry,
        backgroundTasks: List<BackgroundTaskInfo>,
        hiddenAgents: Set<String> = emptySet(),
        ownerOf: (String) -> String? = { null },
    ) {
        this.registry = registry
        this.tasks = backgroundTasks
        this.ownerOfTask = ownerOf
        this.hidden = hiddenAgents
        if (levels.isEmpty()) levels += newLevel(null)
        // A level whose agent is gone (finished and closed, or never ours) takes its descendants with it.
        while (levels.size > 1 && levels.last().parentId?.let { registry.nodes.containsKey(it) } == false) {
            dropLevelsBelow(levels.size - 2)
        }
        levels.forEach(::fill)
        drawBranches()
    }

    /** Opens (or re-selects) [agentId]'s tab, expanding the levels needed to reach it. */
    fun reveal(agentId: String) {
        val reg = registry ?: return
        val chain = ancestryOf(agentId, reg) ?: return
        // Walk down from the chat, selecting each ancestor so its level exists before reaching for the next.
        chain.forEachIndexed { index, id ->
            val level = levels.getOrNull(index) ?: return
            level.agents.select(id)
            if (index < chain.lastIndex) openLevelFor(index, id)
        }
    }

    /** Two orange pulses on whichever level's row carries [agentId]. */
    fun blink(agentId: String) {
        levels.firstOrNull { it.agents.has(agentId) }?.agents?.blink(agentId)
    }

    // ── levels ───────────────────────────────────────────────────────────────────────────────────────────

    private fun newLevel(parentId: String?): Level {
        // The first level's agents hang off the chat, so it is "Agents"; every level below hangs off an
        // agent, so it is "Subagents" — the word the user reads should say what the row is relative to.
        val agentsRow = AgentStripPanel(project, parent, if (parentId == null) "Agents" else "Subagents")
        val backgroundRow = AgentStripPanel(project, parent, "Background tasks")
        val level = Level(parentId, agentsRow, backgroundRow)
        agentsRow.onEvents(
            selected = { id ->
                val index = levels.indexOf(level)
                if (id == null) {
                    dropLevelsBelow(index)
                } else {
                    openLevelFor(index, id)
                }
                onShowTranscript(id ?: level.parentId)
            },
            closed = { id ->
                onTabClosed(id)
                dropLevelsBelow(levels.indexOf(level))
                onShowTranscript(level.parentId)
            },
        )
        // A background task has no transcript of its own, so its tab is a POINTER: it shows the transcript of
        // whoever runs it — the owning agent when the binary let us work that out, else this level's owner.
        backgroundRow.onEvents(
            selected = { taskId -> onShowTranscript(taskId?.let(ownerOfTask) ?: level.parentId) },
            closed = { },
        )
        rows.add(agentsRow)
        rows.add(backgroundRow)
        return level
    }

    /** Ensures the level below [index] exists and belongs to [agentId], dropping whatever was there. */
    private fun openLevelFor(index: Int, agentId: String) {
        if (levels.getOrNull(index + 1)?.parentId == agentId) {
            fill(levels[index + 1])
            drawBranches()
            return
        }
        dropLevelsBelow(index)
        val level = newLevel(agentId)
        levels += level
        fill(level)
        drawBranches()
    }

    /** Removes every level deeper than [index] — selecting elsewhere collapses the drill-down below it. */
    private fun dropLevelsBelow(index: Int) {
        while (levels.size > index + 1) {
            val dropped = levels.removeAt(levels.size - 1)
            rows.remove(dropped.agents)
            rows.remove(dropped.background)
        }
        rows.revalidate()
        rows.repaint()
    }

    private fun fill(level: Level) {
        val reg = registry ?: return
        level.agents.renderAgents(reg.children(level.parentId).filterNot { it.agentId in hidden })
        val mine = tasks.filter { ownerOfTask(it.taskId) == level.parentId }
        level.background.render(
            mine.map {
                AgentStripPanel.Item(
                    id = it.taskId,
                    label = it.description.ifBlank { it.taskType },
                    tooltip = "${it.description} · ${it.taskType}",
                    // Not closable: the plugin does not own a background task's lifetime. Stopping one is a
                    // deliberate act with its own button in the dashboard, not a tab close.
                    closable = false,
                )
            },
        )
    }

    /**
     * Draws the visible rows the way `tree` draws a directory: `├─` while more rows follow at that level,
     * `└─` for the last, and `│` continuing the trunk past every level already opened.
     *
     * Recomputed on every render because it depends on which rows are visible **right now** — rows appear
     * and vanish as agents spawn and finish, and a `├─` pointing at a row that is no longer drawn is worse
     * than no tree at all.
     */
    private fun drawBranches() {
        val visible = levels.flatMap { listOf(it.agents, it.background) }.filter { it.isVisible }
        visible.forEachIndexed { i, row ->
            val depth = levels.indexOfFirst { it.agents === row || it.background === row }
            val connector = if (i == visible.lastIndex) LAST else FORK
            row.setBranch(TRUNK.repeat(depth) + connector)
        }
    }

    /** The chain of agent ids from the chat down to [agentId], or null when it is not in the tree. */
    private fun ancestryOf(agentId: String, reg: AgentRegistry): List<String>? {
        val chain = ArrayDeque<String>()
        val seen = HashSet<String>()
        var current: String? = agentId
        while (current != null && seen.add(current)) {
            val node = reg.nodes[current] ?: return null
            chain.addFirst(node.agentId)
            current = node.parentAgentId
        }
        return chain.toList()
    }

    private companion object {
        /** `tree`'s own glyphs: a fork, a last child, and the trunk that passes an opened level. */
        const val FORK = "├─ "
        const val LAST = "└─ "
        const val TRUNK = "│  "
    }
}
