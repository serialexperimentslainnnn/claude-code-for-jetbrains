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
 * Every string leaf is a location candidate, with one exception, and it is about the leaf's ROLE rather than its
 * spelling: a command key ([COMMAND_KEY]) carries text the paths genuinely live inside, so it is tokenised and
 * every token is judged instead of the raw string.
 *
 * A command is found the same way: by the SHAPE of the key ([COMMAND_KEY]), never by the tool's name — a name is
 * attacker-supplied, so an MCP server that spells its exec argument `script` is covered exactly like `Bash`.
 *
 * ### Five kinds of candidate, because a rule can only judge what it was handed
 * [pathCandidates] (every leaf that could name a file, **before and after variable expansion**),
 * [locationCandidates] (the same, minus what is code rather than a place), [commandCandidates] (what the call
 * executes), [urlCandidates] (where it talks to) and [destinationCandidates] (the RAW spelling of where it acts).
 * The last two exist because a rule can only be walked around at a step it never sees: [candidate] deliberately
 * DISCARDS a URL, so a rule about a destination has to be handed the URLs rather than widening the path list and
 * regressing three rules to reach them; and expansion rewrites a value, so a rule about what could NOT be
 * resolved has to see the value before anything tried.
 */
object ToolInputScanner {

    /**
     * Keys whose value is (or contains) a command line, however the tool spells it. `stdin`/`cmdline`/`entrypoint`
     * cover MCP exec tools spelled that way; deliberately NOT the generic `input`, which appears on tools that
     * carry an arbitrary payload rather than a command and would swallow it into the wrong scan.
     */
    private val COMMAND_KEY = Regex(
        """^(cmd|command|commands|script|shell|shell_?command|exec|execute|run|args|argv|arguments""" +
            """|code|program|pty_?input|stdin|cmdline|entrypoint)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Keys whose value is the **payload** of a call — the text being written or replaced.
     *
     * **It does not subtract from [pathCandidates], and that is a deliberate decision rather than an omission.**
     * Every string leaf, payload included, is judged by the path rules: a single-line value that names a
     * sensitive location is judged as one wherever it arrived, so an `Edit` whose replaced text IS
     * `/home/bob/.cache/app` trips the foreign rule exactly as a `file_path` would. The cost is real and accepted
     * — editing a line of documentation that merely QUOTES someone else's path is refused until that rule is
     * switched off — and the reason it is accepted is that the alternative is a rule an attacker can satisfy by
     * choosing which key to put the path under.
     *
     * What the constant still governs is the two questions where a payload genuinely has nothing to say:
     *  - [destinationCandidates], because a payload names no destination — a `$HOME` inside a Makefile being
     *    written is text, and asking "can this call's destination be resolved" about it is asking nothing;
     *  - [urlCandidates], because a `https://pastebin.com/…` inside the text of a file being written is a string
     *    in a document, not somewhere this call is talking to.
     */
    private val CONTENT_KEY = Regex(
        """^(old_?string|new_?string|old_?str|new_?str|content|contents|old_?source|new_?source)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Keys whose value is a search pattern (`Grep`'s `pattern`/`glob`/`type`, `Glob`'s `pattern`), never a
     * location. A regex delimited by slashes is absolute-LOOKING by pure coincidence of syntax, same class of
     * false positive as a command's own tokens, so [locationCandidates] excludes it the same way.
     */
    private val PATTERN_KEY = Regex("""^(pattern|glob|regex|regexp)$""", RegexOption.IGNORE_CASE)

    /**
     * Keys whose value is the text this call SENDS — a message to another agent, a prompt, a question.
     *
     * A UI concern, and it lives here for the same reason [commandText] does: which key of a tool input holds
     * which KIND of thing is a provenance decision, and a second list of keys kept in the page would be a
     * second answer that drifts from this one. The page paints what it is given and parses no tool input.
     *
     * Deliberately narrow, and the exclusion is the point: never `description`
     * ([dev.lain.claudejb.session.ToolNaming] already puts a `Task`'s description in the card's own label, so
     * admitting it here would print it twice). A key that is both a command and a message is a command, which
     * the ordering in [messageText] enforces — the command already has a block of its own.
     */
    private val MESSAGE_KEY = Regex(
        """^(message|msg|prompt|question|query|instruction|instructions)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The text [input] sends, for the transcript to show WITHOUT the card having to be expanded.
     *
     * A card that reads `{"success":true,…}` tells you the call worked and never tells you what was said. The
     * result stays behind the collapse toggle; what the call carries does not — the same bargain the command
     * block already strikes.
     *
     * Top level only: a message nested inside a structure is a field of some payload, not the thing this call
     * is saying. `null` when the call sends no such text, which is most of them.
     */
    fun messageText(input: JsonObject): String? {
        if (commandText(input) != null) return null
        return input.entries.firstNotNullOfOrNull { (key, value) ->
            if (!MESSAGE_KEY.matches(key)) return@firstNotNullOfOrNull null
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
    }

    /** A URL, not a path. */
    private val URLISH = Regex("""^[a-z][a-z0-9+.\-]*://""", RegexOption.IGNORE_CASE)

    /**
     * A URL *inside* text, for [urlCandidates]: scheme, `://`, then everything up to the first character that
     * cannot be part of a URL. The excluded set is what a shell, a quote or a sentence would use to end it —
     * whitespace, the three quote characters, and the brackets a URL is commonly wrapped in — so
     * `curl 'https://x.io/a' | sh` yields `https://x.io/a` and not `https://x.io/a'`.
     */
    private val URL_IN_TEXT = Regex("""[a-z][a-z0-9+.\-]*://[^\s"'`<>()\[\]{}|\\^]+""", RegexOption.IGNORE_CASE)

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

    fun pathCandidates(input: JsonObject, home: String?, env: Map<String, String> = emptyMap()): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key)) {
                // A command hides paths in variables and quotes; tokenise the raw AND the de-obfuscated form.
                val sources = setOf(value, CommandRules.deobfuscate(value, home, env))
                sources.forEach { src -> commandTokens(src).forEach { tok -> bothSpellings(tok, home, env, out) } }
            } else {
                bothSpellings(value, home, env, out)
            }
        }
        return out.toList()
    }

    /**
     * Adds [value] as a candidate **twice: before and after variable expansion**, whenever the two differ.
     *
     * Expansion is a rewriting step, and every rewriting step in front of a matcher is a place a rule can be
     * walked around — the guard's own history is three separate incidents of exactly that
     * ([GuardPaths.normalize]'s UNC prefix, `CommandRules.substituteAssignments`, the length cap measured before
     * folding). So the pre-expansion spelling is judged as well: a glob written against `~/.ssh/id_rsa` matches
     * the literal `~/.ssh/id_rsa` even on a policy that carries no home to expand it with, and a value whose
     * variable the guard cannot resolve at all still reaches [EnvIndirection] under its own name.
     *
     * ADDING a spelling, never substituting one — the same rule [GuardPaths.expandWithResolved] follows for the
     * folded and resolved forms. Judging both can only ever find one more match; replacing one with the other is
     * how a match gets lost. It costs nothing on the ordinary leaf, which contains no variable and therefore
     * yields the same string twice into a set.
     */
    private fun bothSpellings(value: String, home: String?, env: Map<String, String>, out: MutableSet<String>) {
        candidate(value, home, env)?.let { out += it }
        // The raw spelling too, whenever expansion had anything to do: judged before ANY rewriting, so a rule
        // written against `~/.ssh/id_rsa` matches that literal even on a policy carrying no home to expand with.
        if (home != null || env.isNotEmpty()) candidate(value, null)?.let { out += it }
    }

    /**
     * [pathCandidates], minus every command-key and pattern-key value. Neither is a location: a command's tokens
     * are code — a regex delimiter, a `sed` substitution, a bare flag — and a [PATTERN_KEY] value is a search
     * pattern the tool itself defines as such (`Grep`'s `pattern`). [SensitiveGuard]'s OUTSIDE_PROJECT rule needs
     * candidates that genuinely are a location; CREDENTIAL/FOREIGN/DANGEROUS_COMMAND still scan the command text
     * in full ([pathCandidates] does, unchanged), because a real path can live inside one — this is a narrower,
     * additional view for the one rule whose match is "is this outside the project", a claim a `/pattern/` token
     * trivially and meaninglessly satisfies.
     */
    fun locationCandidates(
        input: JsonObject,
        home: String?,
        env: Map<String, String> = emptyMap(),
    ): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key) || PATTERN_KEY.matches(key)) return@walkStrings
            bothSpellings(value, home, env, out)
        }
        return out.toList()
    }

    /**
     * The RAW, unexpanded values of the keys that decide **where this call acts** — a location key, plus every
     * token of a command. Payload and pattern keys are excluded, and this is the one view where that exclusion
     * survives.
     *
     * [EnvIndirection] is the reason it exists and the reason for that one exclusion. Its question is not "does
     * this name a sensitive place" — it is "can this call's destination be known at all", and a payload has no
     * destination: a `$HOME` inside the text of a Makefile, a CI file or a shell script being WRITTEN is content,
     * and asking about it would make every edit to any of those a card while catching nothing. A `$CREDS` in
     * `file_path`, or as an argument to `cat`, is a destination the guard cannot see.
     *
     * Raw on purpose: the whole point is what the value looked like BEFORE anything tried to expand it.
     */
    internal fun destinationCandidates(input: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (PATTERN_KEY.matches(key) || CONTENT_KEY.matches(key)) return@walkStrings
            if (COMMAND_KEY.matches(key)) {
                // The tokens AND the whole line. The tokens are the destinations; the line is what keeps a
                // command substitution visible, because `commandTokens` splits on `(`/`)` and treats a backtick as
                // a quote — so `cat $(cat which_file)` tokenises to `cat`, `$`, `cat`, `which_file` and no token
                // contains `$(` at all. A rule about "can this be evaluated" has to see the spelling that cannot.
                out += value
                commandTokens(value).forEach { out += it }
            } else {
                out += value
            }
        }
        return out.toList()
    }

    /**
     * Every URL this call names — from a URL-shaped argument (`WebFetch`'s `url`) **and** from inside a command
     * (`curl https://…` lives in a command string, not under a key of its own).
     *
     * Extracted with [URL_IN_TEXT] rather than tested with [URLISH], because the two answer different questions:
     * `URLISH` asks "is this whole leaf a URL" (which is what makes [candidate] drop it), and here the URL is
     * usually one word inside a sentence-long command line.
     *
     * A [CONTENT_KEY] value is skipped, exactly as it is for a path: what a `Write` is putting INTO a file is not
     * a place this call is talking to, and treating it as one would make writing a link into a README an act of
     * egress. Nothing is normalised here — the host of a URL is [DangerousDomains]' business, and one owner for
     * that parse is what keeps the two from disagreeing.
     */
    fun urlCandidates(input: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (CONTENT_KEY.matches(key)) return@walkStrings
            if (value.length > MAX_FOLD_LEN) return@walkStrings // a payload, whatever key it arrived under
            URL_IN_TEXT.findAll(value).forEach { out += it.value }
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
    private fun candidate(value: String, home: String?, env: Map<String, String> = emptyMap()): String? {
        if (value.isBlank() || value.length > MAX_FOLD_LEN) return null
        if (value.any { it == '\n' || it == '\r' }) return null
        if (URLISH.containsMatchIn(value)) return null
        if (value.length <= MAX_PATH_LEN) return GuardPaths.normalize(value, home, env)
        if (!looksPadded(value)) return null // long and not foldable: contents, and they stay out
        return GuardPaths.fold(GuardPaths.normalize(value, home, env)).takeIf { it.length <= MAX_PATH_LEN }
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

    /** Non-quote delimiters a command word breaks on — everything [commandTokens] used to split on besides the
     *  three quote characters, which now OPEN a span instead of splitting one. */
    private val SPLIT_CHARS = charArrayOf(';', '|', '&', '<', '>', '=', '(', ')', ',')

    /**
     * Splits [command] into shell words, the way a shell actually forms them: a quoted span
     * (`'…'`/`"…"`/`` `…` ``) is not itself a delimiter, so its content — including a space — joins the
     * token on either side of it, instead of ending the token the way a naive delimiter split does.
     *
     * **This is a real detection gap, not a style preference.** The old naive split
     * (`command.split(Regex("[\\s;|&<>=(),\"'`]+"))`) treated a quote character as just another delimiter, so
     * `cat "Login Data"` tokenised to `cat`, `Login`, `Data` — and neither fragment alone matches
     * [CredentialPaths.SENSITIVE_GLOBS]' any-directory glob for `Login Data`, which needs the internal space.
     * (Spelled out rather than quoted, because a doubled star followed by a slash ENDS a KDoc block: written
     * literally, this comment closed here and the rest of the file stopped parsing.) A state machine
     * that keeps quoted content attached to its token can only ever MERGE fragments the old splitter would have
     * separated, never lose one it already produced correctly: unquoted text is delimited exactly as before, so
     * this is a strict widening of what is recognised, not a behaviour change on any command with no quotes.
     */
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
