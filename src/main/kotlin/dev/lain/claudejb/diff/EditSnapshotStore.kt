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
    /**
     * Whether the file already existed on disk when this snapshot was captured. Distinguishes a Write that
     * **created** a new file (false) from one that overwrote an existing (possibly empty) file (true) — both have
     * an empty [beforeText], so this flag is what lets a rollback *delete* a freshly-created file instead of
     * leaving a 0-byte husk. Defaults to true so older constructors/tests that predate the flag still compile.
     */
    val existedBefore: Boolean = true,
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
     * Must be called *before* the binary writes. **First capture wins**: if a snapshot already exists for
     * [toolUseId] it is returned unchanged and the file is NOT re-read — so the earliest capture (now on the
     * `tool_use` event, before the write) is authoritative and a later re-capture (permission approval / auto-open)
     * can never overwrite the before-text with post-write content. Returns null when the input carries no `file_path`.
     */
    fun capture(toolName: String, input: JsonObject, toolUseId: String): EditSnapshot? {
        val path = DiffPresenter.filePathOf(input) ?: return null
        // First-capture-wins: return the already-stored pre-write snapshot rather than re-reading (now-written) bytes.
        if (toolUseId.isNotBlank()) byToolUseId[toolUseId]?.let { return it }
        val file = File(path)
        val existedBefore = file.isFile
        val beforeText = if (existedBefore) runCatching { file.readText() }.getOrDefault("") else ""
        // Only index under a real id: a blank toolUseId would collide across edits and make the transcript's
        // "View diff" surface a stale snapshot. Still return it so the transient auto-approve diff can open.
        return EditSnapshot(toolName, input, beforeText, path, existedBefore)
            .also { if (toolUseId.isNotBlank()) byToolUseId[toolUseId] = it }
    }

    fun get(toolUseId: String): EditSnapshot? = byToolUseId[toolUseId]

    /**
     * Replaces the stored [input] for [toolUseId] while keeping the captured before-text, so a later diff reflects
     * what was ACTUALLY written — e.g. after the user edited the proposed content in the review diff and we narrowed
     * the write to their version. No-op if no snapshot exists for [toolUseId] (or it's blank).
     */
    fun updateInput(toolUseId: String, input: JsonObject) {
        if (toolUseId.isBlank()) return
        byToolUseId.computeIfPresent(toolUseId) { _, snap -> snap.copy(input = input) }
    }
}
