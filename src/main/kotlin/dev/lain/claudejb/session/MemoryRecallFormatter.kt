package dev.lain.claudejb.session

import dev.lain.claudejb.protocol.MemoryRecallInfo

object MemoryRecallFormatter {

    private const val SNIPPET_MAX = 200

    fun summary(info: MemoryRecallInfo): String {
        val n = info.memories.size
        val noun = if (n == 1) "memory" else "memories"
        val mode = info.mode.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "Recalled $n $noun$mode"
    }

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
