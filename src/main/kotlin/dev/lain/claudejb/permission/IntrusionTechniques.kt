package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * The [SecurityCategory.INTRUSION_TECHNIQUE] family — **the attacker's tooling, recognised so the guard can stop
 * it.** A defensive detector, the same shape as a Yara signature or an EDR rule: it reads a command and refuses
 * it, it never runs anything.
 *
 * ### Why the tools moved OUT of [CommandRules]
 * The offensive tooling used to sit inside `SECRET_DUMPING_COMMANDS`, which was wrong on two counts. It is a
 * different claim — "this is a hacking tool" is not "this dumps a secret at rest" — and it made the toggle wrong:
 * a user could not turn off "recognise intrusion tools" without also turning off "detect a credential dump". Now
 * it is its own rule under its own category, so the whole intrusion set disables as one deliberate choice (for an
 * authorised engagement) while every confidentiality wall stays up.
 *
 * ### Curated list PLUS shape — because a list alone rots
 * [KNOWN_TOOLS] is a curated, high-signal blacklist, and a blacklist is what you miss the next tool with (the
 * `/dev` enumeration, the stale `AGENT_TOOLS` — this package has paid for that lesson twice). So it is anchored at
 * a real command position ([CommandRules.cmdStart]), matched after de-obfuscation, and it is the CURATED half of
 * a larger design: the shape-based intrusion rules (outbound connection to an undeclared host by its form, log
 * clearing by its verb) live in their own families and catch what no name list can. This file is the names.
 *
 * ### Anchored, never a bare mention
 * Every entry matches only when the tool is the command actually being RUN — string start, after a `;`/`|`/`&`
 * separator, optionally through a path prefix (`/opt/tools/nmap`) or a leading `sudo`. `git commit -m "add nmap
 * parser"` and a file called `sqlmap-notes.md` are NOT matches, for the same reason [CommandRules.cmdStart]
 * exists: a rule keyed on a bare word fires on a commit message that mentions it, which is how a guard earns its
 * uninstall.
 */
object IntrusionTechniques {

    /** An intrusion-tool match: the rule that tripped, and the excerpt to quote back. */
    internal data class Hit(val rule: SecurityRule, val text: String)

    private const val MATCH_EXCERPT_CHARS = 120

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * Recognised offensive tools, by command name, grouped by the ATT&CK tactic they serve — the grouping is for
     * the human reading this list, not a behaviour difference (every entry reports [SecurityRule.HACKING_TOOL]).
     *
     * Cross-platform on purpose: the same engagement runs on Linux, macOS and Windows, so Windows-native tooling
     * (`rubeus`, `certify`, `sharphound`, `seatbelt`) sits beside the Unix names. A name that only exists on one
     * OS costs nothing on the others — it simply never matches — which is the safe direction for a detector.
     */
    private val KNOWN_TOOLS: List<Regex> = listOf(
        // ── Credential access (TA0006) ──────────────────────────────────────────────────────────────────
        CommandRules.cmdStart(
            "mimikatz|pypykatz|lsassy|nanodump|lazagne|secretsdump(\\.py)?|impacket-\\w+|" +
                "gosecretsdump|dpapi|hekatomb|donpapi|responder|mitm6|inveigh|pretender",
        ),
        // ── Scanning / discovery (TA0007) — tools only; bare id/whoami/ps stay OUT (dual-use) ────────────
        CommandRules.cmdStart(
            "nmap|masscan|zmap|rustscan|naabu|nikto|gobuster|dirbuster|dirb|feroxbuster|ffuf|wfuzz|" +
                "wpscan|nuclei|httpx|subfinder|amass|enum4linux(-ng)?|smbmap|ldapdomaindump",
        ),
        // ── Privilege-escalation enumeration (TA0004) ───────────────────────────────────────────────────
        CommandRules.cmdStart(
            "linpeas(\\.sh)?|winpeas(\\.exe|\\.bat)?|linenum(\\.sh)?|les(\\.sh)?|pspy|" +
                "seatbelt|powerup|privesccheck|gtfoblookup",
        ),
        // ── Exploitation / offensive frameworks (TA0002) ────────────────────────────────────────────────
        CommandRules.cmdStart(
            "sqlmap|msfconsole|msfvenom|msfdb|metasploit|beef-xss|setoolkit|routersploit|" +
                "commix|xsser|nosqlmap",
        ),
        // ── Active Directory / Kerberos attack (TA0006/TA0008) ──────────────────────────────────────────
        CommandRules.cmdStart(
            "rubeus|certipy|certify|kerbrute|bloodhound(-python)?|sharphound|" +
                "crackmapexec|nxc|netexec|ldapnomnom|adidnsdump|targetedkerberoast",
        ),
        // ── Command & control frameworks (TA0011) ───────────────────────────────────────────────────────
        CommandRules.cmdStart(
            "sliver(-client|-server)?|mythic|havoc|merlin|covenant|empire|" +
                "poshc2|villain|chisel|ligolo(-ng)?|pwncat",
        ),
        // ── Password cracking (TA0006) ──────────────────────────────────────────────────────────────────
        CommandRules.cmdStart(
            "hashcat|johntheripper|\\bjohn\\b|hydra|medusa|ncrack|patator|ophcrack|hashid|" +
                "crowbar|cewl|unshadow",
        ),
        // ── Cloud attack tooling (TA0007/TA0008 in cloud) ───────────────────────────────────────────────
        CommandRules.cmdStart("pacu|scoutsuite|prowler|cloudsploit|weirdaal|enumerate-iam|gcpbucketbrute"),
    )

    /**
     * **Reverse and bind shells — recognised by SHAPE, not by a name.** This is the half a curated list cannot
     * do: an attacker writes a reverse shell in whatever language is on the box, so what all of them share is the
     * structure — a socket wired to an interactive shell — and that is what is matched.
     *
     * A few of these overlap other rules on purpose (a `>& /dev/tcp` is also a [SecurityRule.SYSTEM_DEVICE], a
     * `nc -e` is also a dangerous command); severity ordering decides the wording and the overlap only ever means
     * a second rule would also have caught it. What earns this rule its own existence are the interpreter
     * one-liners — python, perl, php, ruby, node, powershell — that name no device and no `nc`.
     */
    private val REVERSE_SHELLS: List<Regex> = listOf(
        // bash/sh interactive shell redirected to a TCP socket (the canonical `bash -i >& /dev/tcp/h/p 0>&1`).
        re("""\b(bash|sh|zsh)\b\s+-[a-z]*i[a-z]*\b[^|;&\n]*(>&|>|0<|0>&|<)\s*/dev/(tcp|udp)/"""),
        re("""/dev/(tcp|udp)/[^\s/]+/\d+[^|;&\n]*\b(0>&|>&|1>&|<&)\s*\d"""),
        // A named pipe wired between a socket tool and a shell — the classic mkfifo reverse shell.
        re("""\bmkfifo\b[^|;&\n]*[|;&][^|;&\n]*\b(nc|ncat|netcat|telnet)\b"""),
        // nc / ncat / socat asked to run a program on connect.
        re("""\b(nc|ncat|netcat)\b[^|;&\n]*\s-[a-z]*e[a-z]*\b[^|;&\n]*(sh|bash|cmd|powershell)"""),
        re("""\bsocat\b[^|;&\n]*\b(exec|system):[^|;&\n]*(sh|bash|cmd|powershell)"""),
        re("""\bsocat\b[^|;&\n]*\b(tcp|openssl)-connect\b"""),
        // Interpreter one-liners: a socket AND a shell/exec in the same command → a reverse shell in that
        // language. Matched loosely on the two markers because their ORDER and spelling vary endlessly.
        re("""\bpython\d?\b[^\n]*\bimport\b[^\n]*\bsocket\b[^\n]*(pty\.spawn|/bin/(sh|bash)|subprocess|os\.system)"""),
        re("""\bperl\b[^\n]*\bsocket\b[^\n]*(exec|/bin/(sh|bash)|->spawn)"""),
        re("""\bphp\b[^\n]*\b(fsockopen|stream_socket_client)\b[^\n]*(exec|shell_exec|proc_open|/bin/(sh|bash)|`)"""),
        re("""\bruby\b[^\n]*\b(TCPSocket|socket)\b[^\n]*(exec|/bin/(sh|bash)|system|IO\.popen|`)"""),
        re("""\b(node|nodejs)\b[^\n]*\b(net\.(connect|createConnection)|require\(['"]net)[^\n]*child_process"""),
        re("""\blua\b[^\n]*\bsocket\b[^\n]*\b(os\.execute|io\.popen)"""),
        // PowerShell TCP-client reverse shell.
        re("""New-Object\s+(System\.Net\.Sockets\.)?TCPClient\b"""),
        re("""\bpowershell\b[^\n]*\.GetStream\(\)[^\n]*(IEX|Invoke-Expression|\.Read\()"""),
        // telnet piped into a shell, and the two-telnet reverse shell.
        re("""\btelnet\b[^|;&\n]+\d+\s*[|][^|;&\n]*\b(sh|bash)\b"""),
        // awk reverse shell over gawk's /inet/ pseudo-host.
        re("""\bawk\b[^\n]*/inet/(tcp|udp)/"""),
    )

    /**
     * **GTFOBins-style shell escapes and privilege escalation.** An ordinary, trusted binary coerced into
     * spawning a shell or running an arbitrary command it was never meant to — which is how a restricted shell,
     * a limited container, or a narrow `sudo` rule turns into full command execution.
     *
     * The dual-use floor is respected carefully here, because this is where it bites: `find … -exec grep {} \;`
     * is routine and must pass, so the match is `-exec` **followed specifically by a shell**, not any `-exec`.
     * Same for `awk` (only `BEGIN{system(`), `tar` (only the exec-action flags), `env` (only with a shell as its
     * program). It is NOT exhaustive — GTFOBins is hundreds of entries — but it covers the highest-signal
     * escapes; the residual is the accepted limit of a curated set, and the shape-first reverse-shell rule above
     * catches the exec paths that are also a socket.
     */
    private val GTFOBINS: List<Regex> = listOf(
        // find -exec a shell — the single most common escape. `-execdir` too.
        re("""\bfind\b[^|;&\n]*-exec(dir)?\s+(/(usr/)?bin/)?(sh|bash|zsh|dash|ksh)\b"""),
        // Editors and pagers dropping to a shell.
        re("""\b(vim?|view|rvim|nvim)\b[^|;&\n]*-c\s*['"]?\s*:?\s*(!|shell|py|lua|perl)"""),
        re("""\b(less|more|man|pg)\b[^|;&\n]*['"]?!\s*(/(usr/)?bin/)?(sh|bash)"""),
        re("""\b(ed|ex|nano|pico)\b[^|;&\n]*!\s*(/(usr/)?bin/)?(sh|bash)"""),
        // awk / gawk system() escape.
        re("""\b(awk|gawk|mawk)\b[^\n]*BEGIN\s*\{[^\n]*\bsystem\s*\("""),
        // tar exec actions.
        re("""\btar\b[^|;&\n]*(--checkpoint-action=|--to-command=|--use-compress-program=)"""),
        re("""\b(zip|rsync)\b[^|;&\n]*(--unzip-command|-e\s+sh|rsync-path=)"""),
        // Runners handed a shell as their program (env/nice/timeout/stdbuf/nohup/setsid/xargs).
        re("""\b(env|nice|ionice|timeout|stdbuf|nohup|setsid|chroot|unshare|taskset)\b[^|;&\n]*\s(/(usr/)?bin/)?(sh|bash|zsh)\b(\s|$)"""),
        re("""\bxargs\b[^|;&\n]*\s(/(usr/)?bin/)?(sh|bash)\b"""),
        // git pager / hook escapes.
        re("""\bgit\b[^|;&\n]*-c\s+core\.pager=[^|;&\n]*(sh|bash|less)"""),
        re("""\bgit\b[^|;&\n]*\b(!\s*(sh|bash)|-p\s+help)"""),
        // sed execute, and the interactive-debugger escapes.
        re("""\bsed\b[^|;&\n]*\b[0-9$]*e\s+(/(usr/)?bin/)?(sh|bash)"""),
        re("""\b(gdb|ftp|gimp|lua|irb|python\d?)\b[^\n]*(-ex\s*['"]?!|!\s*(sh|bash)|os\.system|io\.popen)"""),
        // perl/ruby exec of a shell without a socket (the non-reverse-shell escape form).
        re("""\bperl\b[^\n]*\bexec\s*\(?\s*['"](/(usr/)?bin/)?(sh|bash)"""),
        // A sudo of any of the above lands harder — spelled as a prefix so it composes with the patterns above.
        re("""\bsudo\b[^|;&\n]*\b(vim?|less|awk|find|env|tar|nmap|man)\b[^|;&\n]*(-exec|!|BEGIN|-c|--checkpoint)"""),
    )

    /**
     * The first intrusion technique the command invokes, or null. Judged over every command candidate and both
     * its expanded and de-obfuscated forms — the same surface [CommandRules.dangerousCommand] is matched against,
     * so `n""map` and `nmap${'$'}IFS-sS` are caught too.
     *
     * Order across the three groups is severity order (first hit wins the wording): a named tool is the most
     * specific claim, a reverse shell the next, a GTFOBins escape the most general.
     */
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
