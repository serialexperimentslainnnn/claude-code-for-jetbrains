package dev.lain.claudejb.permission

object SystemDevices {

    private val EXEMPT_DEVICES = setOf("/dev/null", "/dev/urandom")

    private val DEVICE_PATTERNS: List<Regex> = listOf(
        Regex("""^/dev(/|$)""", RegexOption.IGNORE_CASE),
        Regex("""^//\./""", RegexOption.IGNORE_CASE),
        Regex("""^/proc/\d+/mem$""", RegexOption.IGNORE_CASE),
        Regex("""^/proc/(kcore|kmem|kallsyms)$""", RegexOption.IGNORE_CASE),
    )

    internal fun deviceHit(paths: List<String>): String? = paths.firstOrNull { isSystemDevice(it) }

    fun isSystemDevice(path: String): Boolean {
        if (path.isBlank()) return false
        val folded = GuardPaths.fold(path)
        if (folded.lowercase() in EXEMPT_DEVICES) return false
        return matches(path) || matches(folded)
    }

    private fun matches(path: String): Boolean = DEVICE_PATTERNS.any { it.containsMatchIn(path) }
}
