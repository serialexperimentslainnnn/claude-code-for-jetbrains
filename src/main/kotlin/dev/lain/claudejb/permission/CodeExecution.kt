package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * The [SecurityCategory.CODE_EXECUTION] family — **turning the machine into something that runs someone else's
 * code, now or later.** None of these is a path or a domain, so no location or egress rule sees them; they are
 * recognised by the SHAPE of the command, like [CommandRules] and [DestructiveCommands].
 *
 * Three vectors, one atom each:
 *  - [SecurityRule.PACKAGE_INSTALL_HOOK] — a package manager installing an arbitrary package runs that package's
 *    post-install script, i.e. its author's code. The primary software-supply-chain attack (A03), and the middle
 *    of the injected-instruction chain: land a dependency, let its install hook run.
 *  - [SecurityRule.PERSISTENCE_MECHANISM] — cron/at/systemd timers and git hooks make code run AFTER this session,
 *    outside anything the user is watching. How an attacker keeps access.
 *  - [SecurityRule.CODE_INJECTION] — `LD_PRELOAD=`/`DYLD_INSERT_LIBRARIES=` force a library into another
 *    process's memory, bypassing what that program was trusted to do.
 *
 * Matched after [CommandRules.deobfuscate] and location-independent, exactly like the other command families.
 * The negatives are load-bearing: `npm test`, `npm run build`, `pip list`, `cargo build`, `git commit`, an
 * ordinary `crontab -l` (listing, not installing) must all keep running.
 */
object CodeExecution {

    /** A code-execution/persistence match: which vector rule tripped, and the excerpt to quote back. */
    internal data class Hit(val rule: SecurityRule, val text: String)

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private val VECTORS: List<Pair<SecurityRule, Regex>> = listOf(
        // Package install (not test/build/list/run) — the install verb specifically, which is what runs hooks.
        // `add` covers yarn/pnpm/poetry/bundle; `install`/`i` covers npm/pip/gem/cargo. A bare `npm install` with
        // no package (restore from lockfile) is included on purpose: a poisoned lockfile is the same attack, and
        // switching PACKAGE_INSTALL_HOOK off is the vector-scoped escape hatch for a project where restore is
        // routine.
        SecurityRule.PACKAGE_INSTALL_HOOK to
            re("""\b(npm|pnpm|yarn|bun)\b[^|;&]*\b(install|add|i)\b"""),
        SecurityRule.PACKAGE_INSTALL_HOOK to re("""\bpip3?\b[^|;&]*\binstall\b"""),
        SecurityRule.PACKAGE_INSTALL_HOOK to re("""\b(gem|cargo|go|composer|poetry|bundle)\b[^|;&]*\b(install|add|get)\b"""),
        SecurityRule.PACKAGE_INSTALL_HOOK to re("""\bcurl\b[^|]*\|\s*(sudo\s+)?(npm|pip3?|gem|bash|sh)\b"""),
        // Persistence — a mechanism that runs code again after the session. crontab INSTALL only — feeding it a
        // file (`crontab evil`) or stdin (`… | crontab -`), or a redirect (`crontab < f`). NOT the read-only flags
        // `crontab -l`/`-r`/`-e`, which list/remove/edit and are ordinary. The alternation is: `-` as a standalone
        // arg (stdin), or a first non-dash character (a filename or a `<` redirect).
        SecurityRule.PERSISTENCE_MECHANISM to re("""\bcrontab\b\s+(-(?=\s|$)|[^-\s])"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""(?:^|[;&|\n]\s*)at\s+\w"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\bsystemctl\b[^|;&]*\b(enable|start)\b[^|;&]*\.timer\b"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\bgit\b[^|;&]*\bconfig\b[^|;&]*\bcore\.hooksPath\b"""),
        SecurityRule.PERSISTENCE_MECHANISM to re("""\.git/hooks/"""),
        // Library / env code injection into a process.
        SecurityRule.CODE_INJECTION to re("""\b(LD_PRELOAD|LD_LIBRARY_PATH|DYLD_INSERT_LIBRARIES)\s*="""),
    )

    /** The first code-execution/persistence vector the command trips, or null. */
    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    /** The first vector [candidate] trips, or null — kept separate so [hit] stays a flat pipeline (detekt nesting). */
    private fun firstVector(candidate: String): Hit? =
        VECTORS.firstNotNullOfOrNull { (rule, pattern) ->
            pattern.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) }
        }
}
