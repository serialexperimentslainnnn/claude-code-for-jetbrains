package dev.lain.claudejb.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SessionTitle(
    val text: String,
    val authored: Boolean,
    val prompt: String?,
)

object SessionTitleReader {

    @Serializable
    private data class TitleLine(
        val type: String? = null,
        val aiTitle: String? = null,
        val customTitle: String? = null,
    )

    private val JSON = Json { ignoreUnknownKeys = true }

    fun read(sessionId: String): SessionTitle? =
        SessionStore.readLines(sessionId)?.let { pick(it) }

    fun readTitle(sessionId: String): String? = read(sessionId)?.text

    fun pick(lines: List<String>): SessionTitle? {
        var custom: String? = null
        var ai: String? = null
        var prompt: String? = null
        for (line in lines) {
            val parsed = titleLineOf(line) ?: continue
            customTitleOf(parsed)?.let { custom = it }
            aiTitleOf(parsed)?.let { ai = it }
            if (prompt == null) prompt = usablePromptOf(parsed, line)
        }
        val authored = custom ?: ai
        val text = authored ?: prompt?.let { asTitle(it) } ?: return null
        return SessionTitle(text = text, authored = authored != null, prompt = prompt)
    }

    private fun titleLineOf(line: String): TitleLine? {
        if (line.isBlank()) return null
        return runCatching { JSON.decodeFromString<TitleLine>(line) }.getOrNull()
    }

    private fun customTitleOf(parsed: TitleLine): String? = parsed.customTitle?.takeIf { it.isNotBlank() }

    private fun aiTitleOf(parsed: TitleLine): String? =
        if (parsed.type == "ai-title") parsed.aiTitle?.takeIf { it.isNotBlank() } else null

    fun pickTitle(lines: List<String>): String? = pick(lines)?.text

    fun asTitle(text: String): String? {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (firstLine.length <= MAX_TITLE) return firstLine
        val cut = firstLine.take(MAX_TITLE)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ';', ':') + "…"
    }

    private fun usablePromptOf(parsed: TitleLine, line: String): String? {
        if (parsed.type != "user") return null
        val entries = runCatching { SessionTranscriptReader.parseEntries(listOf(line)) }.getOrNull().orEmpty()
        val text = entries.firstOrNull { it.speaker == "USER" }?.text?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.take(MAX_PROMPT)
    }

    private const val MAX_TITLE = 48

    private const val MAX_PROMPT = 2000
}
