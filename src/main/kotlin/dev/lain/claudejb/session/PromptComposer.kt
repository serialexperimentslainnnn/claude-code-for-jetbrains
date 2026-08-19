package dev.lain.claudejb.session

import dev.lain.claudejb.context.Attachment
import dev.lain.claudejb.context.FilePickerHelper

object PromptComposer {

    data class Composed(val wireText: String, val images: List<Pair<String, String>>, val displayText: String)

    fun compose(text: String, attachments: List<Attachment>, projectRoot: String?): Composed? {
        val nonImage = attachments.filter { it !is Attachment.Image }
        val trimmed = text.trim().takeIf { it.isNotEmpty() }
        val wireParts = buildList {
            trimmed?.let { add(it) }
            nonImage.forEach { add(wireMention(it, projectRoot)) }
        }
        val displayText = buildList {
            trimmed?.let { add(it) }
            nonImage.takeIf { it.isNotEmpty() }?.let { list ->
                add(list.joinToString("\n") { displayMention(it) })
            }
        }.joinToString("\n\n")
        val images = attachments.filterIsInstance<Attachment.Image>().map { it.mediaType to it.base64 }
        val combined = wireParts.joinToString("\n\n")
        if (combined.isEmpty() && images.isEmpty()) return null
        val shown = displayText.ifEmpty { attachments.joinToString(" ") { it.displayName } }
        return Composed(combined, images, shown)
    }

    private fun wireMention(a: Attachment, root: String?): String = when (a) {
        is Attachment.FileRef -> mentionToken(relativizeForMention(root, a.path))
        else -> a.toPromptText()
    }

    private fun mentionToken(path: String): String =
        if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    private fun displayMention(a: Attachment): String = when (a) {
        is Attachment.FileRef -> {
            val enc = java.net.URLEncoder.encode(a.path, Charsets.UTF_8).replace("+", "%20")
            "[@${a.displayName}](jb://open?file=$enc&line=1)"
        }

        else -> a.toPromptText()
    }

    private fun relativizeForMention(root: String?, path: String): String =
        FilePickerHelper.relativeWithinRoot(root, path) ?: path
}
