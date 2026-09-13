package dev.lain.claudejb.model.permission.paths

import dev.lain.claudejb.model.permission.SensitiveGuard
import dev.lain.claudejb.model.permission.vocab.MAX_ANALYSIS_DEPTH
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object GuardPaths {

    fun normalize(path: String, home: String?, env: Map<String, String> = emptyMap()): String {
        val expanded = LONG_PATH_PREFIX.replace(expandEnv(path.trim(), home, env), "")
        val unc = startsWithDoubleSeparator(expanded)
        val collapsed = expanded.replace('\\', '/').replace(MULTI_SEPARATOR, "/")
        return if (unc) "/$collapsed" else collapsed
    }

    private val MULTI_SEPARATOR = Regex("/{2,}")

    private val LONG_PATH_PREFIX = Regex("""^[\\/]{2}\?(?:[\\/](?![Uu][Nn][Cc][\\/])|(?=[A-Za-z]:))""")

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

    private val ENV_REF = Regex(
        """\x24\{([A-Za-z_][A-Za-z0-9_]*)\}""" +
            """|\x24env:([A-Za-z_][A-Za-z0-9_]*)""" +
            """|\x24([A-Za-z_][A-Za-z0-9_]*)""" +
            """|%([A-Za-z_][A-Za-z0-9_]*)%""",
        RegexOption.IGNORE_CASE,
    )

    internal fun absoluteForm(path: String, projectRoot: String?): String? = when {
        isAbsolute(path) -> fold(path)
        path.isEmpty() || path[0] in UNEXPANDED_PREFIXES -> null
        projectRoot.isNullOrBlank() -> null
        else -> fold("$projectRoot/$path")
    }

    private val SHORT_NAME = Regex("""(?<=[A-Za-z0-9])~\d+(?=/|$)""")

    internal fun under(path: String, root: String, caseInsensitive: Boolean = false): Boolean {
        val r = root.trimEnd('/')
        if (r.isEmpty() || SHORT_NAME.containsMatchIn(path)) return false
        val fold = caseInsensitive || isDriveRooted(r)
        return path.equals(r, ignoreCase = fold) || path.startsWith("$r/", ignoreCase = fold)
    }

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

    internal fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || isDriveRooted(path) || isDrivePrefixed(path)

    private fun isDriveRooted(path: String): Boolean = path.length > 2 && path[1] == ':' && path[2] == '/'

    private fun isDrivePrefixed(path: String): Boolean =
        path.length >= 2 && path[1] == ':' && (path[0] in 'A'..'Z' || path[0] in 'a'..'z')

    private fun rootPrefix(path: String): String = when {
        path.startsWith("//") -> "//"
        path.startsWith("/") -> "/"
        isDriveRooted(path) -> path.substring(0, 3)
        isDrivePrefixed(path) -> path.substring(0, 2)
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
            segments.isNotEmpty() && segments.last() != ".." && !SHORT_NAME.containsMatchIn(segments.last()) ->
                segments.removeAt(segments.lastIndex)

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

    private fun resolveWithTimeout(resolver: (String) -> String?, path: String, timeoutMs: Long): String? =
        withTimeout(timeoutMs) { resolver(path) }

    internal fun <T> withTimeout(timeoutMs: Long, block: () -> T?): T? {
        val future = runCatching { resolverExecutor.submit(Callable { block() }) }.getOrNull() ?: return null
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    internal const val RESOLVE_TIMEOUT_MS = 200L

    private const val RESOLVE_BUDGET_MS = 500L

    private const val NANOS_PER_MS = 1_000_000L

    private const val MAX_RESOLVER_THREADS = 8

    private val resolverExecutor = Executors.newFixedThreadPool(MAX_RESOLVER_THREADS) { r ->
        Thread(r, "SensitiveGuard-resolver").apply { isDaemon = true }
    }
}
