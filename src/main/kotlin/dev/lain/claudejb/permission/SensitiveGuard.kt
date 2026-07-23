package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A guardrail against an agent — by accident, or by prompt injection — reading what a real attacker would come for,
 * or running what a real attacker would run.
 *
 * Read the whole doc before touching a rule: the value here is that it is thought through, not that it is long. It
 * reacts to three curated surfaces and nothing else, so ordinary development never trips it — a guard that cries
 * wolf is a guard the user switches off, and then it protects nothing.
 *
 * ### 1. Credentials & key material — [SENSITIVE_GLOBS]
 * Matched **by shape, wherever the file sits**, never anchored to a specific home. Anchoring to `$HOME` goes blind
 * on Windows (`C:\Users\bob\.ssh`) and WSL (`/mnt/c/Users/bob/.ssh`), where the interesting home is not the one the
 * JVM reports; one structural `.ssh` glob catches Linux, macOS, Windows and WSL at once (and a `.aws` fixture
 * inside a repo too, which is correct).
 *
 * ### 2. Foreign territory — [Category.FOREIGN]
 * Another user's home (`/home/<not-me>`, `/Users/<not-me>`, `C:\Users\<not-me>`, `/root`), a network/removable
 * mount (NFS, CIFS/SMB, SSHFS, UNC `\\server\share`), and — under **WSL** — anything on `/mnt/` that is not
 * `/mnt/c`. None of that is agentic development; it is lateral movement. The only exemption is the open project's
 * own root: a repo on a corporate share is normal and the user opened it on purpose (the credential globs still
 * apply to it).
 *
 * ### 3. Dangerous commands — [DANGEROUS_COMMANDS]
 * Commands that dump a secret at rest, exfiltrate a file, pipe the network into a shell, or invoke recognised
 * offensive/LOLBIN tooling.
 *
 * ### Verdict, by trust of the CALLER — an allowlist, not a blacklist
 * The caller is trusted **only if it is one of the agent's own tools** ([AGENT_TOOLS]). Everything else — every MCP
 * server, every Skill, anything unrecognised — is third-party, because a blacklist of "bad" prefixes is exactly the
 * thing an attacker names their way around. This is a **hard lock, with no opt-out**:
 *  - a **trusted** tool that trips rule 1 or 3 → **ASK** (a card, every time, even under `bypassPermissions`): the
 *    user may authorise their own agent to read their own key, once, explicitly;
 *  - a **third-party** caller that trips rule 1 or 3 → **DENY**, full stop — no setting softens it;
 *  - **anyone** who trips rule 2 (foreign territory) → **DENY**; it is never legitimate.
 *
 * The one thing a user can tune is the sensitive-path list, and only **additively**: the effective globs are the
 * built-in [SENSITIVE_GLOBS] plus their extras. The built-ins cannot be removed — a settings screen that lets you
 * empty the blacklist is just the escape hatch wearing a hat.
 *
 * ### Why this is enforceable even in `bypassPermissions`
 * The plugin launches the binary in `default` mode **always** — `acceptEdits`/`bypassPermissions` are implemented
 * here by auto-approving in [PermissionBroker] (`SessionLauncher.binaryPermissionMode`). Every tool call arrives as
 * a `can_use_tool` request whatever mode the user picked, so "never auto-approve this one" is the plugin's call to
 * make. The mode itself is untouched; that branch is simply not reached.
 *
 * ### Why the whole input, not a key list
 * A file argument is `file_path` — until an MCP server calls it `path`, `target`, `uri`, `destination`, or
 * something no one has seen. [pathCandidates] walks **every string leaf** of the input, skipping URLs and
 * multi-line blobs so a `Write`'s *contents* are not mistaken for a filename.
 *
 * ### What this is, and what it is not
 * This is **not an LLM guardrail.** It does not ask the model to behave, and there is no prompt that talks it out
 * of a No. It is deterministic Kotlin, out of band, intercepting every `can_use_tool` request before any
 * auto-approval — the model has no access to this code and no say in its verdict. At this layer, enforcement is
 * absolute: a match is a wall, not a suggestion.
 *
 * What is *heuristic* is **detection**, and only for shell strings. Matching a declared file path is exact; but
 * `cat $HOME/.ss''h/id_rsa`, a base64 round-trip, or a script that reads a key indirectly may not *match* a
 * pattern, and a symlink is not resolved. That is a gap in what we recognise — not a way to argue with a match
 * once made. Close it by widening the patterns, never by trusting the caller. (The [AGENT_TOOLS] allowlist is the
 * one trust decision, and it is a whitelist precisely so an attacker cannot name their way onto it.)
 *
 * PURE: no IDE, no filesystem, no OS sniffing. [Policy] carries everything (assembled on the IDE side from settings
 * + [dev.lain.claudejb.session.RemoteMounts]), so every rule is unit-testable — for security code, a requirement.
 */
object SensitiveGuard {

    /** What to do with a tool call that trips the guard. */
    enum class Verdict { ALLOW, ASK, DENY }

    /** Which surface a call tripped — decides severity ([verdict]) and the card's wording ([reason]). */
    enum class Category { CREDENTIAL, FOREIGN, DANGEROUS_COMMAND }

    /**
     * The agent's OWN tools — the allowlist of trusted callers. Anything not in here (MCP, Skills, unknown) is
     * third-party and denied by default when it trips the guard. Kept in sync with the binary's built-in tool set.
     */
    val AGENT_TOOLS: Set<String> = setOf(
        "Bash", "Read", "Edit", "Write", "MultiEdit", "NotebookEdit", "NotebookRead",
        "Glob", "Grep", "LS", "Task", "TodoWrite", "WebFetch", "WebSearch", "ExitPlanMode",
    )

    /** Everything the guard needs to judge a call. Assembled by the IDE side; pure input here. */
    data class Policy(
        val globs: List<String> = SENSITIVE_GLOBS,
        /** The user's home, for expanding `~`/`$HOME`/`%USERPROFILE%`. */
        val home: String? = null,
        /** The current username — anyone else's home directory is foreign territory. */
        val currentUser: String? = null,
        /** Network/removable mount points discovered on this host — treated as foreign. */
        val guardedRoots: List<String> = emptyList(),
        /** WSL only: treat every `/mnt/<x>` where x ≠ c as foreign. */
        val blockForeignWslMounts: Boolean = false,
        /** The open project. A path under it is exempt from the FOREIGN rules — never from the credential globs. */
        val projectRoot: String? = null,
        /**
         * Optional **canonicaliser**: given a candidate path, return its real on-disk path (symlinks and `..`
         * resolved), or null if it cannot be resolved. Injected by the IDE side because it touches the filesystem;
         * the guard stays pure. When present, every RESOLVABLE-looking candidate (see [looksResolvable]) is judged
         * on BOTH its literal and its resolved form, so a symlink `proj/innocent → ~/.ssh/id_rsa`, or
         * `proj/../../../etc/shadow`, cannot launder a path past the rules by hiding its true target. A resolver
         * that throws, returns null, or is too slow (see [expandWithResolved]) just leaves the literal.
         *
         * **Caller contract — this WILL be called from whatever thread invokes [verdict]/[reason]/[classify].**
         * A typical implementation (`File(x).canonicalPath`) is a blocking syscall with **no JDK-level timeout and
         * no interrupt**: on a hung/unresponsive network mount it can block the calling thread forever. [SensitiveGuard]
         * defends against that itself (bounded, off-thread, per [expandWithResolved]) — but do not add further
         * blocking work inside this lambda beyond a single stat-like call, since the bound assumes that shape.
         */
        val pathResolver: ((String) -> String?)? = null,
    )

    // ─── Blacklist 1 — the files worth stealing. Structural (match anywhere), cross-OS. ───────────────────
    val SENSITIVE_GLOBS: List<String> = listOf(
        // SSH — keys + the recon goldmine (known_hosts / authorized_keys / config)
        "**/.ssh/**", "**/id_rsa*", "**/id_dsa*", "**/id_ecdsa*", "**/id_ed25519*", "**/*_rsa", "**/*.ppk",
        // GPG / PKI / generic key material
        "**/.gnupg/**", "**/.pki/**", "**/*.pem", "**/*.key", "**/*.p8", "**/*.p12", "**/*.pfx",
        "**/*.jks", "**/*.keystore", "**/*.asc", "**/*.gpg", "**/*.kdbx", "**/*.agekey",
        // VPN / tunnels
        "**/*.ovpn", "**/wg*.conf", "/etc/wireguard/**", "/etc/ipsec.secrets", "**/*.mobileconfig",
        // Cloud, cluster, container, IaC
        "**/.aws/**", "**/.azure/**", "**/.config/gcloud/**", "**/gcloud/**/credentials.db", "**/.oci/**",
        "**/.config/doctl/**", "**/.config/hcloud/**", "**/.config/scw/**", "**/.aliyun/**", "**/.config/linode-cli",
        "**/.kube/config", "**/.kube/**/*config*", "**/.docker/config.json", "**/.config/containers/auth.json",
        "**/.terraform.d/credentials.tfrc.json", "**/*.tfstate", "**/*.tfstate.backup", "**/.config/pulumi/**",
        "**/.ansible/**/*vault*", "**/.config/rclone/rclone.conf", "**/.s3cfg", "**/.boto",
        // Registries, VCS, build tooling
        "**/.netrc", "**/_netrc", "**/.npmrc", "**/.yarnrc.yml", "**/.pypirc", "**/.gem/credentials",
        "**/.cargo/credentials*", "**/.gradle/gradle.properties", "**/.m2/settings.xml", "**/.bundle/config",
        "**/.composer/auth.json", "**/.nuget/NuGet.Config", "**/.git-credentials", "**/.config/gh/hosts.yml",
        "**/.config/glab-cli/**", "**/.config/hub", "**/.config/git/credentials",
        // Databases
        "**/.pgpass", "**/.my.cnf", "**/.mylogin.cnf", "**/.mysql_history", "**/.psql_history", "**/.dbeaver/**",
        "**/.mongorc.js", "**/.rediscli_history",
        // Shell / REPL history — where secrets go to be pasted
        "**/.bash_history", "**/.zsh_history", "**/.sh_history", "**/.python_history", "**/.node_repl_history",
        "**/.local/share/fish/fish_history", "**/.irb_history", "**/.lesshst",
        // Password managers & browser stores (cookies = live sessions)
        "**/.password-store/**", "**/.config/Bitwarden*/**", "**/1Password/**", "**/*.opvault/**",
        "**/logins.json", "**/key4.db", "**/signons.sqlite", "**/Login Data", "**/Cookies", "**/cookies.sqlite",
        // Crypto wallets
        "**/wallet.dat", "**/*.wallet", "**/.electrum/**", "**/.ethereum/keystore/**", "**/.bitcoin/wallet.dat",
        // Mail
        "**/.msmtprc", "**/.fetchmailrc", "**/.authinfo", "**/.authinfo.gpg",
        // macOS keychains
        "**/Library/Keychains/**", "**/*.keychain-db", "**/*.keychain",
        // Windows credential + registry stores (native and via WSL /mnt)
        "**/AppData/Roaming/Microsoft/Credentials/**", "**/AppData/Local/Microsoft/Credentials/**",
        "**/AppData/Roaming/Microsoft/Protect/**", "**/AppData/Local/Microsoft/Vault/**",
        "**/AppData/Roaming/Microsoft/SystemCertificates/**", "**/AppData/**/gcloud/**", "**/*.rdp",
        "**/NTUSER.DAT", "**/Windows/System32/config/SAM", "**/Windows/System32/config/SECURITY",
        "**/Windows/System32/config/SYSTEM",
        // Container / orchestrator secrets, and other processes' environment
        "/run/secrets/**", "/var/run/secrets/**", "**/serviceaccount/token", "/proc/*/environ",
        // AI-agent access tokens — the crown jewels of this era, ours included (the plugin must not read its own)
        "**/.claude/.credentials.json", "**/.claude/**/*credential*", "**/.config/anthropic/**",
        "**/.codex/**", "**/.config/openai/**", "**/.openai/**",                          // OpenAI / Codex
        "**/.config/github-copilot/**", "**/github-copilot/hosts.json", "**/github-copilot/apps.json", // Copilot
        "**/.cursor/**/*token*", "**/.cursor/**/*credential*", "**/.config/Cursor/**/*token*",          // Cursor
        "**/.codeium/**", "**/.codeium/windsurf/**",                                       // Codeium / Windsurf
        "**/.continue/**/*token*", "**/.continue/config.json", "**/.aider*", "**/.aider.conf.yml",
        "**/.config/TabNine/**", "**/.gemini/**", "**/.config/zed/**/*token*",             // TabNine / Gemini / Zed
        "**/.config/gh-copilot/**", "**/.sourcegraph/**", "**/.src-config.json",           // Copilot CLI / Cody
        "**/.config/JetBrains/**/*token*", "**/.local/share/JetBrains/**/*token*",
        // Source-repo & package/registry API keys — access to your code and your supply chain
        "**/.config/gh/hosts.yml", "**/.config/glab-cli/**", "**/.config/hub", "**/.config/git/credentials",
        "**/.config/tea/**", "**/.config/bb/**", "**/.gitconfig.local",                    // gitea / bitbucket
        "**/.huggingface/token", "**/.cache/huggingface/token", "**/.kaggle/kaggle.json",  // model registries
        "**/.config/heroku/**", "**/.fly/**", "**/.config/fly/**", "**/.railway/**", "**/.config/railway/**",
        "**/.wrangler/**", "**/.cloudflared/**", "**/.config/stripe/**", "**/.sentryclirc",// PaaS / CDN / SaaS
        "**/.config/configstore/*.json", "**/.jfrog/**", "**/.config/doctl/**", "**/.vault-token",
        "**/.supabase/**", "**/.config/supabase/**", "**/.planetscale/**", "**/.config/ngrok*/**",
        // Unix system secrets
        "/etc/shadow", "/etc/gshadow", "/etc/master.passwd", "/etc/sudoers", "/etc/sudoers.d/**",
        "/etc/ssl/private/**", "/etc/ssh/*_key", "/etc/krb5.keytab", "**/krb5cc_*", "**/.k5login", "**/.htpasswd",
        // Project secrets (also match inside the repo — that is the point)
        "**/.env", "**/.env.*", "**/.envrc", "**/secrets.y*ml", "**/secrets.json", "**/credentials.json",
        "**/service-account*.json", "**/.vault-token", "**/.netlify/state.json", "**/.vercel/**",
    )

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

    /** Keys whose value is (or contains) a command line, however the tool spells it. */
    private val COMMAND_KEY = re("""^(cmd|command|commands|script|shell|shell_?command|exec|execute|run|args|argv|arguments|code|program|pty_?input)$""")

    /** Segment introducing a user home: `/home/<u>`, `/Users/<u>`, `C:/Users/<u>`, `/mnt/c/Users/<u>`. */
    private val HOME_SEGMENT = re("""(?:^|/)(?:home|users)/([^/]+)""")

    /** A URL, not a path. */
    private val URLISH = re("""^[a-z][a-z0-9+.\-]*://""")

    /** Longer than a filename → it is a file's *contents*, not its name. */
    private const val MAX_PATH_LEN = 512

    // ── origin: trusted only if it is one of the agent's own tools ───────────────────────────────────────

    /** True only for the agent's OWN tools. Everything else — MCP, Skills, unknown — is third-party. */
    fun isTrustedCaller(toolName: String): Boolean = toolName in AGENT_TOOLS

    // ── the decision ─────────────────────────────────────────────────────────────────────────────────────

    /** The verdict for a tool call. [Verdict.ALLOW] means "not our business" — the normal permission flow runs. */
    fun verdict(toolName: String, input: JsonObject, policy: Policy): Verdict {
        val category = classify(toolName, input, policy)?.first ?: return Verdict.ALLOW
        if (category == Category.FOREIGN) return Verdict.DENY // never legitimate, for anyone — no opt-out
        // Credentials / dangerous commands: the agent's own tools may be authorised (a card); anyone else is denied
        // outright, with no setting to soften it. The allowlist is the whole trust decision.
        return if (isTrustedCaller(toolName)) Verdict.ASK else Verdict.DENY
    }

    /** The one-line reason a call tripped the guard (for the card / transcript), or null. */
    fun reason(toolName: String, input: JsonObject, policy: Policy): String? =
        classify(toolName, input, policy)?.second

    /**
     * Category + human reason, or null. Order = severity: FOREIGN wins the wording.
     *
     * The **project root is the sanctioned zone**: a file the user brought into their own repo is theirs, under
     * their responsibility, so a credential file *inside the project* is not blocked. Outside it, a credential is
     * caught. FOREIGN territory is exempt inside the project too (you opened it on purpose). A dangerous **command**
     * is location-independent — running `mimikatz` is dangerous whatever the working directory — so it is judged
     * regardless of the project boundary.
     */
    private fun classify(toolName: String, input: JsonObject, policy: Policy): Pair<Category, String>? {
        // Every candidate is judged on its literal form AND its resolved real path (symlink/`..` laundering).
        val paths = expandWithResolved(pathCandidates(input, policy.home), policy)

        foreignHit(paths, policy)?.let { return Category.FOREIGN to "reaches outside your own space: $it" }

        val projRoot = policy.projectRoot?.let { normalize(it, policy.home) }
        val outsideProject = paths.filter { projRoot == null || !under(it, projRoot) }
        val matchers = policy.globs.map { compile(it, policy.home) }
        outsideProject.firstOrNull { p -> matchers.any { it.matches(p) } }
            ?.let { return Category.CREDENTIAL to "reads credentials or key material outside the project: $it" }

        dangerousCommand(input)?.let { return Category.DANGEROUS_COMMAND to "runs a command that can expose secrets: $it" }

        return null
    }

    /**
     * Each candidate, plus — when a resolver is configured — its canonical real path. Deduped, order-stable.
     *
     * **This is the fix for a real incident**: `pathCandidates` treats every bare word of a `Bash` command as a
     * candidate (`pathish` requires no path separator), so an ordinary command like `git commit -m 'fix: env
     * parsing'` used to produce a resolver call — a synchronous, unbounded, uninterruptible `stat()` — for EVERY
     * token (`git`, `commit`, `-m`, `fix:`, `env`, `parsing`…). That ran on the single thread that reads the
     * `claude` process's entire stdout stream, so a slow or hung filesystem (a stale network mount, WSL's 9p) froze
     * the whole transcript, not just one card — silently, since `runCatching` swallowed the eventual failure but
     * not the wait. Introduced alongside this class, caught only by a live report, never by a unit test (pure
     * functions don't model a hung syscall) — hence the two independent bounds below, not just one:
     *
     *  1. [looksResolvable] filters out candidates that cannot plausibly be a path AT ALL (most Bash tokens) before
     *     ever calling the resolver — this is what makes an ordinary command cheap again;
     *  2. even a resolvable candidate is only given [RESOLVE_TIMEOUT_MS] on a background thread ([resolveWithTimeout]) —
     *     this is what keeps a single `~/.ssh/id_rsa`-shaped argument, on a genuinely hung mount, from freezing
     *     anything: the reader thread moves on, at worst missing that one candidate's canonical form.
     * Capped at [MAX_RESOLVE_CANDIDATES] resolvable candidates as a third, coarser bound against a command crafted
     * with dozens of real-looking paths.
     */
    private fun expandWithResolved(paths: List<String>, policy: Policy): List<String> {
        val resolver = policy.pathResolver ?: return paths
        val out = LinkedHashSet<String>()
        var resolved = 0
        for (p in paths) {
            out += p
            if (resolved >= MAX_RESOLVE_CANDIDATES || !looksResolvable(p)) continue
            resolved++
            resolveWithTimeout(resolver, p)?.let { out += normalize(it, policy.home) }
        }
        return out.toList()
    }

    /**
     * Cheap, no-I/O pre-filter: could [token] plausibly BE a filesystem path? Most words in a shell command
     * (subcommands, flags, flag values, commit messages) cannot — they have no separator and no home/drive marker.
     * Requiring one before ever touching the resolver is what keeps ordinary `Bash` calls fast; a real path
     * (`~/.ssh/id_rsa`, `./script.sh`, `/etc/passwd`, `C:\Users\bob\x`) always has one.
     */
    private fun looksResolvable(token: String): Boolean =
        token.startsWith("~") || token.contains('/') || token.contains('\\')

    /**
     * Runs [resolver] on a background thread and waits at most [RESOLVE_TIMEOUT_MS] — never on the caller's thread.
     * On timeout, exception, or rejection, returns null (the literal candidate is still judged; only its resolved
     * form is missing). [future.cancel] cannot actually stop a blocked native `stat()` (Java has no safe way to do
     * that), so a genuinely hung call leaks one idle daemon thread in [resolverExecutor] rather than ever blocking
     * the process's stdout-reading thread — the trade this class exists to make.
     */
    private fun resolveWithTimeout(resolver: (String) -> String?, path: String): String? {
        val future = runCatching { resolverExecutor.submit(Callable { resolver(path) }) }.getOrNull() ?: return null
        return try {
            future.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Cap on how many candidates per call get a (bounded, off-thread) resolve attempt — a coarse third bound. */
    private const val MAX_RESOLVE_CANDIDATES = 16

    /** How long a single resolver call may run before we give up on it and move on. */
    private const val RESOLVE_TIMEOUT_MS = 200L

    /** Daemon threads only — must never keep the JVM alive, and a stuck one (see [resolveWithTimeout]) is expected. */
    private val resolverExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "SensitiveGuard-resolver").apply { isDaemon = true }
    }

    // ── rule: foreign territory ──────────────────────────────────────────────────────────────────────────

    private fun foreignHit(paths: List<String>, policy: Policy): String? {
        val projRoot = policy.projectRoot?.let { normalize(it, policy.home) }
        val myHome = policy.home?.let { normalize(it, null) }
        val guarded = policy.guardedRoots.map { normalize(it, policy.home) }.filter { it.isNotBlank() }
        return paths.firstOrNull { p ->
            if (projRoot != null && under(p, projRoot)) return@firstOrNull false
            if (myHome != null && under(p, myHome)) return@firstOrNull false
            isUnc(p) ||
                foreignHome(p, policy.currentUser) ||
                (policy.blockForeignWslMounts && underForeignMnt(p)) ||
                guarded.any { under(p, it) }
        }
    }

    /** Another user's home (`/home/<other>`, `/Users/<other>`, `C:/Users/<other>`, `/root` unless we are root). */
    fun foreignHome(path: String, currentUser: String?): Boolean {
        if (path == "/root" || path.startsWith("/root/")) return !currentUser.equals("root", ignoreCase = true)
        val user = HOME_SEGMENT.find(path)?.groupValues?.get(1) ?: return false
        if (user.equals("shared", ignoreCase = true) || user.equals("public", ignoreCase = true)) return false
        return currentUser != null && !user.equals(currentUser, ignoreCase = true)
    }

    /** WSL: `/mnt/<x>` where x ≠ c — a foreign or network Windows drive surfaced under the Linux root. */
    private fun underForeignMnt(path: String): Boolean =
        path.startsWith("/mnt/") && !path.startsWith("/mnt/c/") && path != "/mnt/c"

    /**
     * `\\server\share` / `//server/share` — remote by construction, on any OS.
     *
     * **Real incident**: a `//`-prefixed value isn't necessarily UNC — an ordinary C/JS-style line comment
     * (`// see below`) starts with `//` too, and [normalize] preserves that leading `//` (by design, so a real
     * UNC path survives normalization). The old check only asked "is the third character not another slash",
     * which a comment's leading space trivially satisfies — so `Edit`'s `old_string`/`new_string` (walked as a
     * path candidate like any other string leaf, see [pathCandidates]) flagged FOREIGN and hard-denied a
     * completely ordinary edit, for the agent's own trusted tool, with no way to override it. A real UNC
     * hostname never contains whitespace; a comment almost always does right after the slashes — so require the
     * host segment (up to the next `/`) to be non-blank AND whitespace-free.
     */
    fun isUnc(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (!p.startsWith("//") || p.length <= 2 || p[2] == '/') return false
        val host = p.substring(2).substringBefore('/')
        return host.isNotBlank() && host.none { it.isWhitespace() }
    }

    private fun under(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return r.isNotEmpty() && (path == r || path.startsWith("$r/", ignoreCase = true))
    }

    // ── rules exposed for tests ──────────────────────────────────────────────────────────────────────────

    fun touchesSensitivePath(input: JsonObject, globs: List<String>, home: String?): Boolean {
        val matchers = globs.map { compile(it, home) }
        return pathCandidates(input, home).any { p -> matchers.any { it.matches(p) } }
    }

    fun runsDangerousCommand(input: JsonObject): Boolean = dangerousCommand(input) != null

    /**
     * True when [input] carries a command/script string under a command-shaped key ([COMMAND_KEY]: `command`,
     * `cmd`, `script`, `shell`, `exec`, `run`, `args`/`argv`…) — i.e. this call executes something, whatever the
     * underlying shell (Bash, PowerShell, cmd.exe, sh, zsh…) and whatever the tool is named (the native `Bash`
     * tool, or an MCP tool like `execute_terminal_command`). A UI concern (the transcript uses this to render the
     * call's output as a copyable code block) built on the exact same detection the security rules already rely
     * on, so the two can never quietly drift apart into disagreeing about "is this a command".
     */
    fun isCommandCall(input: JsonObject): Boolean = commandCandidates(input).isNotEmpty()

    /** The raw command/script string [isCommandCall] detected, for rendering — `null` when there isn't one. */
    fun commandText(input: JsonObject): String? = commandCandidates(input).firstOrNull()

    private fun dangerousCommand(input: JsonObject): String? {
        for (command in commandCandidates(input)) {
            // Judge BOTH the raw command and its de-obfuscated form: an attacker hides `cat ~/.ssh/id_rsa` as
            // `c""at ~/.ss$IFS''h/id_rsa`, or ships it base64-encoded to `sh`. Matching only the raw string is a
            // sieve; matching the peeled string closes the cheap evasions (never all of them — see the class doc).
            for (candidate in setOf(expandEnv(command, null), deobfuscate(command))) {
                DANGEROUS_COMMANDS.firstOrNull { it.containsMatchIn(candidate) }
                    ?.let { return it.find(candidate)?.value?.take(120) }
            }
        }
        return null
    }

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
     *  - **`$HOME`/`~`** expansion (via [expandEnv]);
     *  - **base64 payloads** long enough to be a command (`echo <b64> | base64 -d | sh`) → decoded and appended,
     *    so a hidden `nc`/`curl`/key path inside the blob is matched too.
     */
    fun deobfuscate(command: String): String {
        var s = command
        // Line continuations first, so a command split across lines becomes one line.
        s = s.replace("\\\n", "").replace("\\\r\n", "")
        // $IFS (with or without braces, optionally $'...') → a plain space.
        s = s.replace(Regex("""\$\{?IFS\}?"""), " ").replace(Regex("""\$'\\(?:x09|011|t)'"""), " ")
        // Delete empty quote pairs and stray quotes/backticks used purely to break up tokens.
        s = s.replace("''", "").replace("\"\"", "").replace("``", "")
        // A backslash before a normal (non-space) char is a no-op in the shell for our purposes: drop it.
        s = s.replace(Regex("""\\([A-Za-z0-9._/~-])"""), "$1")
        // Now collapse the remaining quotes/backticks that wrap fragments (`"cat"` → cat, `'id'_rsa` → id_rsa).
        s = s.replace(Regex("""["'`]"""), "")
        // Resolve trivial `name=value` assignments, then substitute `$name`/`${name}` with the value.
        s = substituteAssignments(s)
        // Expand $HOME/~ etc.
        s = expandEnv(s, null)
        // Decode any base64 blob long enough to be a hidden command, and append it so its contents get matched.
        decodeBase64Payloads(s).takeIf { it.isNotEmpty() }?.let { s += " " + it.joinToString(" ") }
        return s
    }

    /** `k=~/.ssh/id_rsa … $k` → `… ~/.ssh/id_rsa`. Only literal, single-token assignments; good enough for the net. */
    /**
     * `k=~/.ssh/id_rsa … $k` → `… ~/.ssh/id_rsa`.
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
                if (decoded.isNotBlank() && decoded.all { it == '\t' || it == '\n' || it in ' '..'~' }) out += decoded
            }
        }
        return out
    }

    // ── input surface: every string leaf, not a key list ─────────────────────────────────────────────────

    fun pathCandidates(input: JsonObject, home: String?): List<String> {
        val out = LinkedHashSet<String>()
        walkStrings(input) { key, value ->
            if (COMMAND_KEY.matches(key)) {
                // A command hides paths in variables and quotes; tokenise the raw AND the de-obfuscated form.
                val sources = setOf(value, deobfuscate(value))
                sources.forEach { src ->
                    commandTokens(src).forEach { tok -> if (pathish(tok)) out += normalize(tok, home) }
                }
            } else if (pathish(value)) {
                out += normalize(value, home)
            }
        }
        return out.toList()
    }

    private fun commandCandidates(input: JsonObject): List<String> {
        val out = ArrayList<String>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> for ((k, v) in element) when {
                    COMMAND_KEY.matches(k) && v is JsonPrimitive && v.isString -> out.add(v.content)
                    COMMAND_KEY.matches(k) && v is JsonArray -> {
                        val joined = v.filterIsInstance<JsonPrimitive>().filter { it.isString }
                            .joinToString(" ") { it.content }
                        if (joined.isNotBlank()) out.add(joined)
                    }
                    else -> visit(v)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(input)
        return out
    }

    private fun walkStrings(element: JsonElement, key: String = "", visit: (String, String) -> Unit) {
        when (element) {
            is JsonObject -> for ((k, v) in element) walkStrings(v, k, visit)
            is JsonArray -> element.forEach { walkStrings(it, key, visit) }
            is JsonPrimitive -> if (element.isString) visit(key, element.content)
        }
    }

    private fun pathish(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_PATH_LEN) return false
        if (value.any { it == '\n' || it == '\r' }) return false
        if (URLISH.containsMatchIn(value)) return false
        return true
    }

    private fun commandTokens(command: String): List<String> =
        command.split(Regex("""[\s;|&<>=(),"'`]+""")).filter { it.isNotBlank() }

    // ── normalisation ────────────────────────────────────────────────────────────────────────────────────

    /** One canonical form: `\`→`/`, env/`~` expanded, `//`→`/` (UNC's leading `//` kept), trailing `/` dropped. */
    fun normalize(path: String, home: String?): String {
        val expanded = expandEnv(path.trim(), home).replace('\\', '/')
        val unc = expanded.startsWith("//")
        val collapsed = expanded.replace(Regex("/{2,}"), "/")
        val result = if (unc) "/$collapsed" else collapsed
        return if (result.length > 1) result.trimEnd('/') else result
    }

    private fun expandEnv(value: String, home: String?): String {
        var v = value
        if (!home.isNullOrBlank()) {
            val h = home.replace('\\', '/').trimEnd('/')
            v = v.replace("\${HOME}", h).replace("\$HOME", h)
                .replace("\$env:USERPROFILE", h, ignoreCase = true)
                .replace("%USERPROFILE%", h, ignoreCase = true)
                .replace("%HOMEPATH%", h, ignoreCase = true)
                .replace("%APPDATA%", "$h/AppData/Roaming", ignoreCase = true)
                .replace("%LOCALAPPDATA%", "$h/AppData/Local", ignoreCase = true)
            if (v == "~") v = h else if (v.startsWith("~/") || v.startsWith("~\\")) v = h + "/" + v.substring(2)
        }
        return v
    }

    // ── glob engine ──────────────────────────────────────────────────────────────────────────────────────

    private fun compile(glob: String, home: String?): Matcher {
        val expanded = normalize(glob, home)
        val sb = StringBuilder()
        var i = 0
        while (i < expanded.length) {
            val c = expanded[i]
            when {
                c == '*' && i + 1 < expanded.length && expanded[i + 1] == '*' -> {
                    sb.append(".*"); i += 2
                    if (i < expanded.length && expanded[i] == '/') i++
                }
                c == '*' -> { sb.append("[^/]*"); i++ }
                c == '?' -> { sb.append("[^/]"); i++ }
                else -> { sb.append(Regex.escape(c.toString())); i++ }
            }
        }
        return Matcher(Regex(sb.toString(), RegexOption.IGNORE_CASE))
    }

    /** Matches the whole path, or — for a name-only pattern — the final segment (a bare `*.pem` behaves). */
    @JvmInline
    value class Matcher(private val re: Regex) {
        fun matches(path: String): Boolean =
            re.matches(path) || re.matches(path.substringAfterLast('/'))
    }
}
