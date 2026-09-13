package dev.lain.claudejb.model.permission.scan

internal object CommandTokenizer {

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

    internal class CommandPaths(val tokens: List<String>, val bindings: Map<String, String>)

    internal fun commandTokens(command: String): List<String> = splitTokens(command, SPLIT_CHARS)

    internal fun segments(command: String): List<String> =
        withoutComments(command).split(SEGMENT_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

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
            pathListEntries(value).filterTo(tokens) { PATH_SHAPED.containsMatchIn(it) }
        }
    }

    private val DRIVE_ENTRY = Regex("""^[A-Za-z]:""")

    private fun pathListEntries(value: String): List<String> =
        value.split(';').flatMap { entry -> if (DRIVE_ENTRY.containsMatchIn(entry)) listOf(entry) else entry.split(':') }

    private fun emitPathShaped(token: String, tokens: MutableList<String>) {
        val assigned = token.indexOf('=')
        if (assigned >= 0 && token.startsWith("-")) return
        val candidates = if (assigned >= 0) listOf(token, token.substring(assigned + 1)) else listOf(token)
        candidates.filterTo(tokens) { PATH_SHAPED.containsMatchIn(it) }
    }

    private val INERT_VERBS = setOf(
        "echo", "printf", ":", "true", "false", "test", "[", "[[", "case", "esac", "in",
        "read", "return", "shift", "unset", "type", "command", "which", "basename", "dirname",
    )

    private val NAVIGATION_VERBS = setOf("cd", "chdir", "pushd", "popd")

    private val REDIRECT_TARGET = Regex("""\d?>>?\s*([^\s;|&<>]+)|<\s*([^\s;|&<>]+)""")

    private fun emitRedirectTargets(segment: String, tokens: MutableList<String>) {
        if ('>' !in segment && '<' !in segment) return
        REDIRECT_TARGET.findAll(segment).forEach { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                ?.takeIf { PATH_SHAPED.containsMatchIn(it) }
                ?.let { tokens += it }
        }
    }

    private fun withoutComments(command: String): String {
        if ('#' !in command) return command
        val out = StringBuilder(command.length)
        var quote: Char? = null
        var afterBlank = true
        var skipping = false
        for (c in command) {
            when {
                c == '\n' -> {
                    skipping = false
                    quote = null
                    afterBlank = true
                    out.append(c)
                }

                skipping -> Unit

                quote != null -> {
                    if (c == quote) quote = null
                    afterBlank = false
                    out.append(c)
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    afterBlank = false
                    out.append(c)
                }

                c == '#' && afterBlank -> skipping = true

                else -> {
                    afterBlank = c.isWhitespace()
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    internal fun commandPaths(command: String): CommandPaths {
        val tokens = ArrayList<String>()
        val bindings = LinkedHashMap<String, String>()
        val segments = withoutComments(command).split(SEGMENT_SPLIT)
        segments.forEachIndexed { index, segment ->
            emitRedirectTargets(segment, tokens)
            val acts = segments.drop(index + 1).any { it.isNotBlank() }
            var verb: String? = null
            var sub: String? = null
            var previous: String? = null
            for (token in splitTokens(segment, LOCATION_SPLIT_CHARS)) {
                val declared = ASSIGNMENT.matchEntire(token)
                when {
                    verb != null && ContainerMounts.isContainerTool(verb) && ContainerMounts.isMountKey(token) ->
                        emitOperand(verb, sub, previous, token, tokens)

                    declared != null -> bind(declared, bindings, tokens)

                    token.lowercase() in ASSIGNMENT_PREFIX -> Unit

                    verb == null -> {
                        verb = token.lowercase().substringAfterLast('/')
                        if ('*' !in token && '?' !in token) emitPathShaped(token, tokens)
                    }

                    else -> {
                        if (sub == null && !token.startsWith("-")) sub = token.lowercase()
                        if (operative(verb, acts)) emitOperand(verb, sub, previous, token, tokens)
                    }
                }
                previous = token
            }
        }
        return CommandPaths(tokens, bindings)
    }

    private fun emitOperand(verb: String, sub: String?, previous: String?, token: String, tokens: MutableList<String>) {
        val operand = when {
            ContainerMounts.isContainerTool(verb) -> ContainerMounts.hostSide(previous, token) ?: return
            isApiEndpoint(verb, sub, previous, token) -> return
            else -> token
        }
        emitPathShaped(operand, tokens)
    }

    private fun isApiEndpoint(verb: String, sub: String?, previous: String?, token: String): Boolean =
        verb == "gh" && sub == "api" && token.startsWith("/") && previous != "--input"

    private fun operative(verb: String?, acts: Boolean): Boolean = when (verb) {
        null -> true
        in INERT_VERBS -> false
        in NAVIGATION_VERBS -> acts
        else -> true
    }
}
