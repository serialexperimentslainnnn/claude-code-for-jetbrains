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
        // The cloud instance metadata service. Matched on the ADDRESS alone, with no verb in front of it, and
        // that is deliberate: 169.254.169.254 is a link-local address with exactly one use on a cloud instance —
        // handing out the machine's own IAM role credentials to whoever asks from inside it. It is the first
        // thing an SSRF or an injected instruction reaches for, it needs no authentication, and there is no
        // benign reason for an agent to name it. `metadata.google.internal` is the same service by DNS name.
        re("""\b169\.254\.169\.254\b"""),
        re("""\bmetadata\.(google\.internal|azure\.com)\b"""),
        // Windows / PowerShell secret dumps
        re("""\bcertutil\b[^|;&]*(-exportPFX|-store\b|-user\b|-urlcache\b)"""),
        re("""\b(Export-PfxCertificate|Get-Credential|ConvertFrom-SecureString|Get-ChildItem\s+Cert:)\b"""),
        re("""\breg\b[^|;&]*\b(save|export)\b[^|;&]*hk(lm|cu).*(sam|security|system)"""),
        re("""\b(vaultcmd|cmdkey)\b[^|;&]*(/list|/rlist)"""),
        // `mimikatz`/`sekurlsa`/`lsadump` are intrusion TOOLING, moved to `IntrusionTechniques.HACKING_TOOL`
        // (pypykatz/lsassy live there too). What stays here is a command that dumps a secret WITHOUT a dedicated
        // attack tool — the Windows built-ins above, the cloud-CLI token reads, `cat shadow`.
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
        // Recognised offensive TOOLING moved to `IntrusionTechniques` (SecurityRule.HACKING_TOOL): it is a
        // different claim from "this dumps a secret", and it needed its own toggle so the whole intrusion set
        // disables as one deliberate choice for an authorised engagement. What stays HERE is a command that
        // exposes a secret at rest or exfiltrates a file — the things above this line.
    )

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /**
     * [names] (a `|`-joined alternation) matched only when it is the command actually being RUN at this shell
     * position — string start, or right after a `;`/`|`/`&`/newline separator, optionally through a path prefix
     * (`/usr/bin/nmap`, `./nmap`) and/or a leading `sudo` — never a bare word appearing anywhere in the text.
     *
     * The four offensive-tool lines below used to be `\b(name)\b`, which matches the tool's NAME wherever it
     * occurs: `git commit -m "add nmap parser"`, a path containing `sqlmap`, a `Grep` for the word `hydra`. That
     * denies an untrusted caller with no override on text that runs nothing — the same failure class `isUnc` and
     * `substituteAssignments` each cost a live incident over. Anchoring is more PRECISE, not more lax: `nmap` as
     * an argument to `-m` is still not a match, `nmap` as the command itself still is.
     */
    internal fun cmdStart(names: String) = re("""(?:^|[;&|\n]\s*)(?:sudo\s+)?(?:\S*/)?($names)\b""")

    /**
     * How much of a matched dangerous command is quoted back in the denial reason. The excerpt is shown to the
     * user AND sent to the model, so it stays short: enough to recognise which rule fired, not enough to echo a
     * whole script back into the transcript.
     */
    private const val MATCH_EXCERPT_CHARS = 120

    // ── the rule ─────────────────────────────────────────────────────────────────────────────────────────

    internal fun dangerousCommand(
        input: JsonObject,
        home: String? = null,
        env: Map<String, String> = emptyMap(),
    ): String? {
        for (command in ToolInputScanner.commandCandidates(input)) {
            // Judge BOTH the raw command and its de-obfuscated form: an attacker hides `cat ~/.ssh/id_rsa` as
            // `c""at ~/.ss$IFS''h/id_rsa`, or ships it base64-encoded to `sh`. Matching only the raw string is a
            // sieve; matching the peeled string closes the cheap evasions (never all of them — see the class doc).
            for (candidate in setOf(GuardPaths.expandEnv(command, home, env), deobfuscate(command, home, env))) {
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
    fun deobfuscate(command: String, home: String? = null, env: Map<String, String> = emptyMap()): String {
        var s = command
        // TO A FIXPOINT, bounded by MAX_ANALYSIS_DEPTH, because the ORDER the tricks were applied in decides
        // whether one pass is enough. Found by the fuzz suite: `a``ws$\IFSconfigure get secret` really does run
        // `aws configure get secret`, and a single pass peels it in the wrong order — the `$IFS` step runs before
        // the backslash step, so it sees `$\IFS`, matches nothing, and by the time the backslash is gone nothing
        // looks at IFS again. Peeling until the string stops changing is order-independent; the bound is what
        // keeps it terminating on the thread that reads the binary's entire stdout.
        var passes = 0
        while (passes++ < MAX_ANALYSIS_DEPTH) {
            val next = peel(s, home, env)
            if (next == s) break
            s = next
        }
        // Base64 and brace expansion are appended AFTER the loop and exactly once: both GROW the string, so inside
        // the loop each would be a change on every pass and the fixpoint would never be reached.
        decodeBase64Payloads(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
        expandBraces(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
        return s
    }

    /** `$'…'` — the ANSI-C quoted form, whose body carries escapes the shell decodes before running anything. */
    private val ANSI_C_QUOTED = Regex("""\$'((?:[^'\\]|\\.)*)'""")

    /** `\xNN`, `\uNNNN`, `\NNN` (octal) and the usual single-letter escapes, in one alternation. */
    private val ANSI_C_ESCAPE = Regex("""\\(x[0-9A-Fa-f]{1,2}|u[0-9A-Fa-f]{1,4}|[0-7]{1,3}|.)""")

    /**
     * The body of a `$'…'` string, with its escapes resolved — `\x2fetc\x2fshadow` → `/etc/shadow`.
     *
     * Unknown escapes yield the character itself, which is what the shell does and is also the safe direction
     * here: this function only ever makes a hidden path MORE visible to the matchers, never less.
     */
    private fun decodeAnsiC(body: String): String = ANSI_C_ESCAPE.replace(body) { m ->
        val esc = m.groupValues[1]
        when {
            esc.startsWith("x") || esc.startsWith("u") ->
                esc.drop(1).toIntOrNull(16)?.toChar()?.toString() ?: esc

            esc.length in 1..3 && esc.all { it in '0'..'7' } ->
                esc.toIntOrNull(8)?.toChar()?.toString() ?: esc

            else -> when (esc) {
                "n" -> "\n"
                "t" -> "\t"
                "r" -> "\r"
                "0" -> ""
                else -> esc
            }
        }
    }

    /** How many brace expansions one command may contribute. A bound, because `{a,b}{c,d}{e,f}…` multiplies. */
    private const val MAX_BRACE_EXPANSIONS = 32

    /** A token carrying at least one `{a,b}` alternation — the only shape worth expanding. */
    private val BRACE_TOKEN = Regex("""\S*\{[^{}\s]*,[^{}\s]*\}\S*""")

    /**
     * `~/.{ssh,aws}/credentials` → `~/.ssh/credentials`, `~/.aws/credentials`.
     *
     * Brace expansion happens in the shell before anything else, so a single written token can name several real
     * files — and a literal matcher sees one token that matches no glob at all. The expansions are APPENDED
     * rather than substituted, the same discipline the base64 decode follows and for the same reason: adding a
     * spelling can only ever find one more match, while replacing one can lose the match that was already there.
     *
     * Bounded by [MAX_BRACE_EXPANSIONS] because the product of several groups grows fast, and this runs on the
     * thread that reads the binary's entire stdout.
     */
    private fun expandBraces(command: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in BRACE_TOKEN.findAll(command).map { it.value }) {
            var forms = listOf(token)
            while (forms.size <= MAX_BRACE_EXPANSIONS) {
                val next = forms.flatMap { form ->
                    val group = Regex("""\{([^{}\s]*,[^{}\s]*)\}""").find(form) ?: return@flatMap listOf(form)
                    group.groupValues[1].split(',').map { form.replaceRange(group.range, it) }
                }
                if (next == forms) break
                forms = next
            }
            out += forms.filter { it != token }
            if (out.size >= MAX_BRACE_EXPANSIONS) break
        }
        return out.toList()
    }

    /** One peeling pass. Idempotent by design, which is what lets [deobfuscate] iterate it safely. */
    private fun peel(command: String, home: String?, env: Map<String, String>): String {
        var s = command
        if ('\\' in s) {
            // Line continuations first, so a command split across lines becomes one line.
            s = s.replace("\\\n", "").replace("\\\r\n", "")
        }
        if ("$'" in s) {
            // ANSI-C quoting: `$'\x2fetc\x2fshadow'` IS `/etc/shadow` to the shell, and to a literal matcher it is
            // a string with no slashes in it at all — so every path rule and every command pattern misses it while
            // the command runs exactly as written. Decoded BEFORE the quote-collapsing steps below, which would
            // otherwise strip the `$'…'` delimiters and leave the escapes stranded as ordinary text.
            s = ANSI_C_QUOTED.replace(s) { m -> decodeAnsiC(m.groupValues[1]) }
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
        // Expand `~`/`$HOME`, and every other variable the launch environment can resolve — transitively, so
        // `A=$B` with `B` in the environment reaches the value the shell will actually use.
        s = GuardPaths.expandEnv(s, home, env)
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
