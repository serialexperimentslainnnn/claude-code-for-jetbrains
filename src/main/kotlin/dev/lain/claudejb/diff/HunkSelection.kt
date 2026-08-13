package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pure narrowing of an Edit/Write/MultiEdit tool input.
 *
 * The `claude` binary performs the write; we only narrow the input we hand it so it writes exactly the
 * text the user accepted — today, the text they may have edited on the proposed side of the review diff.
 * No IntelliJ Application/EDT dependency lives here, so all of it is pure and testable.
 */
object HunkSelection {

    /**
     * Re-encodes a narrowed tool input so the binary writes exactly [selectedText]. `file_path` is preserved.
     *
     * - `Write`    → copy of [originalInput] with `content` overwritten to [selectedText].
     * - `Edit`     → `{file_path, old_string=currentText, new_string=selectedText, replace_all=false}`.
     * - `MultiEdit`→ `{file_path, edits:[{old_string=currentText, new_string=selectedText}]}`.
     * - anything else → [originalInput] unchanged.
     */
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
