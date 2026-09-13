package dev.lain.claudejb.model.permission.vocab

enum class SecurityRule(
    val category: SecurityCategory,
    val label: String,
    val whitelistable: Boolean = false,
) {
    CREDENTIALS(SecurityCategory.SENSITIVE_DATA, "Block credential files"),
    SECRET_DUMPING_COMMANDS(SecurityCategory.SENSITIVE_DATA, "Block dangerous commands"),
    VCS_PROTECTION_BYPASS(
        SecurityCategory.SENSITIVE_DATA,
        "Block version-control commands that skip a safeguard",
        whitelistable = true,
    ),

    OUTSIDE_PROJECT(SecurityCategory.FILESYSTEM_BOUNDARY, "Block access outside the project"),
    TEMP_DIR(SecurityCategory.FILESYSTEM_BOUNDARY, "Block the system temporary directory"),
    SHELL_FILE_WRITE(SecurityCategory.FILESYSTEM_BOUNDARY, "Block shell commands that write files", whitelistable = true),

    OTHER_USER_HOME(SecurityCategory.FOREIGN_TERRITORY, "Block other users' home folders"),
    NETWORK_MOUNT(SecurityCategory.FOREIGN_TERRITORY, "Block network mounts"),
    WSL_MOUNT(SecurityCategory.FOREIGN_TERRITORY, "Block other WSL drives"),

    SYSTEM_DEVICE(SecurityCategory.SYSTEM_INTEGRITY, "Block system devices"),

    PRIVILEGE_ESCALATION(
        SecurityCategory.SYSTEM_INTEGRITY,
        "Block running as another user or as root",
        whitelistable = true,
    ),

    PROXY_BYPASS(SecurityCategory.NETWORK_EGRESS, "Block egress that bypasses the proxy"),
    TUNNELING(SecurityCategory.NETWORK_EGRESS, "Block tunnels and anonymising proxies", whitelistable = true),

    BLOCKED_DOMAIN(SecurityCategory.NETWORK_EGRESS, "Block staging and exfiltration domains"),

    DESTRUCTIVE_IAC(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block infrastructure teardown (terraform/pulumi)",
        whitelistable = true,
    ),
    DESTRUCTIVE_ORCHESTRATION(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block cluster deletion (kubectl/helm)",
        whitelistable = true,
    ),
    DESTRUCTIVE_CLOUD(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block cloud resource deletion (aws/gcloud/az)",
        whitelistable = true,
    ),
    DESTRUCTIVE_DATABASE(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block database destruction (DROP/TRUNCATE/FLUSH)",
        whitelistable = true,
    ),
    DESTRUCTIVE_CONTAINER(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block container/volume destruction (docker)",
        whitelistable = true,
    ),
    DESTRUCTIVE_GIT(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block git history loss (force-push/reset/clean)",
        whitelistable = true,
    ),
    DESTRUCTIVE_FILESYSTEM(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block mass filesystem destruction (rm -rf/mkfs/dd)",
        whitelistable = true,
    ),

    INHIBIT_RECOVERY(SecurityCategory.DESTRUCTIVE_OPERATION, "Block inhibiting system recovery", whitelistable = true),

    PACKAGE_INSTALL_HOOK(
        SecurityCategory.CODE_EXECUTION,
        "Block package installs that run install hooks",
        whitelistable = true,
    ),
    PERSISTENCE_MECHANISM(
        SecurityCategory.CODE_EXECUTION,
        "Block persistence (cron/systemd/git-hooks)",
        whitelistable = true,
    ),
    CODE_INJECTION(
        SecurityCategory.CODE_EXECUTION,
        "Block library preloading and env code injection",
        whitelistable = true,
    ),

    HACKING_TOOL(SecurityCategory.INTRUSION_TECHNIQUE, "Block known intrusion tooling"),
    REVERSE_SHELL(SecurityCategory.INTRUSION_TECHNIQUE, "Block reverse and bind shells"),
    PRIVESC_EXEC(
        SecurityCategory.INTRUSION_TECHNIQUE,
        "Block GTFOBins-style shell escapes and privilege escalation",
    ),

    CONTAINER_ESCAPE(SecurityCategory.INTRUSION_TECHNIQUE, "Block container escapes to the host", whitelistable = true),

    RESOURCE_HIJACKING(SecurityCategory.INTRUSION_TECHNIQUE, "Block cryptocurrency miners"),

    DISABLE_DEFENCES(SecurityCategory.DEFENCE_EVASION, "Block turning off security defences", whitelistable = true),

    ANTI_FORENSIC(SecurityCategory.DEFENCE_EVASION, "Block erasing the session's own tracks", whitelistable = true),

    UNRESOLVED_VARIABLE(SecurityCategory.OPAQUE, "Block a destination hidden behind a variable"),
    SCRIPT_EXECUTION(SecurityCategory.OPAQUE, "Analyse scripts before they run"),
    RECURSION_LIMIT(SecurityCategory.OPAQUE, "Block indirection deeper than the analysis follows"),
    ;

    companion object {

        fun from(id: String): SecurityRule? = entries.firstOrNull { it.name == id }

        fun of(category: SecurityCategory): List<SecurityRule> = entries.filter { it.category == category }

        fun canonicalCsv(ids: Collection<String>): String {
            val trimmed = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val known = trimmed.mapNotNull { from(it) }.distinct().sortedBy { it.ordinal }.map { it.name }
            val unknown = trimmed.filter { from(it) == null }
            return (known + unknown).joinToString(",")
        }
    }
}
