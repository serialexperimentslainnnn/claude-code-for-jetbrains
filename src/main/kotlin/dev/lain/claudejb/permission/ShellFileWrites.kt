package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object ShellFileWrites {

    private val BLANKET_MUTATORS =
        CommandRules.cmdStart("""tee|cp|mv|rsync|install|truncate|rm|mkdir|touch|ln|chmod|chown|shred""")

    private val SED_IN_PLACE = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?sed\b[^;&|\n]*(-i\b|--in-place\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val DD_WRITE = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?dd\b[^;&|\n]*\bof=\S""",
        RegexOption.IGNORE_CASE,
    )

    private val REDIRECT = Regex("""\d*>{1,2}\|?(?!&)\s*([^\s;&|<>()]+)""")

    internal fun shellFileWrite(input: JsonObject): String? {
        for (raw in ToolInputScanner.commandCandidates(input)) {
            val command = CommandRules.deobfuscate(raw)
            BLANKET_MUTATORS.find(command)?.let { return it.value.trim() }
            SED_IN_PLACE.find(command)?.let { return it.value.trim() }
            DD_WRITE.find(command)?.let { return it.value.trim() }
            REDIRECT.findAll(command).firstOrNull { !isBenignTarget(it.groupValues[1]) }
                ?.let { return it.value.trim() }
        }
        return null
    }

    private fun isBenignTarget(rawTarget: String): Boolean {
        val target = rawTarget.trim('\'', '"').lowercase()
        if (target.startsWith("&")) return true
        return BENIGN_REDIRECT_TARGETS.any { target == it || target.endsWith("/$it") }
    }

    private val BENIGN_REDIRECT_TARGETS = setOf(
        "dev/null",
        "dev/zero",
        "dev/full",
        "dev/stdout",
        "dev/stderr",
        "dev/tty",
    )
}
