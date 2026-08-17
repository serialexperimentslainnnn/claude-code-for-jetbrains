package dev.lain.claudejb.session

/**
 * The one place the "Show workloads completed in the last X minutes" visibility rule lives, so the tab bar and
 * the dashboard cannot disagree about which finished workload is still worth showing.
 *
 * [isVisible] is pure: no IntelliJ Platform imports and no clock of its own — the current time is a parameter,
 * because a rule that reads its own clock cannot be reasoned about from outside it. The one instant this object
 * does own is [RUN_STARTED_AT], and it is a constant of the run rather than a reading taken per call.
 */
object WorkloadWindow {

    /** The "All" sentinel for [WINDOW_MINUTES]: no age ever hides a workload. */
    const val ALL = 0

    /**
     * The allowed window choices, in menu order. `ALL` sits last because "All" belongs at the end of the menu,
     * not sorted in among the numeric minute values.
     */
    // These literals ARE the menu; a name per entry would lengthen the line and tell the reader strictly less.
    @Suppress("MagicNumber")
    val WINDOW_MINUTES: List<Int> = listOf(5, 10, 15, 30, 60, 120, 240, ALL)

    /** The window the settings layer starts a user on, kept here so it is stated once and referenced elsewhere. */
    const val DEFAULT_MINUTES = 15

    /** Minutes in an hour, named so the wording below carries no bare number. */
    private const val MINUTES_PER_HOUR = 60

    /**
     * How a window reads in a menu: `15 Minutes`, `2 Hours`, `All in this session`.
     *
     * Beside [WINDOW_MINUTES] on purpose. The list and the words for it are one decision, and a settings page
     * holding its own copy of the wording is how a menu ends up offering a value this rule does not know.
     *
     * The sentinel is worded as `All in this session` rather than `All` because that is the honest promise:
     * nothing here reaches beyond the run — a workload restored from a previous one is stamped [RUN_STARTED_AT]
     * and a chat's registries are rebuilt per process — so a bare "All" would offer a history the view does not
     * have.
     */
    fun label(minutes: Int): String = when {
        minutes == ALL -> "All in this session"
        minutes < MINUTES_PER_HOUR -> "$minutes Minutes"
        minutes == MINUTES_PER_HOUR -> "1 Hour"
        else -> "${minutes / MINUTES_PER_HOUR} Hours"
    }

    /**
     * When this run of the plugin began watching workloads — one instant, captured once, shared by everything.
     *
     * It is the completion stamp given to a workload that arrives already finished and unstamped: one restored
     * from a previous run, or recorded by a build that wrote no stamp at all. That makes the window mean the
     * same thing for every workload — visible on reopening the IDE, then ageing out on its own — instead of
     * splitting them into two classes, one of which the window could never reach.
     *
     * A single value, not a reading per admission: stamping each one with its own "now" would make two
     * workloads restored in the same start expire at different moments, for no reason a user could name.
     */
    val RUN_STARTED_AT: Long = System.currentTimeMillis()

    private const val MILLIS_PER_MINUTE = 60_000L

    /**
     * Whether a workload belongs in the view under the given window.
     *
     * Live work is exempt entirely: a workload that is still running is always visible, whatever its age would
     * otherwise say. A `null` stamp means "not settled yet" and is likewise visible — everything that has
     * finished carries an instant, since one that arrives already finished is stamped at admission with
     * [RUN_STARTED_AT].
     *
     * The boundary is inclusive: a workload exactly [windowMinutes] old is still inside the window. The
     * arithmetic is done in `Long` so a wide window cannot overflow the minutes it is built from.
     */
    fun isVisible(running: Boolean, completedAtMillis: Long?, windowMinutes: Int, nowMillis: Long): Boolean {
        if (running) return true
        if (windowMinutes == ALL) return true
        if (completedAtMillis == null) return true
        return nowMillis - completedAtMillis <= windowMinutes.toLong() * MILLIS_PER_MINUTE
    }

    /**
     * One workload as the window judges it: what it is, what it hangs off, and whether it has settled.
     *
     * [parentId] is the workload above it — the agent that spawned an agent, the agent that owns a background
     * task, `null` for work the chat itself started. It is what makes [visible] a rule about a TREE rather
     * than about a list.
     */
    data class Entry(
        val id: String,
        val parentId: String?,
        val running: Boolean,
        val completedAtMillis: Long?,
    )

    /** What belongs in the view: the agent ids to draw, and the background task ids to draw under them. */
    data class Visible(val agents: Set<String>, val tasks: Set<String>)

    /**
     * Which workloads belong in the view, judged BOTTOM-UP so that what is emitted is a tree that holds
     * together.
     *
     * **A workload is hidden only when everything beneath it is hidden too.** [isVisible] answers for one
     * workload alone; this answers for the set, and the two disagree exactly where a finished parent still
     * has live work under it. The difference is not cosmetic. Rows are assembled by matching each node's
     * parent id against the nodes that were sent, so a node whose parent is absent from the payload is
     * nobody's child and is never reached: dropping a settled agent out from under a running one does not
     * leave a stale row behind, it makes the RUNNING one vanish — from the view whose whole subject is what
     * is running. Keeping the ancestors of everything kept is what makes that unrepresentable.
     *
     * A background task is a leaf hanging off the agent that owns it, so keeping a task keeps that agent and
     * everything above it.
     *
     * The walk follows the parent links with a seen-set rather than trusting any ordering, because the links
     * come from the binary and the spawn depth they are usually sorted by is the binary's own counter, which
     * a restored workload can carry a meaningless value for. The same seen-set is the cycle guard. A link to
     * something that is not present ends the walk, so a parent id naming nothing admits nobody.
     *
     * The price, and it is deliberate: the window means "out of window AND nothing live below it", so a
     * finished parent outlives its own window for as long as its children need it to.
     */
    fun visible(
        agents: List<Entry>,
        tasks: List<Entry>,
        windowMinutes: Int,
        nowMillis: Long,
    ): Visible {
        val byId = agents.associateBy { it.id }
        val keptAgents = LinkedHashSet<String>()

        fun keepWithAncestors(from: String?) {
            var current = from
            while (current != null) {
                val entry = byId[current] ?: return
                // Already kept means its own ancestors were kept when it was, so there is nothing above to
                // walk to — and on a malformed parent chain this is what stops the walk going round.
                if (!keptAgents.add(current)) return
                current = entry.parentId
            }
        }

        val keptTasks = tasks.filter { isVisible(it.running, it.completedAtMillis, windowMinutes, nowMillis) }
        agents.filter { isVisible(it.running, it.completedAtMillis, windowMinutes, nowMillis) }
            .forEach { keepWithAncestors(it.id) }
        keptTasks.forEach { keepWithAncestors(it.parentId) }
        return Visible(keptAgents, keptTasks.mapTo(LinkedHashSet()) { it.id })
    }
}
