package dev.lain.claudejb.model.permission.rules

import dev.lain.claudejb.model.permission.paths.GuardPaths
import dev.lain.claudejb.model.permission.scan.ToolInputScanner
import kotlinx.serialization.json.JsonObject

object ContainerEscape {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val DANGEROUS_CAPS =
        "(ALL|SYS_ADMIN|SYS_PTRACE|DAC_READ_SEARCH|DAC_OVERRIDE|SYS_MODULE|NET_ADMIN|SYS_RAWIO|SYS_CHROOT" +
            "|SYS_BOOT|SYS_TIME|BPF|PERFMON|SETUID|SETGID|SETFCAP|LINUX_IMMUTABLE)"

    private val VECTORS: List<Regex> = listOf(
        re("""\bnsenter\b[^|;&]*(-t|--target)[=\s]*1\b"""),
        re("""\bnsenter\b[^|;&]*/proc/1/ns/"""),
        re("""(?:-v|--volume)[=\s]+/:"""),
        re("""--mount\b[^|;&]*\b(source|src)=/(,|\s|$)"""),
        re("""\b(docker|podman|nerdctl|ctr|crictl|kubectl|oc)\b[^|;&]*--privileged\b"""),
        re("""\boc\b[^|;&]*\bdebug\b[^|;&]*--as-root\b"""),
        re("""\boc\s+adm\s+policy\s+add-scc-to-(user|group)\s+privileged\b"""),
        re("""(?:-v|--volume|--mount)\b[^|;&]*docker\.sock"""),
        re("""\bprivileged"?\s*:\s*"?true\b"""),
        re("""\ballowPrivilegeEscalation"?\s*:\s*"?true\b"""),
        re("""\bcapabilities"?\s*:\s*\{\s*"?add"?\s*:\s*\[[^\]]*\b""" + DANGEROUS_CAPS + """\b"""),
        re("""\bhostPID"?\s*:\s*"?true\b"""),
        re("""--cap-add[=\s]+""" + DANGEROUS_CAPS + """\b"""),
        re("""--security-opt[=\s]+(seccomp|apparmor)=unconfined\b"""),
        re("""--security-opt[=\s]+(label[=:]disable|no-new-privileges=false)\b"""),
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
