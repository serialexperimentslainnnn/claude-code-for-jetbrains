package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object HunkSelection {

    fun encodeInput(
        toolName: String,
        originalInput: JsonObject,
        currentText: String,
        selectedText: String,
    ): JsonObject = when (toolName) {
        "Write" -> buildJsonObject {
            originalInput.forEach { (key, value) -> put(key, value) }
            put("content", selectedText)
        }

        "Edit" -> buildJsonObject {
            DiffPresenter.filePathOf(originalInput)?.let { put("file_path", it) }
            put("old_string", currentText)
            put("new_string", selectedText)
            put("replace_all", false)
        }

        "MultiEdit" -> buildJsonObject {
            DiffPresenter.filePathOf(originalInput)?.let { put("file_path", it) }
            putJsonArray("edits") {
                addJsonObject {
                    put("old_string", currentText)
                    put("new_string", selectedText)
                }
            }
        }

        else -> originalInput
    }
}
