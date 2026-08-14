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
 * multi-line blobs so a `Write`'s *contents* are not mistaken for a filename — and the length that decides
 * "contents, not filename" is measured on the FOLDED spelling ([candidate]), because otherwise the caller picks
 * it: `/.` repeated enough times pushes any path over any cap without naming a different file.
 *
 * The two exceptions are both about a leaf's ROLE, not its spelling, and they point opposite ways: a payload key
 * ([CONTENT_KEY]) carries text and names nowhere, so it is not offered as a location at all; a command key
 * ([COMMAND_KEY]) carries text the paths genuinely live inside, so it is tokenised and every token is judged.
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

    /**
     * Keys whose value is the **payload** of a call — the text being written or replaced — and therefore names
     * nowhere. What a call touches is its path argument; what it carries is not a second path argument.
     *
     * This is a rule about **provenance**, and it is the only thing that can fix its bug class. Prose, code and
     * documentation legitimately quote paths that belong to somebody else, and every one of those quotes used to
     * be judged as a destination: an `Edit` on a project file whose `old_string` was the line `/home/bob/.cache`
     * is FOREIGN, which denies **every** caller, with no override and no way to retry. Hardening the recognisers
     * instead only moves the false positive — `ForeignTerritory.isUnc` was narrowed once for a `//` comment and
     * `ForeignTerritory.HOME_SEGMENT` anchored once for a `./pages/home/Home` import, and a payload that happens
     * to BE a path still walks through both, because both are being asked the wrong question.
     *
     * **A command is the deliberate asymmetry**: there the path really does live inside the text, so a
     * [COMMAND_KEY] value is still tokenised and every token still judged. That check runs first, so a key that
     * is both never lands here.
     */
    private val CONTENT_KEY = Regex(
        """^(old_?string|new_?string|old_?str|new_?str|content|contents|old_?source|new_?source)$""",
        RegexOption.IGNORE_CASE,
    )

    /** A URL, not a path. */
    private val URLISH = Regex("""^[a-z][a-z0-9+.\-]*://""", RegexOption.IGNORE_CASE)

    /** Longer than a filename → it is a file's *contents*, not its name. Measured AFTER folding — see [candidate]. */
    private const val MAX_PATH_LEN = 512

    /**
     * Hard ceiling on the string this class is willing to fold at all. [MAX_PATH_LEN] cannot be that ceiling
     * (folding is what decides whether a value is under it), so the bound on the work has to be its own number:
     * a leaf longer than this is a payload, not a padded filename, and is dropped unfolded.
     *
     * **This is not a performance compromise. It is the point at which the string stops being able to be a path**,
     * and the difference matters, because read as a compromise the obvious "fix" is to remove the ceiling and pay
     * an O(n) fold per leaf — on the thread that reads the binary's ENTIRE stdout, which is the very thread whose
     * blocking motivated the time budget in [GuardPaths.expandWithResolved].
     *
     * Nothing can open a path padded past this. Linux refuses any pathname over `PATH_MAX` (4096) with
     * `ENAMETOOLONG`, and that limit is on the whole string, not per component; on Windows not even the extended
     * `\\?\` form reaches 32 767 characters. So the syscall fails before the file is touched, whatever the guard
     * decided: the set of strings this ceiling excludes from folding is exactly the set that designates no
     * reachable file. Folding without a ceiling would buy coverage of zero real cases.
     *
     * 64 KiB is therefore an order of magnitude above any path an operating system will act on (~32 000 `/.`
     * segments), and cheap to fold once. **If `PATH_MAX` ever stops carrying that argument, this ceiling falls
     * with it** — it has no independent justification.
     */
    private const val MAX_FOLD_LEN = 64 * 1024

    // ── paths: every string leaf, not a key list ─────────────────────────────────────────────────────────

    fun pathCandidates(input: JsonObject, home: String?): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key)) {
                // A command hides paths in variables and quotes; tokenise the raw AND the de-obfuscated form.
                val sources = setOf(value, CommandRules.deobfuscate(value))
                sources.forEach { src -> commandTokens(src).forEach { tok -> candidate(tok, home)?.let { out += it } } }
            } else if (!CONTENT_KEY.matches(key)) {
                candidate(value, home)?.let { out += it }
            }
        }
        return out.toList()
    }

    /**
     * The one path candidate [value] yields, or null when it cannot be a filename at all.
     *
     * **The order here is the security property.** `.` and `..` segments are the only way a path's spelling can
     * grow without the file it names changing, so `~/.ssh` + `/.` × 300 + `/id_rsa` is `~/.ssh/id_rsa` written
     * long enough to cross [MAX_PATH_LEN]. Measuring the RAW spelling therefore let that path be dropped — and a
     * dropped candidate is not a weaker match, it is no match at all: credentials, dangerous commands and foreign
     * territory alike never see it, so an over-long spelling was an ALLOW from every rule at once. Fold first,
     * measure the folded form, and there is no length left for the caller to choose — up to [MAX_FOLD_LEN], past
     * which the spelling names no file any operating system will open (see there).
     */
    private fun candidate(value: String, home: String?): String? {
        if (value.isBlank() || value.length > MAX_FOLD_LEN) return null
        if (value.any { it == '\n' || it == '\r' }) return null
        if (URLISH.containsMatchIn(value)) return null
        if (value.length <= MAX_PATH_LEN) return GuardPaths.normalize(value, home)
        if (!looksPadded(value)) return null // long and not foldable: contents, and they stay out
        return GuardPaths.fold(GuardPaths.normalize(value, home)).takeIf { it.length <= MAX_PATH_LEN }
    }

    /**
     * Is [value] long *because* of `.`/`..` segments — i.e. worth folding before it is measured?
     *
     * A pre-test, not a rule: it keeps the ordinary over-long leaf (a minified file's contents under a `content`
     * key) from paying for a fold it cannot benefit from, while every spelling that padding can produce contains
     * one of these four, since padding is a repeated SEGMENT and a segment is delimited on both sides.
     */
    private fun looksPadded(value: String): Boolean =
        value.contains("/./") || value.contains("/../") || value.contains("""\.\""") || value.contains("""\..\""")

    private fun walkStrings(element: JsonElement, key: String = "", visit: (String, String) -> Unit) {
        when (element) {
            is JsonObject -> for ((k, v) in element) walkStrings(v, k, visit)
            is JsonArray -> element.forEach { walkStrings(it, key, visit) }
            is JsonPrimitive -> if (element.isString) visit(key, element.content)
        }
    }

    private fun commandTokens(command: String): List<String> =
        command.split(Regex("""[\s;|&<>=(),"'`]+""")).filter { it.isNotBlank() }

    // ── commands: whatever the tool calls its exec argument ──────────────────────────────────────────────

    /**
     * The raw command/script string [input] carries under a command-shaped key ([COMMAND_KEY]: `command`,
     * `cmd`, `script`, `shell`, `exec`, `run`, `args`/`argv`…) — i.e. what this call executes, whatever the
     * underlying shell (Bash, PowerShell, cmd.exe, sh, zsh…) and whatever the tool is named (the native `Bash`
     * tool, or an MCP tool like `execute_terminal_command`). `null` when the call executes nothing.
     *
     * A UI concern (the transcript renders it as the call's own copyable code block) built on the exact same
     * detection the security rules already rely on, so the two can never quietly drift apart into disagreeing
     * about "is this a command".
     */
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
