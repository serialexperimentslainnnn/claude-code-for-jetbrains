package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object AntiForensics {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val AT = """(?:^|[;&|\n]\s*|\bthen\s+|\bdo\s+)"""

    private const val SEC_LOG =
        "(?:messages|secure|auth\\.log|syslog|utmp|wtmp|btmp|lastlog" +
            "|kern\\.log|cron\\.log|maillog|system\\.log|faillog)"

    private val VECTORS: List<Regex> = listOf(
        re(AT + """history\s+-c(?=\s|$|[;&|])"""),
        re(AT + """unset\s+HISTFILE\b"""),
        re(AT + """set\s+\+o\s+history\b"""),
        re("""\bHISTFILE=/dev/null\b"""),
        re("""\bHIST(?:FILE)?SIZE=0\b"""),
        re(AT + """journalctl\b[^|;&]*--vacuum-(time|size|files)\b"""),
        re(AT + """Clear-History(?=\s|$|[;&|])"""),
        re(AT + """Set-PSReadlineOption\b[^|;&]*-HistorySaveStyle\s+SaveNothing\b"""),
        re("""Set-PSReadlineOption\b[^|;&\n]*-AddToHistoryHandler\b"""),
        re("""Remove-Item\b[^|;&\n]*HistorySavePath\b"""),
        re(""">\s*[^\s>]*\.(?:bash|zsh|sh|ksh|ash|fish)_history\b"""),
        re(AT + """(?:ln|cp)\b[^|;&\n]*/dev/null\b[^|;&\n]*_history\b"""),
        re(AT + """truncate\b[^|;&\n]*(?:-s\s*|--size[= ])0\b"""),
        re(""">\s*[^\s>]*/var/log/""" + SEC_LOG + """\b"""),
        re(AT + """wevtutil\b[^|;&]*\bcl\b"""),
        re(AT + """Clear-EventLog(?=\s|$|[;&|])"""),
        re(AT + """Remove-EventLog(?=\s|$|[;&|])"""),
        re(AT + """fsutil\b[^|;&\n]*\busn\b[^|;&\n]*\bdeletejournal\b"""),
        re(AT + """log\s+erase\b"""),
        re(AT + """touch\b[^|;&]*\s-[a-z]*[trd]"""),
        re(AT + """touch\b[^|;&]*\s--(reference|date|time)\b"""),
        re(AT + """SetFile\b[^|;&]*\s-[dm]\b"""),
        re("""\b(CreationTime|LastWriteTime|LastAccessTime)\s*="""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
