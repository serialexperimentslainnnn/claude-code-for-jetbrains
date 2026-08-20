package dev.lain.claudejb.permission

enum class SecurityCategory(val label: String) {
    SENSITIVE_DATA("Sensitive data"),

    FILESYSTEM_BOUNDARY("Filesystem boundary"),

    FOREIGN_TERRITORY("Foreign territory"),

    SYSTEM_INTEGRITY("System integrity"),

    NETWORK_EGRESS("Network egress"),

    DESTRUCTIVE_OPERATION("Destructive operations"),

    CODE_EXECUTION("Code execution & persistence"),

    INTRUSION_TECHNIQUE("Intrusion techniques"),

    DEFENCE_EVASION("Defence evasion"),

    OPAQUE("Opaque to the guard"),
}

internal const val MAX_ANALYSIS_DEPTH = 5

enum class SecurityRule(
    val category: SecurityCategory,
    val label: String,
    val hint: String,
    val blockedReason: String,
    val blockedWhy: String,
    val whitelistable: Boolean = false,
) {
    CREDENTIALS(
        SecurityCategory.SENSITIVE_DATA,
        "Block credential files",
        "SSH/GPG keys, cloud and DB secrets, access tokens, browser login data",
        "You can't read credentials or sensitive data.",
        "This data can be exfiltrated to an attacker or to the server you're sending this session information.",
    ),
    SECRET_DUMPING_COMMANDS(
        SecurityCategory.SENSITIVE_DATA,
        "Block dangerous commands",
        "credential dumps, exfiltration, piping the network into a shell, offensive tooling",
        "You can't run this command.",
        "It can dump stored secrets, exfiltrate data, or hand an attacker a way to run code on this machine.",
    ),
    VCS_PROTECTION_BYPASS(
        SecurityCategory.SENSITIVE_DATA,
        "Block version-control commands that skip a safeguard",
        "git add -f (defeats .gitignore, which is often what was keeping a key out of the repo) and --no-verify " +
            "on commit/push (skips the hooks, where secret scanning runs). An ordinary git add . or git commit " +
            "-a is NOT affected",
        "You can't skip a version-control safeguard.",
        "Forcing an ignored file into the repository, or committing with the hooks disabled, is how a credential " +
            "ends up in history — where it stays after the commit is gone and has to be rotated.",
        whitelistable = true,
    ),

    OUTSIDE_PROJECT(
        SecurityCategory.FILESYSTEM_BOUNDARY,
        "Block access outside the project",
        "any absolute path argument that resolves outside the open project's own folder",
        "You can't act on files outside the open project.",
        "A path outside the project is not something the user brought into this session on purpose, and it is " +
            "exactly where an attacker's instructions would try to send you.",
    ),
    TEMP_DIR(
        SecurityCategory.FILESYSTEM_BOUNDARY,
        "Block the system temporary directory",
        "/tmp, /var/tmp, macOS /var/folders, %TEMP% — the project is exempt even when it sits under one",
        "You can't act on the system temporary directory.",
        "It is a world-writable location with no review, and it's where data gets staged before it is sent out.",
    ),
    SHELL_FILE_WRITE(
        SecurityCategory.FILESYSTEM_BOUNDARY,
        "Block shell commands that write files",
        "tee, cp, mv, rm, sed -i, dd of=, > redirects — writes with no diff to review, inside the project too",
        "You can't write or modify files through a shell command.",
        "A shell write has no diff for the user to review, so a malicious change here would land unnoticed.",
        whitelistable = true,
    ),

    OTHER_USER_HOME(
        SecurityCategory.FOREIGN_TERRITORY,
        "Block other users' home folders",
        "/home/<someone-else>, /Users/<someone-else>, /root",
        "You can't access another user's home directory.",
        "That is someone else's private space on this machine, with no legitimate reason for this session to be " +
            "in it.",
    ),
    NETWORK_MOUNT(
        SecurityCategory.FOREIGN_TERRITORY,
        "Block network mounts",
        "NFS, CIFS/SMB, SSHFS, UNC \\\\server\\share, removable media",
        "You can't access a network or removable mount.",
        "That is a path onto another machine or share — exactly how data leaves this one.",
    ),
    WSL_MOUNT(
        SecurityCategory.FOREIGN_TERRITORY,
        "Block other WSL drives",
        "any /mnt/* other than /mnt/c, under WSL only",
        "You can't access another WSL drive.",
        "That is a path off this machine's own filesystem, the same reason a network mount is refused.",
    ),

    SYSTEM_DEVICE(
        SecurityCategory.SYSTEM_INTEGRITY,
        "Block system devices",
        "the whole of /dev — the disk (/dev/sda), memory (/dev/mem, /proc/<pid>/mem), the GPU, /dev/kvm, a bus, " +
            "a tty — and /dev/tcp/<host>/<port>, which is a network socket spelled as a file. /dev/null and " +
            "/dev/urandom are exempt",
        "You can't address a raw system device.",
        "That bypasses the filesystem and every permission check it would normally apply — reading raw memory or " +
            "a disk directly, or opening a network connection disguised as a file.",
    ),

    PRIVILEGE_ESCALATION(
        SecurityCategory.SYSTEM_INTEGRITY,
        "Block running as another user or as root",
        "sudo, su, doas, pkexec, runuser, sudoedit and the desktop wrappers on Linux and macOS; osascript " +
            "asking for administrator privileges; runas, Start-Process -Verb RunAs and psexec on Windows; " +
            "wsl -u root",
        "You can't run this with elevated privileges.",
        "Every other rule here is scoped to what this account may already do. Root is outside that scope: it " +
            "reaches any file on the machine, and a mistake made there is not recoverable by the user who " +
            "approved it.",
        whitelistable = true,
    ),

    PROXY_BYPASS(
        SecurityCategory.NETWORK_EGRESS,
        "Block egress that bypasses the proxy",
        "an alternate --proxy, an inline http_proxy=, --noproxy — only when a proxy is actually declared",
        "You can't route network traffic around the proxy that's declared.",
        "Bypassing it hides that traffic from whatever inspection or logging the user put the proxy there for.",
    ),
    BLOCKED_DOMAIN(
        SecurityCategory.NETWORK_EGRESS,
        "Block staging and exfiltration domains",
        "pastebin, transfer.sh, webhook.site, interact.sh, ngrok and the like, plus your own list",
        "You can't talk to this destination.",
        "It's a known anonymous drop/collect service — exactly the kind of place stolen data or a payload gets " +
            "staged.",
    ),

    DESTRUCTIVE_IAC(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block infrastructure teardown (terraform/pulumi)",
        "terraform destroy, terraform apply -auto-approve, terraform state rm, pulumi destroy, terragrunt destroy",
        "You can't tear down infrastructure.",
        "It deletes real cloud resources — databases, storage, whole environments — in seconds, and there is no " +
            "undo once the state is applied.",
        whitelistable = true,
    ),
    DESTRUCTIVE_ORCHESTRATION(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block cluster deletion (kubectl/helm)",
        "kubectl delete namespace/--all, kubectl drain, helm uninstall, helm rollback without --dry-run",
        "You can't delete cluster resources at scale.",
        "Deleting a namespace, draining a node or uninstalling a release removes running workloads and their " +
            "data at once, and a live cluster does not put them back.",
        whitelistable = true,
    ),
    DESTRUCTIVE_CLOUD(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block cloud resource deletion (aws/gcloud/az)",
        "aws s3 rb --force, aws rds delete-db-instance, ec2 terminate-instances, gcloud/az … delete",
        "You can't delete cloud resources.",
        "Removing a bucket, a database instance or a VM destroys the data it holds, and the provider does not " +
            "restore a deleted resource.",
        whitelistable = true,
    ),
    DESTRUCTIVE_DATABASE(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block database destruction (DROP/TRUNCATE/FLUSH)",
        "DROP DATABASE/TABLE, TRUNCATE, mysqladmin drop, MongoDB dropDatabase/dropCollection, Redis FLUSHALL",
        "You can't drop or wipe a database.",
        "Dropping, truncating or flushing erases stored data irreversibly, and it is one statement away from " +
            "destroying production.",
        whitelistable = true,
    ),
    DESTRUCTIVE_CONTAINER(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block container/volume destruction (docker)",
        "docker system prune, docker volume rm, docker rm -f, docker-compose down -v",
        "You can't prune or remove containers and volumes.",
        "Pruning and volume removal delete the data inside them, and -v/prune reach volumes a running app still " +
            "depends on.",
        whitelistable = true,
    ),
    DESTRUCTIVE_GIT(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block git history loss (force-push/reset/clean)",
        "git push --force, reset --hard, clean -fdx, filter-branch/filter-repo, branch -D",
        "You can't rewrite or discard git history destructively.",
        "A force-push, hard reset or filter rewrite erases commits — a whole team's work on a shared branch — " +
            "and clean -fdx deletes untracked files with no recovery.",
        whitelistable = true,
    ),
    DESTRUCTIVE_FILESYSTEM(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block mass filesystem destruction (rm -rf/mkfs/dd)",
        "rm -rf at or near a root or a home, mkfs, shred, dd of= a disk — an ordinary rm -rf build/ is NOT " +
            "affected",
        "You can't mass-delete the filesystem or overwrite a disk.",
        "A recursive delete near a root or home, a reformat or a raw disk write destroys data across the machine " +
            "at once, with nothing to roll back to.",
        whitelistable = true,
    ),

    INHIBIT_RECOVERY(
        SecurityCategory.DESTRUCTIVE_OPERATION,
        "Block inhibiting system recovery",
        "destroying the means to recover — wbadmin delete, bcdedit recoveryenabled no, vssadmin resize " +
            "shadowstorage, WMI shadow-copy deletion, diskshadow, Disable-ComputerRestore, and macOS " +
            "tmutil disable",
        "You can't disable or destroy the system's recovery.",
        "Deleting backups and shadow copies or turning recovery off removes the only way back from a " +
            "destructive change — it is the step ransomware takes before it encrypts, and nothing in " +
            "development needs it.",
        whitelistable = true,
    ),

    PACKAGE_INSTALL_HOOK(
        SecurityCategory.CODE_EXECUTION,
        "Block package installs that run install hooks",
        "npm/pip/gem/cargo install of an arbitrary package — a post-install script runs code you did not review",
        "You can't install an arbitrary package.",
        "Package managers run install-time scripts, so installing an untrusted package executes its author's " +
            "code on this machine — the primary software-supply-chain attack.",
        whitelistable = true,
    ),
    PERSISTENCE_MECHANISM(
        SecurityCategory.CODE_EXECUTION,
        "Block persistence (cron/systemd/git-hooks)",
        "crontab install, at, systemd timers, git core.hooksPath, writes under .git/hooks — code that runs LATER",
        "You can't install a persistence mechanism.",
        "A cron entry, a timer or a git hook makes code run again after this session ends — how an attacker " +
            "keeps access — and it runs outside anything the user is watching.",
        whitelistable = true,
    ),
    CODE_INJECTION(
        SecurityCategory.CODE_EXECUTION,
        "Block library preloading and env code injection",
        "LD_PRELOAD=, LD_LIBRARY_PATH= into a command, DYLD_INSERT_LIBRARIES — inject a library into a process",
        "You can't inject a library into a process.",
        "Preloading a library forces your code into another program's memory, bypassing what that program was " +
            "trusted to do — a classic hijack and evasion primitive.",
        whitelistable = true,
    ),

    HACKING_TOOL(
        SecurityCategory.INTRUSION_TECHNIQUE,
        "Block known intrusion tooling",
        "credential dumpers (mimikatz, lazagne, secretsdump), scanners (nmap, masscan, ffuf), exploitation " +
            "(sqlmap, metasploit), AD attack (rubeus, certipy, kerbrute), C2 (sliver, mythic), privesc " +
            "enumeration (linpeas, winpeas) — matched at command position, never as a bare mention",
        "You can't run this intrusion tool.",
        "It is purpose-built for attacking systems — dumping credentials, scanning for a way in, or running an " +
            "exploit — and nothing in ordinary development invokes it.",
    ),
    REVERSE_SHELL(
        SecurityCategory.INTRUSION_TECHNIQUE,
        "Block reverse and bind shells",
        "an interactive shell wired to a socket — bash -i >& /dev/tcp, nc/ncat/socat -e, and the python/perl/" +
            "php/ruby/node/powershell one-liners that connect a socket to /bin/sh — matched by SHAPE, so an " +
            "unlisted spelling still trips",
        "You can't open a reverse or bind shell.",
        "Wiring a shell to a network socket hands remote control of this machine to whoever is on the other end " +
            "— it is the payload an intrusion drops first and has no legitimate use in development.",
    ),
    PRIVESC_EXEC(
        SecurityCategory.INTRUSION_TECHNIQUE,
        "Block GTFOBins-style shell escapes and privilege escalation",
        "using an ordinary binary to spawn a shell or run a command it was not meant to — find -exec /bin/sh, " +
            "vim/less/awk/tar shell escapes, env/nice/timeout SHELL tricks, especially behind sudo — the " +
            "GTFOBins technique set",
        "You can't use that binary to escape to a shell.",
        "These are the documented GTFOBins escapes: a trusted tool coerced into spawning a shell or running a " +
            "command, which is how a restricted context — or a sudo rule — becomes full command execution.",
    ),

    RESOURCE_HIJACKING(
        SecurityCategory.INTRUSION_TECHNIQUE,
        "Block cryptocurrency miners",
        "known mining binaries (xmrig, minerd, cpuminer, ethminer, cgminer, t-rex and the like) and the " +
            "stratum+tcp:// pool-protocol scheme they connect with — matched at command position",
        "You can't run a cryptocurrency miner.",
        "Mining software exists to spend this machine's CPU, GPU and power on someone else's behalf. A " +
            "coding session never runs one, and it is a common payload dropped after a machine is " +
            "compromised.",
    ),

    ANTI_FORENSIC(
        SecurityCategory.DEFENCE_EVASION,
        "Block erasing the session's own tracks",
        "clearing the shell history (history -c, unset HISTFILE, set +o history), vacuuming the systemd " +
            "journal, and the Windows equivalents (Clear-History, Set-PSReadlineOption SaveNothing, " +
            "wevtutil cl, Clear-EventLog) — matched at command position, never as a bare mention",
        "You can't erase the record of what happened here.",
        "A coding session has no legitimate reason to wipe the shell history or the system logs. Clearing " +
            "the trail is what an intrusion does to hide, and it destroys the evidence of everything else " +
            "that was done.",
        whitelistable = true,
    ),

    UNRESOLVED_VARIABLE(
        SecurityCategory.OPAQUE,
        "Block a destination hidden behind a variable",
        "the launch environment is expanded FIRST, so this is only what nothing could resolve — cat \$CREDS " +
            "with CREDS set nowhere the plugin can read",
        "You can't act on a destination hidden behind a variable this session can't resolve.",
        "An unverifiable destination could be anything, including one an attacker chose.",
    ),
    SCRIPT_EXECUTION(
        SecurityCategory.OPAQUE,
        "Analyse scripts before they run",
        "source x.sh, bash x.sh, ./x.sh, python x.py — the file is READ and its commands judged; a script that " +
            "trips nothing runs unasked, and one that cannot be read at all is refused as unreadable",
        "You can't run this script.",
        "Its contents could not be read and judged, so there is no way to know it's safe before it runs.",
    ),
    RECURSION_LIMIT(
        SecurityCategory.OPAQUE,
        "Block indirection deeper than the analysis follows",
        "a variable defined through a variable, or a script running a script, more than $MAX_ANALYSIS_DEPTH " +
            "deep — or a cycle. Reaching the bound is itself the finding",
        "You can't use indirection this deep.",
        "A variable or script chain built this many layers deep is structured to avoid being analysed — " +
            "reaching the limit is itself the sign of that.",
    ),
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
