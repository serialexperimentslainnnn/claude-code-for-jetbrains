package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

object IntrusionTechniques {

    internal data class Hit(val rule: SecurityRule, val text: String)

    private const val MATCH_EXCERPT_CHARS = 120

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val KNOWN_TOOLS: List<Regex> = listOf(
        CommandRules.cmdStart(
            "mimikatz|pypykatz|lsassy|nanodump|lazagne|secretsdump(\\.py)?|impacket-\\w+|" +
                "gosecretsdump|dpapi|hekatomb|donpapi|responder|mitm6|inveigh|pretender",
        ),
        CommandRules.cmdStart(
            "nmap|masscan|zmap|rustscan|naabu|nikto|gobuster|dirbuster|dirb|feroxbuster|ffuf|wfuzz|" +
                "wpscan|nuclei|httpx|subfinder|amass|enum4linux(-ng)?|smbmap|ldapdomaindump",
        ),
        CommandRules.cmdStart(
            "linpeas(\\.sh)?|winpeas(\\.exe|\\.bat)?|linenum(\\.sh)?|les(\\.sh)?|pspy|" +
                "seatbelt|powerup|privesccheck|gtfoblookup",
        ),
        CommandRules.cmdStart(
            "sqlmap|msfconsole|msfvenom|msfdb|metasploit|beef-xss|setoolkit|routersploit|" +
                "commix|xsser|nosqlmap",
        ),
        CommandRules.cmdStart(
            "rubeus|certipy|certify|kerbrute|bloodhound(-python)?|sharphound|" +
                "crackmapexec|nxc|netexec|ldapnomnom|adidnsdump|targetedkerberoast",
        ),
        CommandRules.cmdStart(
            "sliver(-client|-server)?|mythic|havoc|merlin|covenant|empire|" +
                "poshc2|villain|chisel|ligolo(-ng)?|pwncat",
        ),
        CommandRules.cmdStart(
            "hashcat|johntheripper|\\bjohn\\b|hydra|medusa|ncrack|patator|ophcrack|hashid|" +
                "crowbar|cewl|unshadow",
        ),
        CommandRules.cmdStart("pacu|scoutsuite|prowler|cloudsploit|weirdaal|enumerate-iam|gcpbucketbrute"),
    )

    private val REVERSE_SHELLS: List<Regex> = listOf(
        re("""\b(bash|sh|zsh)\b\s+-[a-z]*i[a-z]*\b[^|;&\n]*(>&|>|0<|0>&|<)\s*/dev/(tcp|udp)/"""),
        re("""/dev/(tcp|udp)/[^\s/]+/\d+[^|;&\n]*\b(0>&|>&|1>&|<&)\s*\d"""),
        re("""\bmkfifo\b[^|;&\n]*[|;&][^|;&\n]*\b(nc|ncat|netcat|telnet)\b"""),
        re("""\b(nc|ncat|netcat)\b[^|;&\n]*\s-[a-z]*e[a-z]*\b[^|;&\n]*(sh|bash|cmd|powershell)"""),
        re("""\bsocat\b[^|;&\n]*\b(exec|system):[^|;&\n]*(sh|bash|cmd|powershell)"""),
        re("""\bsocat\b[^|;&\n]*\b(tcp|openssl)-connect\b"""),
        re("""\bpython\d?\b[^\n]*\bimport\b[^\n]*\bsocket\b[^\n]*(pty\.spawn|/bin/(sh|bash)|subprocess|os\.system)"""),
        re("""\bperl\b[^\n]*\bsocket\b[^\n]*(exec|/bin/(sh|bash)|->spawn)"""),
        re("""\bphp\b[^\n]*\b(fsockopen|stream_socket_client)\b[^\n]*(exec|shell_exec|proc_open|/bin/(sh|bash)|`)"""),
        re("""\bruby\b[^\n]*\b(TCPSocket|socket)\b[^\n]*(exec|/bin/(sh|bash)|system|IO\.popen|`)"""),
        re("""\b(node|nodejs)\b[^\n]*\b(net\.(connect|createConnection)|require\(['"]net)[^\n]*child_process"""),
        re("""\blua\b[^\n]*\bsocket\b[^\n]*\b(os\.execute|io\.popen)"""),
        re("""New-Object\s+(System\.Net\.Sockets\.)?TCPClient\b"""),
        re("""\bpowershell\b[^\n]*\.GetStream\(\)[^\n]*(IEX|Invoke-Expression|\.Read\()"""),
        re("""\btelnet\b[^|;&\n]+\d+\s*[|][^|;&\n]*\b(sh|bash)\b"""),
        re("""\bawk\b[^\n]*/inet/(tcp|udp)/"""),
    )

    private val GTFOBINS: List<Regex> = listOf(
        re("""\bfind\b[^|;&\n]*-exec(dir)?\s+(/(usr/)?bin/)?(sh|bash|zsh|dash|ksh)\b"""),
        re("""\b(vim?|view|rvim|nvim)\b[^|;&\n]*-c\s*['"]?\s*:?\s*(!|shell|py|lua|perl)"""),
        re("""\b(less|more|man|pg)\b[^|;&\n]*['"]?!\s*(/(usr/)?bin/)?(sh|bash)"""),
        re("""\b(ed|ex|nano|pico)\b[^|;&\n]*!\s*(/(usr/)?bin/)?(sh|bash)"""),
        re("""\b(awk|gawk|mawk)\b[^\n]*BEGIN\s*\{[^\n]*\bsystem\s*\("""),
        re("""\btar\b[^|;&\n]*(--checkpoint-action=|--to-command=|--use-compress-program=)"""),
        re("""\b(zip|rsync)\b[^|;&\n]*(--unzip-command|-e\s+sh|rsync-path=)"""),
        re("""\b(env|nice|ionice|timeout|stdbuf|nohup|setsid|chroot|unshare|taskset)\b[^|;&\n]*\s(/(usr/)?bin/)?(sh|bash|zsh)\b(\s|$)"""),
        re("""\bxargs\b[^|;&\n]*\s(/(usr/)?bin/)?(sh|bash)\b"""),
        re("""\bgit\b[^|;&\n]*-c\s+core\.pager=[^|;&\n]*(sh|bash|less)"""),
        re("""\bgit\b[^|;&\n]*\b(!\s*(sh|bash)|-p\s+help)"""),
        re("""\bsed\b[^|;&\n]*\b[0-9$]*e\s+(/(usr/)?bin/)?(sh|bash)"""),
        re("""\b(gdb|ftp|gimp|lua|irb|python\d?)\b[^\n]*(-ex\s*['"]?!|!\s*(sh|bash)|os\.system|io\.popen)"""),
        re("""\bperl\b[^\n]*\bexec\s*\(?\s*['"](/(usr/)?bin/)?(sh|bash)"""),
        re("""\bsudo\b[^|;&\n]*\b(vim?|less|awk|find|env|tar|nmap|man)\b[^|;&\n]*(-exec|!|BEGIN|-c|--checkpoint)"""),
    )

    internal fun hit(input: JsonObject, home: String? = null, env: Map<String, String> = emptyMap()): Hit? =
        ToolInputScanner.commandCandidates(input)
            .flatMap { setOf(GuardPaths.expandEnv(it, home, env), CommandRules.deobfuscate(it, home, env)) }
            .firstNotNullOfOrNull { candidate -> firstTechnique(candidate) }

    private fun firstTechnique(candidate: String): Hit? =
        match(candidate, KNOWN_TOOLS, SecurityRule.HACKING_TOOL)
            ?: match(candidate, REVERSE_SHELLS, SecurityRule.REVERSE_SHELL)
            ?: match(candidate, GTFOBINS, SecurityRule.PRIVESC_EXEC)

    private fun match(candidate: String, patterns: List<Regex>, rule: SecurityRule): Hit? =
        patterns.firstNotNullOfOrNull { p -> p.find(candidate)?.let { Hit(rule, it.value.take(MATCH_EXCERPT_CHARS)) } }
}
