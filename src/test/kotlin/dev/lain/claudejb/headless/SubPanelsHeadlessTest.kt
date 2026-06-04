package dev.lain.claudejb.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.lain.claudejb.permission.PendingPermission
import dev.lain.claudejb.protocol.AskOption
import dev.lain.claudejb.protocol.AskQuestion
import dev.lain.claudejb.ui.PermissionTrayPanel
import dev.lain.claudejb.ui.QueueStripPanel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Headless: the extracted composer sub-panels build their rows/cards from plain data and wire their
 * callbacks, with no [dev.lain.claudejb.session.ClaudeSession] dependency. Runs on the EDT
 * (BasePlatformTestCase) so the Swing component work is safe.
 */
class SubPanelsHeadlessTest : BasePlatformTestCase() {

    // -----------------------------------------------------------------------
    // QueueStripPanel
    // -----------------------------------------------------------------------

    fun `test queue strip hidden when empty, one row per prompt`() {
        val panel = QueueStripPanel(onRemove = {})

        panel.update(emptyList())
        assertFalse("empty queue must hide the strip", panel.isVisible)
        assertEquals(0, panel.componentCount)

        panel.update(listOf("first", "second", "third"))
        assertTrue("non-empty queue must show the strip", panel.isVisible)
        assertEquals(3, panel.componentCount)

        panel.update(listOf("only"))
        assertEquals("update must rebuild, not append", 1, panel.componentCount)
    }

    fun `test queue strip remove callback reports the row index`() {
        var removed = -1
        val panel = QueueStripPanel(onRemove = { removed = it })
        panel.update(listOf("a", "b", "c"))

        clickButton(findButtons(panel.getComponent(2))!!.single())
        assertEquals(2, removed)
    }

    // -----------------------------------------------------------------------
    // PermissionTrayPanel
    // -----------------------------------------------------------------------

    private fun newTray(
        onAccept: (String, JsonObject?) -> Unit = { _, _ -> },
        onReject: (String) -> Unit = {},
        onViewDiff: (String) -> Unit = {},
        onAlwaysAllow: (String) -> Unit = {},
        onAnswer: (String, Map<String, String>) -> Unit = { _, _ -> },
    ) = PermissionTrayPanel(onAccept, onReject, onViewDiff, onAlwaysAllow, onAnswer)

    private fun permission(id: String, tool: String = "Bash"): PendingPermission =
        PendingPermission(
            requestId = id,
            toolName = tool,
            input = buildJsonObject { put("command", "ls") },
            title = "Claude wants to use $tool",
            summary = "$ ls",
            reviewable = false,
        )

    private fun question(id: String): PendingPermission =
        PendingPermission(
            requestId = id,
            toolName = "AskUserQuestion",
            input = JsonObject(emptyMap()),
            title = "Pick one",
            summary = "",
            reviewable = false,
            questions = listOf(
                AskQuestion(
                    question = "Color?",
                    options = listOf(AskOption(label = "Red"), AskOption(label = "Blue")),
                    multiSelect = false,
                ),
            ),
        )

    private fun plan(id: String, text: String? = "1. step one\n2. step two"): PendingPermission =
        PendingPermission(
            requestId = id,
            toolName = "ExitPlanMode",
            input = JsonObject(emptyMap()),
            title = "Claude proposes a plan",
            summary = "",
            reviewable = false,
            isPlan = true,
            planText = text,
        )

    fun `test tray hidden when empty, one card per pending`() {
        val panel = newTray()

        panel.update(emptyList())
        assertFalse(panel.isVisible)
        assertEquals(0, panel.componentCount)

        panel.update(listOf(permission("r1"), permission("r2")))
        assertTrue(panel.isVisible)
        assertEquals(2, panel.componentCount)

        panel.update(listOf(permission("r1")))
        assertEquals(1, panel.componentCount)
    }

    fun `test tray renders both permission and question cards`() {
        val panel = newTray()
        panel.update(listOf(permission("r1"), question("q1")))
        assertEquals(2, panel.componentCount)
    }

    fun `test accept and reject route by request id`() {
        var accepted: String? = null
        var rejected: String? = null
        val panel = newTray(
            onAccept = { id, override -> accepted = id; assertNull("single-hunk accept sends no override", override) },
            onReject = { rejected = it },
        )
        panel.update(listOf(permission("req-42")))

        val buttons = findButtons(panel.getComponent(0))!!
        clickButton(buttons.first { it.text == "Accept" })
        assertEquals("req-42", accepted)
        clickButton(buttons.first { it.text == "Reject" })
        assertEquals("req-42", rejected)
    }

    fun `test always allow reports the tool name`() {
        var allowed: String? = null
        val panel = newTray(onAlwaysAllow = { allowed = it })
        panel.update(listOf(permission("r1", tool = "WebFetch")))

        clickButton(findButtons(panel.getComponent(0))!!.first { it.text == "Always allow" })
        assertEquals("WebFetch", allowed)
    }

    fun `test question card answers after a selection`() {
        var answeredId: String? = null
        var answers: Map<String, String>? = null
        val panel = newTray(onAnswer = { id, a -> answeredId = id; answers = a })
        panel.update(listOf(question("q-7")))

        val card = panel.getComponent(0)
        // Submit is disabled until an option is chosen: nothing selected → no callback.
        clickButton(findButtons(card)!!.first { it.text == "Submit" })
        assertNull(answeredId)

        // Choose "Red" by clicking its row, then Submit.
        selectOption(card, "Red")
        clickButton(findButtons(card)!!.first { it.text == "Submit" })
        assertEquals("q-7", answeredId)
        assertEquals(mapOf("Color?" to "Red"), answers)
    }

    fun `test plan card renders the plan text and wires approve to accept`() {
        var accepted: String? = null
        val panel = newTray(onAccept = { id, override -> accepted = id; assertNull(override) })
        panel.update(listOf(plan("plan-1")))

        val card = panel.getComponent(0)
        // Plan body is shown verbatim in a text area.
        val texts = findTextAreas(card).map { it.text }
        assertTrue("plan body must be rendered", texts.any { it.contains("step one") && it.contains("step two") })

        // Approve plan resolves through onAccept (allow).
        clickButton(findButtons(card)!!.first { it.text == "Approve plan" })
        assertEquals("plan-1", accepted)
    }

    fun `test plan card keep planning routes to reject`() {
        var rejected: String? = null
        val panel = newTray(onReject = { rejected = it })
        panel.update(listOf(plan("plan-2")))

        clickButton(findButtons(panel.getComponent(0))!!.first { it.text == "Keep planning" })
        assertEquals("plan-2", rejected)
    }

    fun `test permission card renders rich fields when present`() {
        val rich = PendingPermission(
            requestId = "rich-1",
            toolName = "Bash",
            input = buildJsonObject { put("command", "cat /etc/shadow") },
            title = "Claude wants to use Bash",
            summary = "$ cat /etc/shadow",
            reviewable = false,
            description = "Read a system file",
            blockedPath = "/etc/shadow",
            decisionReason = "Path outside the project root",
        )
        val panel = newTray()
        panel.update(listOf(rich))

        val texts = findTextAreas(panel.getComponent(0)).map { it.text }
        assertTrue(texts.any { it.contains("Read a system file") })
        assertTrue(texts.any { it.contains("/etc/shadow") })
        assertTrue(texts.any { it.contains("Path outside the project root") })
    }

    private fun findTextAreas(c: java.awt.Component): List<javax.swing.JTextArea> {
        val out = ArrayList<javax.swing.JTextArea>()
        fun walk(comp: java.awt.Component) {
            if (comp is javax.swing.JTextArea) out.add(comp)
            if (comp is java.awt.Container) comp.components.forEach(::walk)
        }
        walk(c)
        return out
    }

    // -----------------------------------------------------------------------
    // tiny Swing tree helpers
    // -----------------------------------------------------------------------

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

    /** Finds the option-row text area whose content matches [label] and dispatches a click to its row. */
    private fun selectOption(card: java.awt.Component, label: String) {
        var target: java.awt.Component? = null
        fun walk(comp: java.awt.Component) {
            if (comp is javax.swing.JTextArea && comp.text == label) target = comp
            if (comp is java.awt.Container) comp.components.forEach(::walk)
        }
        walk(card)
        val area = requireNotNull(target) { "option '$label' not found" }
        area.mouseListeners.forEach {
            it.mouseClicked(
                java.awt.event.MouseEvent(
                    area, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 0, 0, 1, false,
                ),
            )
        }
    }
}
