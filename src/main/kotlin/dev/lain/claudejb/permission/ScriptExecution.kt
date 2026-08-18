package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * [SecurityRule.SCRIPT_EXECUTION] — **the files a call runs**, so the guard can READ them and judge what is
 * inside instead of refusing because it has not looked.
 *
 * ### The laundering path this closes, and it is the cleanest one there was
 * Every rule here judges the text of the `can_use_tool` request, and a file's contents are not in that text:
 *
 * ```
 * printf 'cat ~/.ssh/id_rsa\n' > /tmp/x.sh   # a shell file write — one card, and a boring-looking one
 * source /tmp/x.sh                            # …and nothing in this package ever saw the payload
 * ```
 *
 * Two calls, and the dangerous one is invisible: `source /tmp/x.sh` contains no credential glob, no dangerous
 * command, no foreign path. Same for `bash x.sh`, `python x.py`, `./deploy` and `powershell -File x.ps1`, and the
 * write is not even needed when the file arrived with the repository.
 *
 * ### Analysis, not a blanket refusal — this is what the rule actually does
 * This object's job is only to say WHICH files a command runs. `SensitiveGuard.classify` then reads each one
 * through [SensitiveGuard.Policy.fileReader] and **judges its contents with the whole rule set**, recursively (a
 * script that sources a script), bounded by [MAX_ANALYSIS_DEPTH]. So:
 *  - a script that trips nothing **runs unasked** — `./gradlew build` costs no card, which is what makes this
 *    rule survivable at all;
 *  - a script that dumps a key is refused **as a key dump**, at that rule's severity, naming the script it came
 *    from — strictly more informative than "you ran a script";
 *  - a script that **cannot be read** (missing, unreadable, or no reader configured) is opaque, and only then is
 *    the answer a card;
 *  - indirection deeper than the bound, or cyclic, is [SecurityRule.RECURSION_LIMIT] — a hard block, because
 *    reaching the bound is the finding.
 *
 * **Inside a script, the two OPAQUE rules do not apply** (see `SensitiveGuard.classify`), and that is deliberate:
 * a build wrapper is *made* of `$JAVACMD` and `$(cd …)`, so asking "could every variable be resolved" of a file
 * the agent did not write in this request would put a card on every build. The stated cost: a `cat $CREDS` inside
 * a script, with `CREDS` set somewhere the plugin cannot read, is not caught. Everything the guard CAN resolve —
 * including from the launch environment — still is.
 *
 * ### The five shapes, and why each is a shape rather than a name
 *  1. **Sourcing** — `source f`, `. f`. The file's code runs in the current shell.
 *  2. **An interpreter with a FILE argument** — `bash f`, `python3 f`, `node f`, `pwsh -File f`. Inline code
 *     (`-c`, `-e`, `--eval`, `-Command`) is deliberately NOT one: that code is IN the request, so the rest of this
 *     package already judges it, and flagging it would punish the transparent spelling.
 *  3. **A relative launch** — `./x`, `../x`: the shape of running something that lives with the project.
 *  4. **A file with a script EXTENSION in command position** — `x.sh`, `deploy.py`, `task.ps1`.
 *  5. **An absolute path in command position outside the system's own binary directories** ([SYSTEM_BIN_DIRS]) —
 *     `/tmp/x`, `/home/me/proj/tool`. Extensionless and freshly written is exactly how shape 4 is avoided, and the
 *     allowlist is what keeps `/usr/bin/git status` out of it: a directory a package manager owns is a boundary
 *     the agent cannot move with a `Write`, unlike a filename, which it chooses.
 */
object ScriptExecution {

    /** `source f` and `. f` — POSIX sourcing, anchored at a shell command position. */
    private val SOURCED = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:source|\.)\s+(\S+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Interpreters that take a script FILE as their first non-flag argument. */
    private const val INTERPRETERS =
        """sh|bash|zsh|ksh|dash|ash|fish|csh|tcsh|python\d?(?:\.\d+)?|perl|ruby|node|deno|bun|php|""" +
            """pwsh|powershell|osascript|Rscript|lua|julia|tclsh|groovy|scala|kotlin|elixir|escript"""

    /**
     * Inline-code flags: the spelling where the code IS in the request and every other rule already judges it.
     * Matched anywhere in the command's own segment, so `python -u -c '…'` is exempt too.
     */
    private val INLINE_CODE = Regex(
        """(?:^|\s)(?:-c|-e|-E|--eval|--command|-Command|-EncodedCommand|--exec)(?:\s|=|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** `bash x.sh`, `python3 tools/build.py`, `pwsh -File x.ps1` — the interpreter plus the rest of its segment. */
    private val INTERPRETED = Regex(
        """(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?($INTERPRETERS)\b([^;&|\n]*)""",
        RegexOption.IGNORE_CASE,
    )

    /** Extensions that only ever name something meant to be RUN. */
    private val SCRIPT_SUFFIXES = setOf(
        "sh", "bash", "zsh", "ksh", "fish", "csh", "command",
        "py", "pyw", "pl", "rb", "js", "mjs", "cjs", "ts", "php", "lua", "r", "jl", "tcl", "groovy",
        "ps1", "psm1", "bat", "cmd", "vbs", "wsf", "jse", "scpt", "exp", "awk", "sed",
    )

    /**
     * Where the system's own programs live. A path under one of these is something a package manager put there,
     * which is a boundary the agent cannot cross with a file write — unlike a name, which it can choose.
     */
    private val SYSTEM_BIN_DIRS = listOf(
        "/usr/bin/", "/bin/", "/usr/sbin/", "/sbin/", "/usr/local/bin/", "/usr/local/sbin/",
        "/opt/homebrew/bin/", "/opt/homebrew/sbin/", "/snap/bin/", "/usr/libexec/",
        "c:/windows/system32/", "c:/windows/", "c:/program files/", "c:/program files (x86)/",
    )

    /**
     * Every script [input] runs, normalised and anchored so the reader can open it.
     *
     * Relative paths are anchored at the project root, which is the working directory the binary is launched in
     * (`ClaudeProcess`), so `./gradlew` names the file it will really execute rather than something relative to
     * whatever the IDE's own working directory happens to be.
     */
    internal fun scriptsIn(input: JsonObject, policy: SensitiveGuard.Policy): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in ToolInputScanner.commandCandidates(input)) {
            val command = CommandRules.deobfuscate(raw, policy.home, policy.envValues)
            SOURCED.findAll(command).forEach { m -> anchor(m.groupValues[1], policy)?.let { out += it } }
            interpretedFiles(command).forEach { f -> anchor(f, policy)?.let { out += it } }
            launchedFiles(command).forEach { f -> anchor(f, policy)?.let { out += it } }
        }
        return out.toList()
    }

    /** One script path as the reader will be asked for it, or null when it is not a usable path at all. */
    private fun anchor(rawPath: String, policy: SensitiveGuard.Policy): String? {
        val token = rawPath.trim().trim('\'', '"')
        if (token.isEmpty()) return null
        val normalized = GuardPaths.normalize(token, policy.home, policy.envValues)
        if (normalized.isBlank()) return null
        if (GuardPaths.isAbsolute(normalized)) return GuardPaths.fold(normalized)
        val root = policy.projectRoot?.let { GuardPaths.normalize(it, policy.home) } ?: return null
        return GuardPaths.fold("$root/${normalized.removePrefix("./")}")
    }

    /** The file each interpreter invocation is handed, skipping the ones handed inline code instead. */
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

    /** Files launched as the command itself: relative, script-suffixed, or absolute outside the system's own bins. */
    private fun launchedFiles(command: String): List<String> = commandWords(command).filter { isLaunchedFile(it) }

    /** Is [word] — sitting in command position — a file being run? */
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

    /**
     * The word in COMMAND position of each segment — after a `;`/`|`/`&`/newline, and after a leading `sudo` or
     * an inline `VAR=value` prefix.
     *
     * Only the first word of a segment, deliberately: an ordinary argument that happens to be a path
     * (`cp ./a ./b`, `cat ./notes.md`) is not an execution, and treating every argument as one would make this
     * rule fire on most commands rather than on the ones that run something.
     */
    private fun commandWords(command: String): List<String> =
        command.split(';', '|', '&', '\n')
            .mapNotNull { segment ->
                segment.trim().split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
                    .dropWhile { it.equals("sudo", ignoreCase = true) || ASSIGNMENT.matches(it) }
                    .firstOrNull()
            }

    /** `VAR=value` sitting in front of the command it applies to. */
    private val ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")
}
