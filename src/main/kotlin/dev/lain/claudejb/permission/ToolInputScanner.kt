package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The **input surface** every rule of [SensitiveGuard] is matched against: the paths a tool call names, and the
 * commands it carries — read off the whole JSON input, not off a key list.
 *
 * ### Why the whole input, not a key list
 * A file argument is `file_path` — until an MCP server calls it `path`, `target`, `uri`, `destination`, or
 * something no one has seen. [pathCandidates] walks **every string leaf** of the input, skipping URLs and
 * multi-line blobs so a `Write`'s *contents* are not mistaken for a filename.
 *
 * A command is found the same way: by the SHAPE of the key ([COMMAND_KEY]), never by the tool's name — a name is
 * attacker-supplied, so an MCP server that spells its exec argument `script` is covered exactly like `Bash`.
 */
object ToolInputScanner {

    /** Keys whose value is (or contains) a command line, however the tool spells it. */
    private val COMMAND_KEY = Regex(
        """^(cmd|command|commands|script|shell|shell_?command|exec|execute|run|args|argv|arguments""" +
            """|code|program|pty_?input)$""",
        RegexOption.IGNORE_CASE,
    )

    /** A URL, not a path. */
    private val URLISH = Regex("""^[a-z][a-z0-9+.\-]*://""", RegexOption.IGNORE_CASE)

    /** Longer than a filename → it is a file's *contents*, not its name. */
    private const val MAX_PATH_LEN = 512

    // ── paths: every string leaf, not a key list ─────────────────────────────────────────────────────────

    fun pathCandidates(input: JsonObject, home: String?): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key)) {
                // A command hides paths in variables and quotes; tokenise the raw AND the de-obfuscated form.
                val sources = setOf(value, CommandRules.deobfuscate(value))
                sources.forEach { src ->
                    commandTokens(src).forEach { tok -> if (pathish(tok)) out += GuardPaths.normalize(tok, home) }
                }
            } else if (pathish(value)) {
                out += GuardPaths.normalize(value, home)
            }
        }
        return out.toList()
    }

    private fun walkStrings(element: JsonElement, key: String = "", visit: (String, String) -> Unit) {
        when (element) {
            is JsonObject -> for ((k, v) in element) walkStrings(v, k, visit)
            is JsonArray -> element.forEach { walkStrings(it, key, visit) }
            is JsonPrimitive -> if (element.isString) visit(key, element.content)
        }
    }

    private fun pathish(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_PATH_LEN) return false
        if (value.any { it == '\n' || it == '\r' }) return false
        if (URLISH.containsMatchIn(value)) return false
        return true
    }

    private fun commandTokens(command: String): List<String> =
        command.split(Regex("""[\s;|&<>=(),"'`]+""")).filter { it.isNotBlank() }

    // ── commands: whatever the tool calls its exec argument ──────────────────────────────────────────────

    /**
     * True when [input] carries a command/script string under a command-shaped key ([COMMAND_KEY]: `command`,
     * `cmd`, `script`, `shell`, `exec`, `run`, `args`/`argv`…) — i.e. this call executes something, whatever the
     * underlying shell (Bash, PowerShell, cmd.exe, sh, zsh…) and whatever the tool is named (the native `Bash`
     * tool, or an MCP tool like `execute_terminal_command`). A UI concern (the transcript uses this to render the
     * call's output as a copyable code block) built on the exact same detection the security rules already rely
     * on, so the two can never quietly drift apart into disagreeing about "is this a command".
     */
    fun isCommandCall(input: JsonObject): Boolean = commandCandidates(input).isNotEmpty()

    /** The raw command/script string [isCommandCall] detected, for rendering — `null` when there isn't one. */
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

    /**
     * One object entry: if the KEY names a command argument, take its value as a command (a string, or an argv
     * array joined back into one); otherwise keep descending. Split out of [commandCandidates] so the recursion
     * and the per-key decision are not nested in one another.
     */
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
