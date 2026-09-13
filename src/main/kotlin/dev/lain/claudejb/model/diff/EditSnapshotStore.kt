package dev.lain.claudejb.model.diff

import kotlinx.serialization.json.JsonObject
import java.io.File

data class EditSnapshot(
    val toolName: String,
    val input: JsonObject,
    val beforeText: String,
    val filePath: String,
    val existedBefore: Boolean = true,
)

class EditSnapshotStore(private val capacity: Int = DEFAULT_CAPACITY) {

    private val byToolUseId = object : LinkedHashMap<String, EditSnapshot>(INITIAL_BUCKETS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EditSnapshot>): Boolean = size > capacity
    }

    @Synchronized
    fun capture(toolName: String, input: JsonObject, toolUseId: String): EditSnapshot? {
        val path = DiffPresenter.filePathOf(input) ?: return null
        if (toolUseId.isNotBlank()) byToolUseId[toolUseId]?.let { return it }
        val file = File(path)
        val existedBefore = file.isFile
        val beforeText = if (existedBefore) runCatching { file.readText() }.getOrDefault("") else ""
        return EditSnapshot(toolName, input, beforeText, path, existedBefore)
            .also { if (toolUseId.isNotBlank()) byToolUseId[toolUseId] = it }
    }

    @Synchronized
    fun get(toolUseId: String): EditSnapshot? = byToolUseId[toolUseId]

    @Synchronized
    fun updateInput(toolUseId: String, input: JsonObject) {
        if (toolUseId.isBlank()) return
        byToolUseId.computeIfPresent(toolUseId) { _, snap -> snap.copy(input = input) }
    }

    @Synchronized
    fun clear() = byToolUseId.clear()

    companion object {
        const val DEFAULT_CAPACITY = 500
        private const val INITIAL_BUCKETS = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
