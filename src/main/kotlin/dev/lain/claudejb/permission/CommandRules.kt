package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * Rule family 3 of [SensitiveGuard] — **dangerous commands**: commands that dump a secret at rest, exfiltrate a
 * file, pipe the network into a shell, or invoke recognised offensive/LOLBIN tooling — together with the
 * de-obfuscation that is applied before matching them.
 *
 * A dangerous command is **location-independent**: running `mimikatz` is dangerous whatever the working
 * directory, so unlike the credential globs this rule is not exempted inside the project root.
 *
 * What is *heuristic* here is **detection**, and only for shell strings: [deobfuscate] peels the cheap laundering
 * an attacker uses, never all of it. That is a gap in what we recognise — not a way to argue with a match once
 * made. Close it by widening the patterns, never by trusting the caller.
 */
object CommandRules {

    // ─── Blacklist 2 — the commands worth running, if you are the attacker. Curated: high signal. ─────────
    val DANGEROUS_COMMANDS: List<Regex> = listOf(
        // Dump a secret at rest
        re("""\bgpg2?\b[^|;&]*--export-secret-(keys|subkeys)"""),
        re("""\bssh-keygen\b[^|;&]*\s-y\b"""),
        re("""\bopenssl\b[^|;&]*\b(rsa|ec|pkcs12|pkcs8)\b[^|;&]*-in\b"""),
        re("""\bsecurity\b[^|;&]*\b(dump-keychain|find-(generic|internet)-password)\b"""),
        re("""\b(aws|az|gcloud|oci)\b[^|;&]*\b(configure get|print-access-token|get-token|get-session-token|list-access-tokens)\b"""),
        re("""\bkubectl\b[^|;&]*\bget\b[^|;&]*\bsecret"""),
        re("""\b(docker|podman)\b[^|;&]*\blogin\b[^|;&]*(-p\b|--password\b)"""),
        re("""\bgit\b[^|;&]*\bcredential\b[^|;&]*\bfill\b"""),
        re("""\b(printenv|env|set)\b\s*(\||>|$)"""),
        re("""\bcat\b[^|;&]*\b(shadow|master\.passwd|sudoers)\b"""),
        re("""BEGIN\s+(RSA|OPENSSH|EC|DSA|PGP)\s+PRIVATE\s+KEY"""),
        // Windows / PowerShell secret dumps
        re("""\bcertutil\b[^|;&]*(-exportPFX|-store\b|-user\b|-urlcache\b)"""),
        re("""\b(Export-PfxCertificate|Get-Credential|ConvertFrom-SecureString|Get-ChildItem\s+Cert:)\b"""),
        re("""\breg\b[^|;&]*\b(save|export)\b[^|;&]*hk(lm|cu).*(sam|security|system)"""),
        re("""\b(vaultcmd|cmdkey)\b[^|;&]*(/list|/rlist)"""),
        re("""\b(mimikatz|sekurlsa|lsadump)\b"""),
        // Exfiltrate
        re("""\bcurl\b[^|;&]*(-T\b|--upload-file\b|-F\b|--data-binary\s*@|--data\s*@)"""),
        re("""\bwget\b[^|;&]*--post-file"""),
        re("""\b(nc|ncat|netcat|socat)\b[^|;&]*(-e\b|\b\d{2,5}\b)"""),
        re("""\b(scp|rsync|sftp)\b[^|;&]*(\.ssh|\.aws|\.gnupg|\.kube|id_rsa|\.pem|\.env)\b"""),
        re("""\b(tar|zip|7z|gzip)\b[^|;&]*(\.ssh|\.aws|\.gnupg|\.kube|id_rsa|\.pem|\.env)\b"""),
        re("""\bbase64\b[^|;&]*(\.ssh|\.aws|\.gnupg|id_rsa|\.pem|\.env)"""),
        re("""\bInvoke-WebRequest\b[^|;&]*-(InFile|Body)\b"""),
        re("""/dev/tcp/\d"""),
        re("""\bdd\b[^|;&]*if=/dev/(sd|nvme|mem|kmem)"""),
        // Remote code / LOLBINs / reverse shells
        re("""\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(sh|bash|zsh|python\d?|perl|ruby)\b"""),
        re("""\b(powershell|pwsh)\b[^|;&]*-e(nc|ncodedcommand)?\b\s+[A-Za-z0-9+/=]{16,}"""),
        re("""\b(bitsadmin|mshta|regsvr32|rundll32|installutil|msbuild)\b[^|;&]*(http|/i:|javascript:|scrobj)"""),
        // Recognised offensive tooling
        re("""\b(lazagne|secretsdump(\.py)?|impacket-\w+|responder|bloodhound|sharphound|crackmapexec|nxc)\b"""),
        re("""\b(hashcat|johntheripper|hydra|medusa|patator|ophcrack|hashid)\b"""),
        re("""\b(sqlmap|msfconsole|msfvenom|metasploit|beef-xss|setoolkit|empire|covenant|sliver)\b"""),
        re("""\b(nmap|masscan|zmap|nikto|gobuster|dirbuster|feroxbuster|ffuf|wpscan)\b"""),
    )

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * How much of a matched dangerous command is quoted back in the denial reason. The excerpt is shown to the
     * user AND sent to the model, so it stays short: enough to recognise which rule fired, not enough to echo a
     * whole script back into the transcript.
     */
    private const val MATCH_EXCERPT_CHARS = 120

    // ── the rule ─────────────────────────────────────────────────────────────────────────────────────────

    internal fun dangerousCommand(input: JsonObject): String? {
        for (command in ToolInputScanner.commandCandidates(input)) {
            // Judge BOTH the raw command and its de-obfuscated form: an attacker hides `cat ~/.ssh/id_rsa` as
            // `c""at ~/.ss$IFS''h/id_rsa`, or ships it base64-encoded to `sh`. Matching only the raw string is a
            // sieve; matching the peeled string closes the cheap evasions (never all of them — see the class doc).
            for (candidate in setOf(GuardPaths.expandEnv(command, null), deobfuscate(command))) {
                DANGEROUS_COMMANDS.firstOrNull { it.containsMatchIn(candidate) }
                    ?.let { return it.find(candidate)?.value?.take(MATCH_EXCERPT_CHARS) }
            }
        }
        return null
    }

    // ── de-obfuscation ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Best-effort shell de-obfuscation: peel the cheap tricks an attacker uses to slip a command or a path past a
     * literal-string match. Explicitly NOT a shell parser — it cannot and does not claim to catch everything (a
     * decode-and-`eval`, `$(printf ...)`, a downloaded script). It removes the *common* laundering so the pattern
     * set is matched against something close to what the shell will actually run:
     *
     *  - **quote splitting**: `c""at`, `i''d_rsa`, `` `` `` → the quotes are deleted (`cat`, `id_rsa`);
     *  - **`$IFS` / `${IFS}`** used as a separator → a space;
     *  - **line continuations** `\<newline>` and stray backslash-escapes before a normal char → the char;
     *  - **simple var assignments** `k=~/.ssh/id_rsa; cat $k` → `$k`/`${k}` substituted with the value;
     *  - **`$HOME`/`~`** expansion (via [GuardPaths.expandEnv]);
     *  - **base64 payloads** long enough to be a command (`echo <b64> | base64 -d | sh`) → decoded and appended,
     *    so a hidden `nc`/`curl`/key path inside the blob is matched too.
     *
     * **Each step below is guarded by a plain `in` check on the character it needs.** Every one of these regexes
     * requires a specific character to match at all — `$` for the IFS/assignment steps, one of `\`/`'`/`"`/`` ` ``
     * for the quote/escape steps — so on a command with none of them the `replace` call is provably a no-op and
     * skipping it changes nothing it would have produced. This is what makes an ordinary command (`git status`,
     * `npm test`, `ls -la`) cheap: none of those characters appear, so only the harmless no-op [GuardPaths.expandEnv]
     * call and the base64 scan below still run. **The base64 scan is NOT gated the same way, and cannot be**: a
     * pure base64 blob (`A-Za-z0-9+/=`) contains none of `\`/`$`/`'`/`"`/`` ` `` either, so gating it on those
     * characters would skip decoding exactly the payloads this step exists to catch. Perf-only; revisit once
     * phase 5's timings exist — if it bought nothing, revert it.
     */
    fun deobfuscate(command: String): String {
        var s = command
        if ('\\' in s) {
            // Line continuations first, so a command split across lines becomes one line.
            s = s.replace("\\\n", "").replace("\\\r\n", "")
        }
        if ('$' in s) {
            // $IFS (with or without braces, optionally $'...') → a plain space.
            s = s.replace(Regex("""\$\{?IFS\}?"""), " ").replace(Regex("""\$'\\(?:x09|011|t)'"""), " ")
        }
        if ('\'' in s || '"' in s || '`' in s) {
            // Delete empty quote pairs and stray quotes/backticks used purely to break up tokens.
            s = s.replace("''", "").replace("\"\"", "").replace("``", "")
        }
        if ('\\' in s) {
            // A backslash before a normal (non-space) char is a no-op in the shell for our purposes: drop it.
            s = s.replace(Regex("""\\([A-Za-z0-9._/~-])"""), "$1")
        }
        if ('\'' in s || '"' in s || '`' in s) {
            // Now collapse the remaining quotes/backticks that wrap fragments (`"cat"` → cat, `'id'_rsa` → id_rsa).
            s = s.replace(Regex("""["'`]"""), "")
        }
        if ('=' in s) {
            // Resolve trivial `name=value` assignments, then substitute `$name`/`${name}` with the value.
            s = substituteAssignments(s)
        }
        // Expand $HOME/~ etc.
        s = GuardPaths.expandEnv(s, null)
        // Decode any base64 blob long enough to be a hidden command, and append it so its contents get matched.
        decodeBase64Payloads(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
        return s
    }

    /**
     * `k=~/.ssh/id_rsa … $k` → `… ~/.ssh/id_rsa`. Only literal, single-token assignments; enough for the net.
     *
     * **Real incident**: `String.replace(Regex, String)` treats the replacement argument as a *replacement
     * template* — `$1`/`${name}` are group references, not literal text. `v` is arbitrary shell-assigned text an
     * attacker (or an ordinary script) fully controls, e.g. `k=${OTHER}/x`: passing it straight to `replace()`
     * throws `IllegalArgumentException: Illegal group reference` from deep inside `java.util.regex.Matcher`,
     * uncaught, crashing `verdict()` for every `Bash` call with such an assignment — confirmed live via a stack
     * trace in idea.log. [java.util.regex.Matcher.quoteReplacement] escapes `\`/`$` so `v` is substituted
     * literally, exactly as intended.
     */
    private fun substituteAssignments(command: String): String {
        val assign = Regex("""(?:^|[\s;&|])([A-Za-z_][A-Za-z0-9_]*)=([^\s;&|]+)""")
        val vars = HashMap<String, String>()
        assign.findAll(command).forEach { vars[it.groupValues[1]] = it.groupValues[2] }
        if (vars.isEmpty()) return command
        var s = command
        for ((k, v) in vars) {
            val literal = java.util.regex.Matcher.quoteReplacement(v)
            s = s.replace(Regex("""\$\{$k\}"""), literal).replace(Regex("""\$$k(?![A-Za-z0-9_])"""), literal)
        }
        return s
    }

    /** Any base64-looking token ≥ 16 chars, decoded to printable ASCII (a hidden `nc`/path/command), else dropped. */
    private fun decodeBase64Payloads(command: String): List<String> {
        val out = ArrayList<String>()
        Regex("""[A-Za-z0-9+/]{16,}={0,2}""").findAll(command).forEach { m ->
            runCatching {
                val decoded = String(java.util.Base64.getDecoder().decode(m.value), Charsets.UTF_8)
                if (decoded.isNotBlank() && decoded.all(::isPrintableAscii)) out += decoded
            }
        }
        return out
    }

    /** Printable ASCII plus tab/newline — i.e. something that could plausibly BE a command, not binary noise. */
    private fun isPrintableAscii(c: Char): Boolean = c == '\t' || c == '\n' || c in ' '..'~'
}
