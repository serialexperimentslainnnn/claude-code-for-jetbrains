package dev.lain.claudejb.permission

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object GuardPaths {

    fun normalize(path: String, home: String?, env: Map<String, String> = emptyMap()): String {
        val expanded = expandEnv(path.trim(), home, env)
        val unc = startsWithDoubleSeparator(expanded)
        val collapsed = expanded.replace('\\', '/').replace(MULTI_SEPARATOR, "/")
        val result = if (unc) "/$collapsed" else collapsed
        return if (result.length > 1) result else result
    }

    private val MULTI_SEPARATOR = Regex("/{2,}")

    private val BARE_HOME = Regex("""\x24HOME(?![A-Za-z0-9_])""")

    private fun startsWithDoubleSeparator(value: String): Boolean =
        value.length >= 2 && (value[0] == '\\' || value[0] == '/') && value[1] == value[0]

    internal fun expandEnv(value: String, home: String?, env: Map<String, String> = emptyMap()): String {
        var v = value
        if (!home.isNullOrBlank()) {
            val h = home.replace('\\', '/').trimEnd('/')
            v = v.replace("\${HOME}", h).replace(BARE_HOME, h)
                .replace("\$env:USERPROFILE", h, ignoreCase = true)
                .replace("%USERPROFILE%", h, ignoreCase = true)
                .replace("%HOMEPATH%", h, ignoreCase = true)
                .replace("%APPDATA%", "$h/AppData/Roaming", ignoreCase = true)
                .replace("%LOCALAPPDATA%", "$h/AppData/Local", ignoreCase = true)
            if (v == "~") {
                v = h
            } else if (v.startsWith("~/") || v.startsWith("~\\")) {
                v = h + "/" + v.substring(2)
            }
        }
        return if (env.isEmpty()) v else substituteEnv(v, env)
    }

    private fun substituteEnv(value: String, env: Map<String, String>): String = expandLoop(value, env).value

    internal fun exceedsEnvDepth(value: String, home: String?, env: Map<String, String>): Boolean {
        if (env.isEmpty() && home.isNullOrBlank()) return false
        return expandLoop(expandEnv(value, home), env).exhausted
    }

    private class Expansion(val value: String, val exhausted: Boolean)

    private fun expandLoop(value: String, env: Map<String, String>): Expansion {
        var v = value
        repeat(MAX_ANALYSIS_DEPTH) {
            val next = ENV_REF.replace(v) { m ->
                val name = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
                lookup(env, name) ?: m.value
            }
            if (next == v) return Expansion(v, exhausted = false)
            v = next
        }
        return Expansion(v, exhausted = ENV_REF.containsMatchIn(v))
    }

    private fun lookup(env: Map<String, String>, name: String): String? =
        env[name] ?: env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * A variable reference in any of the four spellings the guard understands.
     *
     * **The dollar is spelled `\x24`, and it has to be spelled as something.** A literal one cannot appear in this
     * file at all — not in the pattern and not in this sentence. Backslash-dollar inside a raw string is a
     * backslash followed by a TEMPLATE, so the pattern does not compile (there is no variable called `env`); a
     * dollar in a character class does compile, but ktlint's own parser then reports `Identifier expected` and
     * refuses the file, prose included; and the escaped-template spelling reads as noise in the middle of a
     * pattern. `\x24` is the regex engine's own way to write the character, so every tool in the chain agrees.
     */
    private val ENV_REF = Regex(
        """\x24\{([A-Za-z_][A-Za-z0-9_]*)\}""" +
            """|\x24env:([A-Za-z_][A-Za-z0-9_]*)""" +
            """|\x24([A-Za-z_][A-Za-z0-9_]*)""" +
            """|%([A-Za-z_][A-Za-z0-9_]*)%""",
        RegexOption.IGNORE_CASE,
    )

    /** The folded absolute spelling of a candidate, anchoring a relative one at the project root the way the
     *  shell anchors it at the working directory — so `../../../etc/passwd` is judged as `/etc/passwd`, not
     *  waved past for lacking a leading slash. Null when there is nothing to anchor against or the token still
     *  carries an unexpanded `~`/`$`/`%` prefix. */
    internal fun absoluteForm(path: String, projectRoot: String?): String? = when {
        isAbsolute(path) -> fold(path)
        path.isEmpty() || path[0] in UNEXPANDED_PREFIXES -> null
        projectRoot.isNullOrBlank() -> null
        else -> fold("$projectRoot/$path")
    }

    /** Containment is decided case-sensitively where the filesystem is, and case-insensitively where it is not.
     *  Windows and the default macOS volume fold case, so `C:/Proj` and `c:/proj` are one directory and must both
     *  read as inside. Linux does not, so `/home/me/PROJ` is a different directory from `/home/me/proj` — folding
     *  case there let a sibling of the project count as part of it, which exempted it from the rules that only
     *  apply outside. */
    internal fun under(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        if (r.isEmpty()) return false
        val fold = caseInsensitiveFilesystem || isDriveRooted(r)
        return path.equals(r, ignoreCase = fold) || path.startsWith("$r/", ignoreCase = fold)
    }

    private val caseInsensitiveFilesystem: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().let { "win" in it || "mac" in it || "darwin" in it }

    private fun lexicalForm(path: String, projectRoot: String?): String? {
        if (path.isEmpty() || path[0] in UNEXPANDED_PREFIXES) return null
        val absolute = when {
            isAbsolute(path) -> path
            projectRoot.isNullOrBlank() -> return null
            else -> "$projectRoot/$path"
        }
        return fold(absolute).takeIf { it != path }
    }

    private const val UNEXPANDED_PREFIXES = "~\$%"

    internal fun isAbsolute(path: String): Boolean = path.startsWith("/") || isDriveRooted(path)

    private fun isDriveRooted(path: String): Boolean = path.length > 2 && path[1] == ':' && path[2] == '/'

    private fun rootPrefix(path: String): String = when {
        path.startsWith("//") -> "//"
        path.startsWith("/") -> "/"
        isDriveRooted(path) -> path.substring(0, 3)
        else -> ""
    }

    internal fun fold(path: String): String {
        val prefix = rootPrefix(path)
        val segments = ArrayList<String>()
        for (segment in path.substring(prefix.length).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> climb(segments, prefix)
                else -> segments.add(segment)
            }
        }
        val body = segments.joinToString("/")
        return if (prefix.isEmpty()) body.ifEmpty { "." } else prefix + body
    }

    private fun climb(segments: MutableList<String>, prefix: String) {
        when {
            segments.isNotEmpty() && segments.last() != ".." -> segments.removeAt(segments.lastIndex)
            prefix.isEmpty() -> segments.add("..")
            else -> Unit
        }
    }

    internal fun expandWithResolved(paths: List<String>, policy: SensitiveGuard.Policy): List<String> {
        val projectRoot = policy.projectRoot?.let { normalize(it, policy.home) }
        val out = LinkedHashSet<String>()
        val targets = LinkedHashSet<String>()
        for (p in paths) {
            out += p
            lexicalForm(p, projectRoot)?.let { out += it }
            if (looksResolvable(p)) targets += anchored(p, projectRoot)
        }
        val resolver = policy.pathResolver ?: return out.toList()
        val deadline = System.nanoTime() + RESOLVE_BUDGET_MS * NANOS_PER_MS
        for (t in targets) {
            val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MS
            if (remainingMs <= 0) break
            resolveWithTimeout(resolver, t, minOf(RESOLVE_TIMEOUT_MS, remainingMs))
                ?.let { out += normalize(it, policy.home) }
        }
        return out.toList()
    }

    private fun anchored(path: String, projectRoot: String?): String = when {
        isAbsolute(path) || projectRoot.isNullOrBlank() -> path
        path[0] in UNEXPANDED_PREFIXES -> path
        else -> "$projectRoot/$path"
    }

    private fun looksResolvable(token: String): Boolean =
        token.startsWith("~") || token.contains('/') || token.contains('\\')

    private fun resolveWithTimeout(resolver: (String) -> String?, path: String, timeoutMs: Long): String? {
        val future = runCatching { resolverExecutor.submit(Callable { resolver(path) }) }.getOrNull() ?: return null
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    private const val RESOLVE_TIMEOUT_MS = 200L

    private const val RESOLVE_BUDGET_MS = 500L

    private const val NANOS_PER_MS = 1_000_000L

    private const val MAX_RESOLVER_THREADS = 8

    private val resolverExecutor = Executors.newFixedThreadPool(MAX_RESOLVER_THREADS) { r ->
        Thread(r, "SensitiveGuard-resolver").apply { isDaemon = true }
    }
}
