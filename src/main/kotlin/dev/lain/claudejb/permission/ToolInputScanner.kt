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

    private val URLISH = Regex("""^[a-z][a-z0-9+.\-]*://""", RegexOption.IGNORE_CASE)

    private val URL_IN_TEXT = Regex("""[a-z][a-z0-9+.\-]*://[^\s"'`<>()\[\]{}|\\^]+""", RegexOption.IGNORE_CASE)

    private const val MAX_PATH_LEN = 512

    private const val MAX_FOLD_LEN = 64 * 1024

    fun pathCandidates(input: JsonObject, home: String?, env: Map<String, String> = emptyMap()): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key)) {
                val sources = setOf(value, CommandRules.deobfuscate(value, home, env))
                sources.forEach { src -> commandTokens(src).forEach { tok -> bothSpellings(tok, home, env, out) } }
            } else {
                bothSpellings(value, home, env, out)
            }
        }
        return out.toList()
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
            if (COMMAND_KEY.matches(key) || PATTERN_KEY.matches(key)) return@walkStrings
            if (CONTENT_KEY.matches(key) && BLOCK_COMMENT_ONLY.matches(value.trim())) return@walkStrings
            bothSpellings(value, home, env, out)
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
            if (value.length > MAX_FOLD_LEN) return@walkStrings
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

    private fun commandTokens(command: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in command) {
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)

                c == '\'' || c == '"' || c == '`' -> quote = c

                c.isWhitespace() || c in SPLIT_CHARS -> if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }

                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    fun commandText(input: JsonObject): String? = commandCandidates(input).firstOrNull()

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
            is JsonPrimitive -> if (value.isString) out.add(value.content)

            is JsonArray -> {
                val joined = value.filterIsInstance<JsonPrimitive>().filter { it.isString }
                    .joinToString(" ") { it.content }
                if (joined.isNotBlank()) out.add(joined)
            }

            else -> descend(value)
        }
    }
}
