package dev.lain.claudejb.forge

data class Redacted(val text: String, val count: Int) {

    val clean: Boolean get() = count == 0
}

object SecretRedactor {

    const val MASK = "[redacted]"

    fun scrub(raw: String): Redacted {
        var redactions = 0
        var text = raw
        PATTERNS.forEach { pattern ->
            text = pattern.replace(text) { match ->
                redactions++
                mask(match)
            }
        }
        return Redacted(text, redactions)
    }

    private fun mask(match: MatchResult): String {
        val keep = match.groupValues.getOrNull(1).orEmpty()
        return if (keep.isEmpty()) MASK else keep + MASK
    }

    private val PATTERNS: List<Regex> = listOf(
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bglpat-[A-Za-z0-9_-]{16,}"""),
        Regex("""\bgldt-[A-Za-z0-9_-]{16,}"""),
        Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\bsk-[A-Za-z0-9_-]{20,}"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""),
        Regex("""(://[^\s/:@]+:)[^\s/@]+@"""),
        Regex("""((?i:authorization|proxy-authorization)\s*:\s*(?i:bearer|basic|token)?\s*)\S+"""),
        Regex(
            """((?i:[a-z0-9_.-]*(?:secret|password|passwd|token|api[_-]?key|access[_-]?key|credential)""" +
                """[a-z0-9_.-]*)\s*[=:]\s*)(?:"[^"]{4,}"|'[^']{4,}'|\S{4,})""",
        ),
    )
}
