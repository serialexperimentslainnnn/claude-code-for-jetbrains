package dev.lain.claudejb.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WorkloadWindow] — the single visibility rule the tab bar and the dashboard both defer to.
 *
 * Every instant here is a literal, and the clock is the parameter it is: a rule about elapsed time that reads
 * its own clock cannot be tested at its boundary, only near it.
 */
class WorkloadWindowTest {

    /** The instant every case is judged at. 15 minutes before it is 999_100_000. */
    private val now = 1_000_000_000L

    @Test
    fun `a running workload is visible however ancient its completion stamp`() {
        assertTrue(WorkloadWindow.isVisible(running = true, completedAtMillis = 0L, windowMinutes = 5, nowMillis = now))
    }

    @Test
    fun `a running workload is visible even with no completion stamp at all`() {
        assertTrue(WorkloadWindow.isVisible(running = true, completedAtMillis = null, windowMinutes = 5, nowMillis = now))
    }

    @Test
    fun `ALL shows a completed workload of any age`() {
        assertTrue(
            WorkloadWindow.isVisible(running = false, completedAtMillis = 0L, windowMinutes = WorkloadWindow.ALL, nowMillis = now),
        )
    }

    @Test
    fun `a workload with no completion stamp has not settled yet, so it is visible`() {
        // Nothing that has finished reaches this rule unstamped: a workload inherited already finished is
        // stamped at admission with the instant this run started. Hiding a null here would hide live work
        // whose ending has simply not been observed.
        assertTrue(WorkloadWindow.isVisible(running = false, completedAtMillis = null, windowMinutes = 5, nowMillis = now))
        assertTrue(WorkloadWindow.isVisible(running = false, completedAtMillis = null, windowMinutes = 240, nowMillis = now))
    }

    @Test
    fun `a workload completed exactly windowMinutes ago is visible, the boundary is inclusive`() {
        assertTrue(WorkloadWindow.isVisible(running = false, completedAtMillis = 999_100_000L, windowMinutes = 15, nowMillis = now))
    }

    @Test
    fun `a workload one millisecond older than windowMinutes is not visible`() {
        assertFalse(WorkloadWindow.isVisible(running = false, completedAtMillis = 999_099_999L, windowMinutes = 15, nowMillis = now))
    }

    @Test
    fun `a stamp inside the window is visible`() {
        assertTrue(WorkloadWindow.isVisible(running = false, completedAtMillis = 999_700_000L, windowMinutes = 15, nowMillis = now))
    }

    @Test
    fun `the widest window still measures elapsed time rather than overflowing it`() {
        // 240 minutes is 14_400_000 ms — well past what the minute count itself is, which is why the
        // multiplication is done in Long.
        assertTrue(WorkloadWindow.isVisible(running = false, completedAtMillis = 985_600_000L, windowMinutes = 240, nowMillis = now))
        assertFalse(WorkloadWindow.isVisible(running = false, completedAtMillis = 985_599_999L, windowMinutes = 240, nowMillis = now))
    }

    @Test
    fun `WINDOW_MINUTES is exactly the declared menu order, ending with ALL`() {
        assertEquals(listOf(5, 10, 15, 30, 60, 120, 240, 0), WorkloadWindow.WINDOW_MINUTES)
        assertEquals(WorkloadWindow.ALL, WorkloadWindow.WINDOW_MINUTES.last())
        assertTrue(WorkloadWindow.WINDOW_MINUTES.contains(WorkloadWindow.DEFAULT_MINUTES))
    }

    @Test
    fun `the run stamp is a real wall-clock instant`() {
        // It is what an already-finished workload is stamped with, so an unset or zero value would date every
        // restored workload to 1970 and hide the lot. That it is ONE instant shared by everything restored in
        // the same run is pinned where it is applied, in the registry tests.
        assertTrue(WorkloadWindow.RUN_STARTED_AT > 1_700_000_000_000L, "the run stamp must come from the wall clock")
    }

    // ── The set, not the single workload: what `visible` decides that `isVisible` cannot ──────────────────

    /** Out of the window by a wide margin at [WINDOW]. */
    private val aged = 999_000_000L

    /** Inside the window at [WINDOW]. */
    private val recent = 999_700_000L

    private fun settled(id: String, parentId: String? = null, at: Long = aged) =
        WorkloadWindow.Entry(id, parentId, running = false, completedAtMillis = at)

    private fun live(id: String, parentId: String? = null) =
        WorkloadWindow.Entry(id, parentId, running = true, completedAtMillis = null)

    private fun visible(agents: List<WorkloadWindow.Entry>, tasks: List<WorkloadWindow.Entry> = emptyList()) =
        WorkloadWindow.visible(agents, tasks, windowMinutes = WINDOW, nowMillis = now)

    @Test
    fun `an aged-out parent with a live child is still emitted`() {
        val shown = visible(listOf(settled("parent"), live("child", parentId = "parent")))
        assertTrue("child" in shown.agents, "the running child is the whole point of the view")
        assertTrue(
            "parent" in shown.agents,
            "a child whose parent is missing from the payload is nobody's child, so dropping the parent " +
                "hides the RUNNING child instead of merely tidying up",
        )
    }

    @Test
    fun `an aged-out parent whose descendants are all aged out is dropped`() {
        val shown = visible(listOf(settled("parent"), settled("child", parentId = "parent")))
        assertTrue(shown.agents.isEmpty(), "with nothing live beneath it, the window applies as written")
    }

    @Test
    fun `every emitted parent id is itself emitted, however deep the live work sits`() {
        val shown = visible(
            listOf(
                settled("grandparent"),
                settled("parent", parentId = "grandparent"),
                live("child", parentId = "parent"),
                settled("unrelated"),
            ),
        )
        assertEquals(setOf("grandparent", "parent", "child"), shown.agents)
    }

    @Test
    fun `an aged-out agent that owns a live task is still emitted`() {
        // A task's stamp is sealed where the task settles and has no relation to its owner's, so the two can
        // fall on opposite sides of the window. The owner is named by every task row that is drawn.
        val shown = visible(listOf(settled("owner")), listOf(live("task", parentId = "owner")))
        assertEquals(setOf("task"), shown.tasks)
        assertTrue("owner" in shown.agents, "a task row names its owner, so that owner has to be in the payload")
    }

    @Test
    fun `a task is judged on its own age and takes nobody with it when it goes`() {
        val shown = visible(listOf(settled("owner")), listOf(settled("task", parentId = "owner")))
        assertTrue(shown.tasks.isEmpty())
        assertTrue(shown.agents.isEmpty(), "nothing live remained under the owner either")
    }

    @Test
    fun `a live workload is kept whatever its recorded age says`() {
        val shown = visible(listOf(live("a"), settled("b", at = recent), settled("c")))
        assertEquals(setOf("a", "b"), shown.agents)
    }

    @Test
    fun `a parent id naming nothing admits nobody and leaves the child alone`() {
        // The parent links come from the binary, and a sidecar whose parent's own file never parsed leaves a
        // link to an agent that is not in the map at all.
        val shown = visible(listOf(live("child", parentId = "never-existed")))
        assertEquals(setOf("child"), shown.agents)
    }

    @Test
    fun `a cycle in the parent links terminates and keeps the ring`() {
        val shown = visible(listOf(settled("a", parentId = "b"), live("b", parentId = "a")))
        assertEquals(setOf("a", "b"), shown.agents)
    }

    @Test
    fun `ALL keeps every workload without consulting the tree at all`() {
        val shown = WorkloadWindow.visible(
            agents = listOf(settled("parent"), settled("child", parentId = "parent")),
            tasks = listOf(settled("task", parentId = "parent")),
            windowMinutes = WorkloadWindow.ALL,
            nowMillis = now,
        )
        assertEquals(setOf("parent", "child"), shown.agents)
        assertEquals(setOf("task"), shown.tasks)
    }

    private companion object {
        /** The window every set case is judged under; 15 minutes before [now] is 999_100_000. */
        const val WINDOW = 15
    }
}
