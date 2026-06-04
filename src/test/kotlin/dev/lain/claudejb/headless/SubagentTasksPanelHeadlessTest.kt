package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.protocol.TaskProgressInfo
import dev.lain.claudejb.protocol.TaskUsage
import dev.lain.claudejb.ui.SubagentTasksPanel

/**
 * Headless: [SubagentTasksPanel] builds one card per live subagent from plain [TaskProgressInfo], hides
 * itself when there are none, and routes its Stop (✕) callback by `taskId` — with no session dependency.
 * The pure [SubagentTasksPanel.formatTokens]/[SubagentTasksPanel.formatDuration] helpers are unit-tested
 * here too. Runs on the EDT (BasePlatformTestCase) so the Swing work is safe.
 */
class SubagentTasksPanelHeadlessTest : BasePlatformTestCase() {

    private fun task(
        id: String,
        type: String? = "code-reviewer",
        description: String = "review the diff",
        usage: TaskUsage = TaskUsage(),
        lastTool: String? = null,
    ) = TaskProgressInfo(
        taskId = id,
        description = description,
        subagentType = type,
        usage = usage,
        lastToolName = lastTool,
    )

    fun `test panel hidden when empty, one card per task`() {
        val panel = SubagentTasksPanel(onStop = {})

        panel.update(emptyList())
        assertFalse("no tasks must hide the strip", panel.isVisible)
        assertEquals(0, panel.componentCount)

        panel.update(listOf(task("t1"), task("t2"), task("t3")))
        assertTrue("non-empty must show the strip", panel.isVisible)
        assertEquals(3, panel.componentCount)

        panel.update(listOf(task("only")))
        assertEquals("update must rebuild, not append", 1, panel.componentCount)
    }

    fun `test stop callback reports the task id`() {
        var stopped: String? = null
        val panel = SubagentTasksPanel(onStop = { stopped = it })
        panel.update(listOf(task("alpha"), task("bravo"), task("charlie")))

        // The Stop ✕ is the single button in the third card.
        clickButton(findButtons(panel.getComponent(2))!!.single())
        assertEquals("charlie", stopped)
    }

    fun `test format tokens compacts magnitudes`() {
        assertEquals("0", SubagentTasksPanel.formatTokens(0))
        assertEquals("940", SubagentTasksPanel.formatTokens(940))
        assertEquals("1.2k", SubagentTasksPanel.formatTokens(1_234))
        assertEquals("48k", SubagentTasksPanel.formatTokens(48_000))
        assertEquals("3.4M", SubagentTasksPanel.formatTokens(3_400_000))
        assertEquals("0", SubagentTasksPanel.formatTokens(-5))
    }

    fun `test format duration compacts elapsed time`() {
        assertEquals("0s", SubagentTasksPanel.formatDuration(0))
        assertEquals("0s", SubagentTasksPanel.formatDuration(850))
        assertEquals("45s", SubagentTasksPanel.formatDuration(45_000))
        assertEquals("2m 05s", SubagentTasksPanel.formatDuration(125_000))
        assertEquals("1h 03m", SubagentTasksPanel.formatDuration(3_780_000))
        assertEquals("0s", SubagentTasksPanel.formatDuration(-1))
    }

    // tiny Swing tree helpers (mirrors SubPanelsHeadlessTest) ----------------

    private fun findButtons(c: java.awt.Component): List<javax.swing.JButton>? {
        val out = ArrayList<javax.swing.JButton>()
        fun walk(comp: java.awt.Component) {
            if (comp is javax.swing.JButton) out.add(comp)
            if (comp is java.awt.Container) comp.components.forEach(::walk)
        }
        walk(c)
        return out.ifEmpty { null }
    }

    private fun clickButton(b: javax.swing.JButton) = b.actionListeners.forEach {
        it.actionPerformed(java.awt.event.ActionEvent(b, java.awt.event.ActionEvent.ACTION_PERFORMED, "click"))
    }
}
