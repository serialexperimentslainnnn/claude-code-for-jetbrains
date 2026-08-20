package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object ScriptExecution {

    private val SOURCED = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:source|\.)\s+(\S+)""",
        RegexOption.IGNORE_CASE,
    )

    private const val INTERPRETERS =
        """sh|bash|zsh|ksh|dash|ash|fish|csh|tcsh|nu|python\d?(?:\.\d+)?|perl|raku|ruby|node|deno|bun|php|""" +
            """pwsh|powershell|osascript|Rscript|lua|julia|tclsh|wish|expect|groovy|scala|kotlin|elixir|escript|""" +
            """swift|dart|crystal|clojure|bb|racket|guile|gosh|chez|sbcl"""

    private val SOURCE_RUN = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?""" +
            """(?:go\s+run|nim\s+[cr]|crystal\s+run|dart\s+run|tcc\s+-run|java)\s+([^\s;&|]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val INLINE_CODE = Regex(
        """(?:^|\s)(?:-c|-e|-E|--eval|--command|-Command|-EncodedCommand|--exec)(?:\s|=|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val INTERPRETED = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?($INTERPRETERS)\b([^;&|\n]*)""",
        RegexOption.IGNORE_CASE,
    )

    private val SCRIPT_SUFFIXES = setOf(
        "sh", "bash", "zsh", "ksh", "fish", "csh", "command",
        "py", "pyw", "pl", "rb", "js", "mjs", "cjs", "ts", "php", "lua", "r", "jl", "tcl", "groovy",
        "ps1", "psm1", "bat", "cmd", "vbs", "wsf", "jse", "scpt", "exp", "awk", "sed",
    )

    private val SYSTEM_BIN_DIRS = listOf(
        "/usr/bin/", "/bin/", "/usr/sbin/", "/sbin/", "/usr/local/bin/", "/usr/local/sbin/",
        "/opt/homebrew/bin/", "/opt/homebrew/sbin/", "/snap/bin/", "/usr/libexec/",
        "c:/windows/system32/", "c:/windows/", "c:/program files/", "c:/program files (x86)/",
    )

    internal fun scriptsIn(input: JsonObject, policy: SensitiveGuard.Policy): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in ToolInputScanner.commandCandidates(input)) {
            val command = CommandRules.deobfuscate(raw, policy.home, policy.envValues)
            SOURCED.findAll(command).forEach { m -> anchor(m.groupValues[1], policy)?.let { out += it } }
            SOURCE_RUN.findAll(command).forEach { m -> anchor(m.groupValues[1], policy)?.let { out += it } }
            interpretedFiles(command).forEach { f -> anchor(f, policy)?.let { out += it } }
            launchedFiles(command).forEach { f -> anchor(f, policy)?.let { out += it } }
        }
        return out.toList()
    }

    private val NAMEABLE = Regex("""[A-Za-z0-9_]""")

    private fun anchor(rawPath: String, policy: SensitiveGuard.Policy): String? {
        val token = rawPath.trim().trim('\'', '"')
        if (token.isEmpty()) return null
        if (!NAMEABLE.containsMatchIn(token)) return null
        val normalized = GuardPaths.normalize(token, policy.home, policy.envValues)
        if (normalized.isBlank()) return null
        if (GuardPaths.isAbsolute(normalized)) return GuardPaths.fold(normalized)
        val root = policy.projectRoot?.let { GuardPaths.normalize(it, policy.home) } ?: return null
        return GuardPaths.fold("$root/${normalized.removePrefix("./")}")
    }

    private fun interpretedFiles(command: String): List<String> {
        val out = ArrayList<String>()
        INTERPRETED.findAll(command).forEach { m ->
            val tail = m.groupValues[2]
            if (INLINE_CODE.containsMatchIn(tail)) return@forEach
            tail.split(' ', '\t').map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("-") }
                ?.let { out += it }
        }
        return out
    }

    private fun launchedFiles(command: String): List<String> = commandWords(command).filter { isLaunchedFile(it) }

    private fun isLaunchedFile(word: String): Boolean {
        val token = word.trim().trim('\'', '"')
        if (token.isEmpty() || token.startsWith("-")) return false
        val path = token.replace('\\', '/')
        if (path.startsWith("./") || path.startsWith("../")) return true
        if (path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in SCRIPT_SUFFIXES) return true
        if (!GuardPaths.isAbsolute(path)) return false
        val lower = path.lowercase()
        return SYSTEM_BIN_DIRS.none { lower.startsWith(it) }
    }

    internal fun inSystemBinDir(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase()
        return SYSTEM_BIN_DIRS.any { lower.startsWith(it) }
    }

    private fun commandWords(command: String): List<String> =
        command.split(';', '|', '&', '\n')
            .mapNotNull { segment ->
                segment.trim().split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
                    .dropWhile { it.equals("sudo", ignoreCase = true) || ASSIGNMENT.matches(it) }
                    .firstOrNull()
            }

    private val ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")
}
