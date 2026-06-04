package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.MemoryRecallInfo

/**
 * Pure formatting of a memory_recall event into a short header summary and a markdown body listing each recalled
 * memory (scope · path + a truncated snippet). UI-free so it's unit-testable.
 */
object MemoryRecallFormatter {

    private const val SNIPPET_MAX = 200

    /** One-line summary for the row header, e.g. "Recalled 3 memories (select)". */
    fun summary(info: MemoryRecallInfo): String {
        val n = info.memories.size
        val noun = if (n == 1) "memory" else "memories"
        val mode = info.mode.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "Recalled $n $noun$mode"
    }

    /** Markdown bullet list of the recalled memories (one per line: scope, path, truncated snippet). */
    fun body(info: MemoryRecallInfo): String = buildString {
        for (m in info.memories) {
            val scope = m.scope.takeIf { it.isNotBlank() }?.let { "**$it** " } ?: ""
            val path = m.path.ifBlank { "(memory)" }
            append("- ").append(scope).append(path)
            m.content?.takeIf { it.isNotBlank() }?.let { append(" — ").append(truncate(it)) }
            append('\n')
        }
    }.trimEnd()

    private fun truncate(s: String): String {
        val flat = s.replace('\n', ' ').trim()
        return if (flat.length > SNIPPET_MAX) flat.take(SNIPPET_MAX) + "…" else flat
    }
}
