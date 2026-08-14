package dev.lain.claudejb.permission

/**
 * Rule family 1 of [SensitiveGuard] — **credentials and key material**: the blacklist of files worth stealing,
 * and the glob engine that recognises them.
 *
 * Matched **by shape, wherever the file sits**, never anchored to a specific home. Anchoring to `$HOME` goes blind
 * on Windows (`C:\Users\bob\.ssh`) and WSL (`/mnt/c/Users/bob/.ssh`), where the interesting home is not the one the
 * JVM reports; one structural `.ssh` glob catches Linux, macOS, Windows and WSL at once (and a `.aws` fixture
 * inside a repo too, which is correct).
 *
 * The list is tunable from Settings only **additively**: the effective globs are these plus the user's extras, so
 * a built-in can never be removed from the set (see `ClaudeSettings.sensitiveGlobs`).
 */
object CredentialPaths {

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
        "**/.codex/**", "**/.config/openai/**", "**/.openai/**", // OpenAI / Codex
        "**/.config/github-copilot/**", "**/github-copilot/hosts.json", "**/github-copilot/apps.json", // Copilot
        "**/.cursor/**/*token*", "**/.cursor/**/*credential*", "**/.config/Cursor/**/*token*", // Cursor
        "**/.codeium/**", "**/.codeium/windsurf/**", // Codeium / Windsurf
        "**/.continue/**/*token*", "**/.continue/config.json", "**/.aider*", "**/.aider.conf.yml",
        "**/.config/TabNine/**", "**/.gemini/**", "**/.config/zed/**/*token*", // TabNine / Gemini / Zed
        "**/.config/gh-copilot/**", "**/.sourcegraph/**", "**/.src-config.json", // Copilot CLI / Cody
        "**/.config/JetBrains/**/*token*", "**/.local/share/JetBrains/**/*token*",
        // Source-repo & package/registry API keys — access to your code and your supply chain
        "**/.config/gh/hosts.yml", "**/.config/glab-cli/**", "**/.config/hub", "**/.config/git/credentials",
        "**/.config/tea/**", "**/.config/bb/**", "**/.gitconfig.local", // gitea / bitbucket
        "**/.huggingface/token", "**/.cache/huggingface/token", "**/.kaggle/kaggle.json", // model registries
        "**/.config/heroku/**", "**/.fly/**", "**/.config/fly/**", "**/.railway/**", "**/.config/railway/**",
        "**/.wrangler/**", "**/.cloudflared/**", "**/.config/stripe/**", "**/.sentryclirc", // PaaS / CDN / SaaS
        "**/.config/configstore/*.json", "**/.jfrog/**", "**/.config/doctl/**", "**/.vault-token",
        "**/.supabase/**", "**/.config/supabase/**", "**/.planetscale/**", "**/.config/ngrok*/**",
        // Unix system secrets
        "/etc/shadow", "/etc/gshadow", "/etc/master.passwd", "/etc/sudoers", "/etc/sudoers.d/**",
        "/etc/ssl/private/**", "/etc/ssh/*_key", "/etc/krb5.keytab", "**/krb5cc_*", "**/.k5login", "**/.htpasswd",
        // Project secrets (also match inside the repo — that is the point)
        "**/.env", "**/.env.*", "**/.envrc", "**/secrets.y*ml", "**/secrets.json", "**/credentials.json",
        "**/service-account*.json", "**/.vault-token", "**/.netlify/state.json", "**/.vercel/**",
    )

    // ── glob engine ──────────────────────────────────────────────────────────────────────────────────────

    internal fun compile(glob: String, home: String?): Matcher {
        val expanded = GuardPaths.normalize(glob, home)
        val sb = StringBuilder()
        var i = 0
        while (i < expanded.length) {
            val c = expanded[i]
            when {
                c == '*' && i + 1 < expanded.length && expanded[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < expanded.length && expanded[i] == '/') i++
                }

                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }

                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }

                else -> {
                    sb.append(Regex.escape(c.toString()))
                    i++
                }
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
