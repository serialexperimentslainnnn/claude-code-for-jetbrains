package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object ContainerEscape {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private val VECTORS: List<Regex> = listOf(
        re("""\bnsenter\b[^|;&]*(-t|--target)[=\s]*1\b"""),
        re("""\bnsenter\b[^|;&]*/proc/1/ns/"""),
        re("""(?:-v|--volume)[=\s]+/:"""),
        re("""--mount\b[^|;&]*\b(source|src)=/(,|\s|$)"""),
        re("""\b(docker|podman|nerdctl|ctr|crictl)\b[^|;&]*--privileged\b"""),
        re("""(?:-v|--volume|--mount)\b[^|;&]*docker\.sock"""),
        re("""\bprivileged"?\s*:\s*"?true\b"""),
        re("""\bhostPID"?\s*:\s*"?true\b"""),
        re("""--cap-add[=\s]+(ALL|SYS_ADMIN|SYS_PTRACE|DAC_READ_SEARCH|SYS_MODULE)\b"""),
        re("""--security-opt[=\s]+(seccomp|apparmor)=unconfined\b"""),
        re("""--(pid|network|net|ipc)[=\s]+host\b"""),
        re("""--(userns|cgroupns)[=\s]+host\b"""),
        re("""(?:-v|--volume)[=\s]+/(proc|sys|dev|var/run|run):"""),
        re("""\bhost(Network|IPC)"?\s*:\s*"?true\b"""),
        re("""\bhostPath"?\s*:"""),
        re("""\brelease_agent\b"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
