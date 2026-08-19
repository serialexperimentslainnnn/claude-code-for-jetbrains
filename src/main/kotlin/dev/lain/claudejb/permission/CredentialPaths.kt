package dev.lain.claudejb.permission

object CredentialPaths {

    val SENSITIVE_GLOBS: List<String> = listOf(
        "**/.ssh/**", "**/id_rsa*", "**/id_dsa*", "**/id_ecdsa*", "**/id_ed25519*", "**/*_rsa", "**/*.ppk",
        "**/.gnupg/**", "**/.pki/**", "**/*.pem", "**/*.key", "**/*.p8", "**/*.p12", "**/*.pfx",
        "**/*.jks", "**/*.keystore", "**/*.asc", "**/*.gpg", "**/*.kdbx", "**/*.agekey",
        "**/*.ovpn", "**/wg*.conf", "/etc/wireguard/**", "/etc/ipsec.secrets", "**/*.mobileconfig",
        "**/.aws/**", "**/.azure/**", "**/.config/gcloud/**", "**/gcloud/**/credentials.db", "**/.oci/**",
        "**/.config/doctl/**", "**/.config/hcloud/**", "**/.config/scw/**", "**/.aliyun/**", "**/.config/linode-cli",
        "**/.kube/config", "**/.kube/**/*config*", "**/.docker/config.json", "**/.config/containers/auth.json",
        "**/.terraform.d/credentials.tfrc.json", "**/*.tfstate", "**/*.tfstate.backup", "**/.config/pulumi/**",
        "**/.ansible/**/*vault*", "**/.config/rclone/rclone.conf", "**/.s3cfg", "**/.boto",
        "**/.netrc", "**/_netrc", "**/.npmrc", "**/.yarnrc.yml", "**/.pypirc", "**/.gem/credentials",
        "**/.cargo/credentials*", "**/.gradle/gradle.properties", "**/.m2/settings.xml", "**/.bundle/config",
        "**/.composer/auth.json", "**/.nuget/NuGet.Config", "**/.git-credentials", "**/.config/gh/hosts.yml",
        "**/.config/glab-cli/**", "**/.config/hub", "**/.config/git/credentials",
        "**/.pgpass", "**/.my.cnf", "**/.mylogin.cnf", "**/.mysql_history", "**/.psql_history", "**/.dbeaver/**",
        "**/.mongorc.js", "**/.rediscli_history",
        "**/.bash_history", "**/.zsh_history", "**/.sh_history", "**/.python_history", "**/.node_repl_history",
        "**/.local/share/fish/fish_history", "**/.irb_history", "**/.lesshst",
        "**/.password-store/**", "**/.config/Bitwarden*/**", "**/1Password/**", "**/*.opvault/**",
        "**/logins.json", "**/key4.db", "**/signons.sqlite", "**/Login Data", "**/Cookies", "**/cookies.sqlite",
        "**/wallet.dat", "**/*.wallet", "**/.electrum/**", "**/.ethereum/keystore/**", "**/.bitcoin/wallet.dat",
        "**/.msmtprc", "**/.fetchmailrc", "**/.authinfo", "**/.authinfo.gpg",
        "**/Library/Keychains/**", "**/*.keychain-db", "**/*.keychain",
        "**/AppData/Roaming/Microsoft/Credentials/**", "**/AppData/Local/Microsoft/Credentials/**",
        "**/AppData/Roaming/Microsoft/Protect/**", "**/AppData/Local/Microsoft/Vault/**",
        "**/AppData/Roaming/Microsoft/SystemCertificates/**", "**/AppData/**/gcloud/**", "**/*.rdp",
        "**/NTUSER.DAT", "**/Windows/System32/config/SAM", "**/Windows/System32/config/SECURITY",
        "**/Windows/System32/config/SYSTEM",
        "/run/secrets/**", "/var/run/secrets/**", "**/serviceaccount/token", "/proc/*/environ",
        "**/.claude/.credentials.json", "**/.claude/**/*credential*", "**/.config/anthropic/**",
        "**/.codex/**", "**/.config/openai/**", "**/.openai/**",
        "**/.config/github-copilot/**", "**/github-copilot/hosts.json", "**/github-copilot/apps.json",
        "**/.cursor/**/*token*", "**/.cursor/**/*credential*", "**/.config/Cursor/**/*token*",
        "**/.codeium/**", "**/.codeium/windsurf/**",
        "**/.continue/**/*token*", "**/.continue/config.json", "**/.aider*", "**/.aider.conf.yml",
        "**/.config/TabNine/**", "**/.gemini/**", "**/.config/zed/**/*token*",
        "**/.config/gh-copilot/**", "**/.sourcegraph/**", "**/.src-config.json",
        "**/.config/JetBrains/**/*token*", "**/.local/share/JetBrains/**/*token*",
        "**/.config/gh/hosts.yml", "**/.config/glab-cli/**", "**/.config/hub", "**/.config/git/credentials",
        "**/.config/tea/**", "**/.config/bb/**", "**/.gitconfig.local",
        "**/.huggingface/token", "**/.cache/huggingface/token", "**/.kaggle/kaggle.json",
        "**/.config/heroku/**", "**/.fly/**", "**/.config/fly/**", "**/.railway/**", "**/.config/railway/**",
        "**/.wrangler/**", "**/.cloudflared/**", "**/.config/stripe/**", "**/.sentryclirc",
        "**/.config/configstore/*.json", "**/.jfrog/**", "**/.config/doctl/**", "**/.vault-token",
        "**/.supabase/**", "**/.config/supabase/**", "**/.planetscale/**", "**/.config/ngrok*/**",
        "/etc/shadow", "/etc/gshadow", "/etc/master.passwd", "/etc/sudoers", "/etc/sudoers.d/**",
        "/etc/ssl/private/**", "/etc/ssh/*_key", "/etc/krb5.keytab", "**/krb5cc_*", "**/.k5login", "**/.htpasswd",
        "**/.env", "**/.env.*", "**/.envrc", "**/secrets.y*ml", "**/secrets.json", "**/credentials.json",
        "**/service-account*.json", "**/.vault-token", "**/.netlify/state.json", "**/.vercel/**",
    )

    private val compiled = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, Matcher>()

    internal fun compile(glob: String, home: String?): Matcher =
        compiled.computeIfAbsent(glob to home) { buildMatcher(glob, home) }

    private fun buildMatcher(glob: String, home: String?): Matcher {
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

    @JvmInline
    value class Matcher(private val re: Regex) {
        fun matches(path: String): Boolean =
            re.matches(path) || re.matches(path.substringAfterLast('/'))
    }
}
