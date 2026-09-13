package dev.lain.claudejb.model.permission.scan

internal object ContainerMounts {

    private val CONTAINER_VERBS = setOf("docker", "podman", "nerdctl", "kubectl", "oc")

    private val VOLUME_FLAGS = setOf("-v", "--volume")

    private val CONTAINER_SIDE_KEYS = listOf("target=", "destination=", "dst=")

    private val HOST_SIDE_KEYS = listOf("source=", "src=")

    private val WRITES_HOST = Regex("""\b(?:kubectl|oc)\s+cp\b""", RegexOption.IGNORE_CASE)

    private val CONTAINER_COMMAND = Regex(
        """^\s*(?:\w+=\S*\s+)*(?:\S*/)?(?:docker|podman|nerdctl|kubectl|oc)\b""",
        RegexOption.IGNORE_CASE,
    )

    internal fun isContainerTool(verb: String?): Boolean = verb != null && verb in CONTAINER_VERBS

    internal fun writesHost(command: String): Boolean = WRITES_HOST.containsMatchIn(command)

    internal fun hostSides(command: String): List<String> {
        val out = ArrayList<String>()
        for (segment in command.split(';', '|', '&', '\n')) {
            if (!CONTAINER_COMMAND.containsMatchIn(segment)) continue
            var previous: String? = null
            for (token in segment.trim().split(Regex("""\s+""")).map { it.trim('\'', '"') }) {
                val host = hostSide(previous, token)
                if (host != null && host != token) out += host
                previous = token
            }
        }
        return out
    }

    internal fun isMountKey(token: String): Boolean =
        (CONTAINER_SIDE_KEYS + HOST_SIDE_KEYS).any { token.startsWith(it, ignoreCase = true) }

    internal fun hostSide(previous: String?, token: String): String? {
        if (CONTAINER_SIDE_KEYS.any { token.startsWith(it, ignoreCase = true) }) return null
        HOST_SIDE_KEYS.firstOrNull { token.startsWith(it, ignoreCase = true) }?.let { return token.substring(it.length) }
        val spec = when {
            token.startsWith("--volume=") || token.startsWith("-v=") -> token.substringAfter('=')
            previous in VOLUME_FLAGS -> token
            else -> return token
        }
        val cut = mountSeparator(spec)
        if (cut < 0) return null
        return spec.substring(0, cut).ifEmpty { null }
    }

    private fun mountSeparator(spec: String): Int {
        val start = if (isDriveLetter(spec)) 2 else 0
        return spec.indexOf(':', start)
    }

    private fun isDriveLetter(spec: String): Boolean =
        spec.length > 2 && spec[1] == ':' && spec[0].isLetter() && (spec[2] == '/' || spec[2] == '\\')
}
