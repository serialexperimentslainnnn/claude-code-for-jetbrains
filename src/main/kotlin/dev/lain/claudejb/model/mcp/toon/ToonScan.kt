package dev.lain.claudejb.model.mcp.toon

internal object ToonScan {

    fun indexOfUnquoted(text: String, target: Char, from: Int = 0): Int {
        var quoted = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '\\' -> i++
                c == '"' -> quoted = !quoted
                !quoted && c == target -> return i
            }
            i++
        }
        return -1
    }

    fun split(text: String, delimiter: Char): List<String> {
        val tokens = ArrayList<String>()
        var start = 0
        while (true) {
            val at = indexOfUnquoted(text, delimiter, start)
            if (at < 0) break
            tokens += trimSpaces(text.substring(start, at))
            start = at + 1
        }
        tokens += trimSpaces(text.substring(start))
        return tokens
    }

    fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        var quoted = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '\\' -> i++
                c == '"' -> quoted = !quoted
                quoted -> Unit
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return i
            }
            i++
        }
        return -1
    }

    fun listItemContent(content: String): String? = when {
        content == "-" -> ""
        content.startsWith("- ") -> trimSpaces(content.substring(2))
        else -> null
    }

    fun trimSpaces(token: String): String = token.trim { it == ' ' }
}
