package dev.lain.claudejb.permission

object TempDirs {

    private val TEMP_ROOTS: List<Regex> = listOf(
        Regex("""^(?:[A-Za-z]:)?(?:/private)?(?:/var)?/tmp/""", RegexOption.IGNORE_CASE),
        Regex("""^(?:/private)?/var/folders/""", RegexOption.IGNORE_CASE),
        Regex("""^(?:[A-Za-z]:)?(?:/mnt/[A-Za-z])?/windows/temp/""", RegexOption.IGNORE_CASE),
        Regex("""^(?:[A-Za-z]:)?(?:/mnt/[A-Za-z])?/users/[^/]+/appdata/local/temp/""", RegexOption.IGNORE_CASE),
    )

    internal fun tempHit(paths: List<String>): String? = paths.firstOrNull { isTemp(it) }

    fun isTemp(path: String): Boolean = matches(path) || matches(GuardPaths.fold(path))

    private fun matches(path: String): Boolean {
        if (path.isEmpty()) return false
        val probe = if (path.endsWith("/")) path else "$path/"
        return TEMP_ROOTS.any { it.containsMatchIn(probe) }
    }
}
