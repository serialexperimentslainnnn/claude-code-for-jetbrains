package dev.lain.claudejb.session

object WorkloadWindow {

    const val ALL = 0

    @Suppress("MagicNumber")
    val WINDOW_MINUTES: List<Int> = listOf(5, 10, 15, 30, 60, 120, 240, ALL)

    const val DEFAULT_MINUTES = 15

    private const val MINUTES_PER_HOUR = 60

    fun label(minutes: Int): String = when {
        minutes == ALL -> "All in this session"
        minutes < MINUTES_PER_HOUR -> "$minutes Minutes"
        minutes == MINUTES_PER_HOUR -> "1 Hour"
        else -> "${minutes / MINUTES_PER_HOUR} Hours"
    }

    val RUN_STARTED_AT: Long = System.currentTimeMillis()

    private const val MILLIS_PER_MINUTE = 60_000L

    fun isVisible(running: Boolean, completedAtMillis: Long?, windowMinutes: Int, nowMillis: Long): Boolean {
        if (running) return true
        if (windowMinutes == ALL) return true
        if (completedAtMillis == null) return true
        return nowMillis - completedAtMillis <= windowMinutes.toLong() * MILLIS_PER_MINUTE
    }

    data class Entry(
        val id: String,
        val parentId: String?,
        val running: Boolean,
        val completedAtMillis: Long?,
    )

    data class Visible(val agents: Set<String>, val tasks: Set<String>)

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
