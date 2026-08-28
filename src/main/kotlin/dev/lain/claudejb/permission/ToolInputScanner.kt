package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ToolInputScanner {

    private val COMMAND_KEY = Regex(
        """^(cmd|command|commands|script|shell|shell_?command|exec|execute|run|args|argv|arguments""" +
            """|code|program|pty_?input|stdin|cmdline|entrypoint)$""",
        RegexOption.IGNORE_CASE,
    )

    private val CONTENT_KEY = Regex(
        """^(old_?string|new_?string|old_?str|new_?str|content|contents|old_?source|new_?source)$""",
        RegexOption.IGNORE_CASE,
    )

    private val PATTERN_KEY = Regex("""^(pattern|glob|regex|regexp)$""", RegexOption.IGNORE_CASE)

    private val BLOCK_COMMENT_ONLY = Regex("""^/\*+$|^/\*[\s\S]*\*/$""")

    private val MESSAGE_KEY = Regex(
        """^(message|msg|prompt|question|query|instruction|instructions)$""",
        RegexOption.IGNORE_CASE,
    )

    fun messageText(input: JsonObject): String? {
        if (commandText(input) != null) return null
        return input.entries.firstNotNullOfOrNull { (key, value) ->
            if (!MESSAGE_KEY.matches(key)) return@firstNotNullOfOrNull null
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
    }

    private val URLISH = Regex("""^[a-z][a-z0-9+.\-]*+://""", RegexOption.IGNORE_CASE)

    private val URL_IN_TEXT = Regex("""[a-z][a-z0-9+.\-]*+://[^\s"'`<>()\[\]{}|\\^]+""", RegexOption.IGNORE_CASE)

    private const val MAX_PATH_LEN = 512

    private const val MAX_FOLD_LEN = 64 * 1024

    private const val MAX_COMMAND_LEN = 8 * 1024

    private fun windowed(command: String): List<String> =
        if (command.length <= MAX_COMMAND_LEN) {
            listOf(command)
        } else {
            listOf(command.take(MAX_COMMAND_LEN), command.takeLast(MAX_COMMAND_LEN))
        }

    fun pathCandidates(input: JsonObject, home: String?, env: Map<String, String> = emptyMap()): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key)) {
                windowed(value).forEach { win ->
                    val sources = setOf(win, CommandRules.deobfuscate(win, home, env))
                    sources.forEach { src -> commandTokens(src).forEach { tok -> bothSpellings(tok, home, env, out) } }
                }
            } else {
                pathSpellings(value, home, env, out)
            }
        }
        return out.toList()
    }

    private fun pathSpellings(value: String, home: String?, env: Map<String, String>, out: MutableSet<String>) {
        bothSpellings(value, home, env, out)
        val deobfuscated = CommandRules.deobfuscatePath(value, home, env)
        if (deobfuscated != value) bothSpellings(deobfuscated, home, env, out)
    }

    private fun bothSpellings(value: String, home: String?, env: Map<String, String>, out: MutableSet<String>) {
        candidate(value, home, env)?.let { out += it }
        if (home != null || env.isNotEmpty()) candidate(value, null)?.let { out += it }
    }

    fun locationCandidates(
        input: JsonObject,
        home: String?,
        env: Map<String, String> = emptyMap(),
    ): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (PATTERN_KEY.matches(key)) return@walkStrings
            if (CONTENT_KEY.matches(key) && BLOCK_COMMENT_ONLY.matches(value.trim())) return@walkStrings
            if (COMMAND_KEY.matches(key)) {
                windowed(value).forEach { win ->
                    val sources = setOf(win, CommandRules.deobfuscate(win, home, env))
                    sources.forEach { src ->
                        val parsed = commandPaths(src)
                        val scope = if (parsed.bindings.isEmpty()) env else env + parsed.bindings
                        parsed.tokens.forEach { tok -> bothSpellings(tok, home, scope, out) }
                    }
                }
            } else {
                pathSpellings(value, home, env, out)
            }
        }
        return out.toList()
    }

    internal fun destinationCandidates(input: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (PATTERN_KEY.matches(key) || CONTENT_KEY.matches(key)) return@walkStrings
            if (COMMAND_KEY.matches(key)) {
                out += value
                commandTokens(value).forEach { out += it }
            } else {
                out += value
            }
        }
        return out.toList()
    }

    fun urlCandidates(input: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (CONTENT_KEY.matches(key)) return@walkStrings
            if (value.length > MAX_FOLD_LEN || "://" !in value) return@walkStrings
            URL_IN_TEXT.findAll(value).forEach { out += it.value }
        }
        return out.toList()
    }

    private fun candidate(value: String, home: String?, env: Map<String, String> = emptyMap()): String? {
        if (value.isBlank() || value.length > MAX_FOLD_LEN) return null
        if (value.any { it == '\n' || it == '\r' }) return null
        if (URLISH.containsMatchIn(value)) return null
        if (value.length <= MAX_PATH_LEN) return GuardPaths.normalize(value, home, env)
        if (!looksPadded(value)) return null
        return GuardPaths.fold(GuardPaths.normalize(value, home, env)).takeIf { it.length <= MAX_PATH_LEN }
    }

    private fun looksPadded(value: String): Boolean =
        value.contains("/./") || value.contains("/../") || value.contains("""\.\""") || value.contains("""\..\""")

    private fun walkStrings(element: JsonElement, key: String = "", visit: (String, String) -> Unit) {
        when (element) {
            is JsonObject -> for ((k, v) in element) walkStrings(v, k, visit)
            is JsonArray -> element.forEach { walkStrings(it, key, visit) }
            is JsonPrimitive -> if (element.isString) visit(key, element.content)
        }
    }

    private val SPLIT_CHARS = charArrayOf(';', '|', '&', '<', '>', '=', '(', ')', ',')

    private val LOCATION_SPLIT_CHARS = charArrayOf(';', '|', '&', '<', '>', '(', ')', ',')

    private val SEGMENT_SPLIT = Regex("""[;&|\n]""")

    private val ASSIGNMENT = Regex("""^([A-Za-z_][A-Za-z0-9_]*)=([\s\S]*)$""")

    private val ASSIGNMENT_PREFIX = setOf("export", "declare", "local", "readonly", "typeset", "env", "set")

    private val PATH_SHAPED = Regex("""^(?:[/~]|\.{1,2}/|[A-Za-z]:[/\\]|[\x24%])""")

    private val EXECUTION_CONTROLLING = setOf(
        "PATH", "BASH_ENV", "ENV", "SHELL",
        "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
        "NODE_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP", "PERL5LIB", "RUBYOPT",
        "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXTERNAL_DIFF", "GIT_PAGER", "PAGER", "EDITOR", "VISUAL",
    )

    private class CommandPaths(val tokens: List<String>, val bindings: Map<String, String>)

    private fun commandTokens(command: String): List<String> = splitTokens(command, SPLIT_CHARS)

    private fun splitTokens(command: String, splitChars: CharArray): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in command) {
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)

                c == '\'' || c == '"' || c == '`' -> quote = c

                c.isWhitespace() || c in splitChars -> if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }

                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun bind(declared: MatchResult, bindings: MutableMap<String, String>, tokens: MutableList<String>) {
        val name = declared.groupValues[1]
        val value = declared.groupValues[2]
        bindings[name] = value
        if (name.uppercase() in EXECUTION_CONTROLLING) {
            value.split(':').filterTo(tokens) { PATH_SHAPED.containsMatchIn(it) }
        }
    }

    private fun emitPathShaped(token: String, tokens: MutableList<String>) {
        val assigned = token.indexOf('=')
        val candidates = if (assigned >= 0) listOf(token, token.substring(assigned + 1)) else listOf(token)
        candidates.filterTo(tokens) { PATH_SHAPED.containsMatchIn(it) }
    }

    private fun commandPaths(command: String): CommandPaths {
        val tokens = ArrayList<String>()
        val bindings = LinkedHashMap<String, String>()
        for (segment in command.split(SEGMENT_SPLIT)) {
            var declaring = true
            for (token in splitTokens(segment, LOCATION_SPLIT_CHARS)) {
                val declared = if (declaring) ASSIGNMENT.matchEntire(token) else null
                when {
                    declared != null -> bind(declared, bindings, tokens)

                    token.lowercase() in ASSIGNMENT_PREFIX -> Unit

                    else -> {
                        declaring = false
                        emitPathShaped(token, tokens)
                    }
                }
            }
        }
        return CommandPaths(tokens, bindings)
    }

    fun commandText(input: JsonObject): String? = commandCandidates(input).firstOrNull()

    /** Every command in the input, for the callers that must answer for all of them rather than the first. */
    fun commandsIn(input: JsonObject): List<String> = commandCandidates(input)

    internal fun commandCandidates(input: JsonObject): List<String> {
        val out = ArrayList<String>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> visitEntry(k, v, out, ::visit) }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(input)
        return out
    }

    private fun visitEntry(key: String, value: JsonElement, out: MutableList<String>, descend: (JsonElement) -> Unit) {
        if (!COMMAND_KEY.matches(key)) {
            descend(value)
            return
        }
        when (value) {
            is JsonPrimitive -> if (value.isString) addWindows(value.content, out)

            is JsonArray -> {
                val joined = value.filterIsInstance<JsonPrimitive>().filter { it.isString }
                    .joinToString(" ") { it.content }
                if (joined.isNotBlank()) addWindows(joined, out)
            }

            else -> descend(value)
        }
    }

    private fun addWindows(command: String, out: MutableList<String>) {
        out.addAll(windowed(command))
    }
}
