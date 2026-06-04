package dev.lain.claudejb.diff

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One contiguous changed line-range.
 * `[start1,end1)` indexes the current file's lines; `[start2,end2)` indexes the proposed file's lines.
 */
data class Hunk(
    val index: Int,
    val start1: Int,
    val end1: Int,
    val start2: Int,
    val end2: Int,
    val preview: String,
)

/**
 * Pure logic for partial ("hunk-by-hunk") acceptance of Edit/Write/MultiEdit.
 *
 * The `claude` binary performs the write; we only narrow the input we hand it so it writes exactly the
 * text the user accepted. No IntelliJ Application/EDT dependency lives here — hunk computation (which needs
 * the platform diff engine) is in [DiffPresenter.computeHunks]; everything below is pure and testable.
 */
object HunkSelection {

    /**
     * Rebuilds the file text applying only the [accepted] hunks. Between hunks the current lines are copied
     * verbatim; for each hunk we emit `proposed[start2,end2)` when accepted, else `current[start1,end1)`.
     * Hunks are assumed sorted by `start1` and non-overlapping (the platform diff guarantees this).
     * Lines are joined with `"\n"`. subList bounds are coerced defensively.
     */
    fun reconstruct(
        currentLines: List<String>,
        proposedLines: List<String>,
        hunks: List<Hunk>,
        accepted: Set<Int>,
    ): String {
        val out = ArrayList<String>()
        var cursor = 0
        val currentSize = currentLines.size
        for (hunk in hunks) {
            val gapEnd = hunk.start1.coerceIn(cursor, currentSize)
            if (cursor < gapEnd) out.addAll(currentLines.subList(cursor, gapEnd))
            if (hunk.index in accepted) {
                val s = hunk.start2.coerceIn(0, proposedLines.size)
                val e = hunk.end2.coerceIn(s, proposedLines.size)
                if (s < e) out.addAll(proposedLines.subList(s, e))
            } else {
                val s = hunk.start1.coerceIn(0, currentSize)
                val e = hunk.end1.coerceIn(s, currentSize)
                if (s < e) out.addAll(currentLines.subList(s, e))
            }
            cursor = hunk.end1.coerceIn(cursor, currentSize)
        }
        if (cursor < currentSize) out.addAll(currentLines.subList(cursor, currentSize))
        return out.joinToString("\n")
    }

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
