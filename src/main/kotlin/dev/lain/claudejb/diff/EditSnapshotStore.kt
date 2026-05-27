package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The pre-write state of one Edit/Write/MultiEdit, captured at approval time and keyed by `tool_use_id`.
 *
 * The binary — not the IDE — writes the file once we answer `allow`, so the file's current contents are only
 * recoverable *before* that response goes out. We snapshot them here so the change can be re-diffed later from
 * the transcript, in any permission mode, long after the transient approval diff has closed.
 */
data class EditSnapshot(
    val toolName: String,
    val input: JsonObject,
    val beforeText: String,
    val filePath: String,
)

/**
 * Per-session store of [EditSnapshot]s. Written from the process reader thread (auto-approve path) and from the
 * EDT (manual Accept), read from the EDT (the transcript's "View diff" affordance) — hence [ConcurrentHashMap].
 *
 * Lifetime is the session: snapshots hold the full pre-write text per edit, so on a very long session the count
 * may need capping. They do not survive a restart (the file changes between sessions), which is why the
 * transcript's "View diff" degrades to a no-op when [get] returns null.
 */
class EditSnapshotStore {
    private val byToolUseId = ConcurrentHashMap<String, EditSnapshot>()

    /**
     * Reads the file's current contents (or `""` if it does not exist yet) and stores them against [toolUseId].
     * Must be called *before* the `allow` response is written. Returns the captured snapshot, or null when the
     * input carries no `file_path`. Centralizes the "read before the binary writes" logic.
     */
    fun capture(toolName: String, input: JsonObject, toolUseId: String): EditSnapshot? {
        val path = DiffPresenter.filePathOf(input) ?: return null
        val file = File(path)
        val beforeText = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        // Only index under a real id: a blank toolUseId would collide across edits and make the transcript's
        // "View diff" surface a stale snapshot. Still return it so the transient auto-approve diff can open.
        return EditSnapshot(toolName, input, beforeText, path).also { if (toolUseId.isNotBlank()) byToolUseId[toolUseId] = it }
    }

    fun get(toolUseId: String): EditSnapshot? = byToolUseId[toolUseId]
}
