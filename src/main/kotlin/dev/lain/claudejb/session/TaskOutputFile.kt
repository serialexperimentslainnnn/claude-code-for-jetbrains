package dev.lain.claudejb.session

object TaskOutputFile {

    private val PROSE = Regex("""Output is being written to:\s*(\S+?)\.?(?:\s|$)""")

    private val TAG = Regex("""<output-file>\s*(.+?)\s*</output-file>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        TAG.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return PROSE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
