package dev.lain.claudejb.session

object LegacyModels {

    data class Entry(val value: String, val label: String)

    val ALL: List<Entry> = listOf(
        Entry("claude-opus-4-8", "Opus 4.8"),
        Entry("claude-opus-4-7", "Opus 4.7"),
        Entry("claude-opus-4-6", "Opus 4.6"),
        Entry("claude-opus-4-5", "Opus 4.5"),
        Entry("claude-opus-4-1", "Opus 4.1"),
        Entry("claude-opus-4-0", "Opus 4"),
        Entry("claude-sonnet-4-6", "Sonnet 4.6"),
        Entry("claude-sonnet-4-5", "Sonnet 4.5"),
        Entry("claude-sonnet-4-0", "Sonnet 4"),
        Entry("claude-3-7-sonnet", "Sonnet 3.7"),
        Entry("claude-3-5-sonnet", "Sonnet 3.5"),
        Entry("claude-3-5-haiku", "Haiku 3.5"),
    )

    fun labelFor(value: String?): String? = value?.let { id -> ALL.firstOrNull { it.value == id }?.label }

    fun offeredAlongside(catalog: Collection<String>): List<Entry> =
        ALL.filterNot { entry -> catalog.any { it == entry.value } }
}
