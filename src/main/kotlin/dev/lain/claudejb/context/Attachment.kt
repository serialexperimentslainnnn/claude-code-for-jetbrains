package dev.lain.claudejb.context

/**
 * A rich attachment the user can pin to a chat turn before sending: a bare file reference, a code
 * selection, or an image. Pure data + [toPromptText] so it is trivially unit-testable; the mapping
 * to a real content block (especially images → `{type:"image",source:{type:"base64",…}}`) is done
 * later by `ClaudeSession`, which is why [Image] only carries the raw base64/media type here.
 */
sealed interface Attachment {

    /** Stable, human-readable label for chips/UI. */
    val displayName: String

    /**
     * Prompt-text fallback for this attachment. For [FileRef] this is an `@path` mention; for
     * [Selection] a fenced block tagged with `path:line`; for [Image] a placeholder marker (the
     * actual image bytes are sent as a separate content block, not inline text).
     */
    fun toPromptText(): String

    /** A whole file referenced by path, surfaced to the agent as an `@path` mention. */
    data class FileRef(val path: String, override val displayName: String) : Attachment {
        override fun toPromptText(): String = "@$path"
    }

    /** A code selection captured from an editor, fenced with a `path:line` info string. */
    data class Selection(
        val path: String,
        val startLine: Int,
        val text: String,
        val lang: String?,
    ) : Attachment {
        override val displayName: String
            get() = "${path.substringAfterLast('/')}:$startLine"

        override fun toPromptText(): String = buildString {
            append("```").append(lang.orEmpty())
            append(' ').append(path).append(':').append(startLine).append('\n')
            append(text)
            if (!text.endsWith("\n")) append('\n')
            append("```")
        }
    }

    /**
     * An image to send as a base64 content block. [mediaType] is an IANA type (e.g. `image/png`),
     * [base64] the raw (unprefixed) payload. [toPromptText] is only a marker — the real conversion
     * to a content block happens in `ClaudeSession`.
     */
    data class Image(
        override val displayName: String,
        val mediaType: String,
        val base64: String,
    ) : Attachment {
        override fun toPromptText(): String = "[image: $displayName]"
    }
}
