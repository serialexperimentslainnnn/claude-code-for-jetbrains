package dev.lain.claudejb.context

sealed interface Attachment {

    val displayName: String

    fun toPromptText(): String

    data class FileRef(val path: String, override val displayName: String) : Attachment {
        override fun toPromptText(): String = "@$path"
    }

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

    data class Image(
        override val displayName: String,
        val mediaType: String,
        val base64: String,
    ) : Attachment {
        override fun toPromptText(): String = "[image: $displayName]"
    }
}
