package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object DisableDefences {

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val MATCH_EXCERPT_CHARS = 120

    private const val AT = """(?:^|[;&|\n]\s*|\bthen\s+|\bdo\s+)(?:\S*/)?"""

    private const val SEC_SVC =
        "auditd|firewalld|apparmor|ufw|firewall|snort|falco|osquery|clamav|clamav-daemon|clamd|" +
            "wazuh-agent|ossec|crowdstrike|falcon-sensor|sysmon"

    private const val SEC_PROC =
        "auditd|falcod|falco|falcon-sensor|falcon|wazuh-agent|wazuh|clamd|clamav|osqueryd|" +
            "crowdstrike|sysmon|ossec|MsMpEng|SentinelAgent|CSFalconService"

    private val VECTORS: List<Regex> = listOf(
        re(AT + """setenforce\s+0\b"""),
        re(AT + """semanage\s+permissive\b"""),
        re("""\bsystemctl\b[^|;&]*\b(stop|disable|mask|kill)\b[^|;&]*\b($SEC_SVC)\b"""),
        re("""\bservice\b[^|;&]*\b($SEC_SVC)\b[^|;&]*\bstop\b"""),
        re("""\b(pkill|killall)\b[^|;&]*\b($SEC_PROC)\b"""),
        re("""\btaskkill\b[^|;&]*\b(MsMpEng|CSFalcon\w*|SentinelAgent|Sysmon|CylanceSvc|windefend)\b"""),
        re(AT + """ufw\s+disable\b"""),
        re("""\biptables\b[^|;&]*(-F|--flush)\b"""),
        re("""\bnft\b[^|;&]*\bflush\s+ruleset\b"""),
        re("""\bnft\b[^|;&]*\bdelete\s+table\b"""),
        re("""\bpfctl\b[^|;&]*-d(?=\s|$|[;&|])"""),
        re("""\bfirewall-cmd\b[^|;&]*(--set-default-zone=trusted|--panic-off)\b"""),
        re(AT + """(aa-disable|aa-teardown)\b"""),
        re("""\bauditctl\b[^|;&]*(-e\s+0|-D)\b"""),
        re("""\bchattr\b[^|;&]*[+-][aiu]"""),
        re("""\bsysctl\b[^|;&]*(yama\.ptrace_scope|randomize_va_space|kptr_restrict)=0"""),
        re("""\bspctl\b[^|;&]*--(master-disable|global-disable)\b"""),
        re("""\bcsrutil\b[^|;&]*\bdisable\b"""),
        re("""\bSet-MpPreference\b[^|;&]*-Disable\w+"""),
        re("""\bSet-MpPreference\b[^|;&]*-MAPSReporting\s+Disabled\b"""),
        re("""\bAdd-MpPreference\b[^|;&]*-ExclusionPath\b"""),
        re("""\bMpCmdRun\b[^|;&]*-RemoveDefinitions\b"""),
        re("""\bnetsh\b[^|;&]*advfirewall[^|;&]*\bstate\s+off\b"""),
        re("""\bauditpol\b[^|;&]*/(clear|remove)\b"""),
        re("""\bfltmc\b[^|;&]*\bunload\b"""),
        re("""\bsysmon(64)?\b[^|;&]*\s-u(?=\s|$|[;&|])"""),
        re("""\b(sc|Stop-Service)\b[^|;&]*\b(windefend|Sense|WdNisSvc)\b"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): String? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstVector(candidate) }

    private fun firstVector(candidate: String): String? =
        VECTORS.firstNotNullOfOrNull { it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
}
