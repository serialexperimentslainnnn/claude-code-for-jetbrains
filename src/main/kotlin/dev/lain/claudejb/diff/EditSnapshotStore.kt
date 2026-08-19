package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class EditSnapshot(
    val toolName: String,
    val input: JsonObject,
    val beforeText: String,
    val filePath: String,
    val existedBefore: Boolean = true,
)

class EditSnapshotStore {
    private val byToolUseId = ConcurrentHashMap<String, EditSnapshot>()

    fun capture(toolName: String, input: JsonObject, toolUseId: String): EditSnapshot? {
        val path = DiffPresenter.filePathOf(input) ?: return null
        if (toolUseId.isNotBlank()) byToolUseId[toolUseId]?.let { return it }
        val file = File(path)
        val existedBefore = file.isFile
        val beforeText = if (existedBefore) runCatching { file.readText() }.getOrDefault("") else ""
        return EditSnapshot(toolName, input, beforeText, path, existedBefore)
            .also { if (toolUseId.isNotBlank()) byToolUseId[toolUseId] = it }
    }

    fun get(toolUseId: String): EditSnapshot? = byToolUseId[toolUseId]

    fun updateInput(toolUseId: String, input: JsonObject) {
        if (toolUseId.isBlank()) return
        byToolUseId.computeIfPresent(toolUseId) { _, snap -> snap.copy(input = input) }
    }
}
