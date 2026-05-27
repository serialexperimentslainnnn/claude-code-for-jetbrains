package dev.lain.claudejb.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the human-readable session title the `claude` binary generates (the one shown by `--resume`)
 * from its sidecar transcript. The binary persists, per session, lines like
 * `{"type":"ai-title","aiTitle":"…","sessionId":"…"}` and — after a `/rename` — a `customTitle`.
 *
 * File access is delegated to [SessionStore] (the single source of truth, confined to
 * `~/.claude/projects`); IO is synchronous and must run off the EDT. The title-selection logic is
 * factored into the pure [pickTitle] so it is unit-testable without a filesystem.
 */
object SessionTitleReader {

    @Serializable
    private data class TitleLine(
        val type: String? = null,
        val aiTitle: String? = null,
        val customTitle: String? = null,
    )

    private val JSON = Json { ignoreUnknownKeys = true }

    /** Returns the binary's title for [sessionId], or null if no sidecar / no title line is found. */
    fun readTitle(sessionId: String): String? =
        SessionStore.readLines(sessionId)?.let { pickTitle(it) }

    /**
     * Picks the session title from raw JSONL lines: the last non-blank `customTitle` (user `/rename`)
     * wins; otherwise the last `ai-title` line's `aiTitle`. Blank/corrupt lines are skipped, never throw.
     * Pure — unit-testable.
     */
    fun pickTitle(lines: List<String>): String? {
        var custom: String? = null
        var ai: String? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val parsed = runCatching { JSON.decodeFromString<TitleLine>(line) }.getOrNull() ?: continue
            parsed.customTitle?.takeIf { it.isNotBlank() }?.let { custom = it }
            if (parsed.type == "ai-title") parsed.aiTitle?.takeIf { it.isNotBlank() }?.let { ai = it }
        }
        return custom ?: ai
    }
}
