package dev.lain.claudejb.model.permission.vocab

val SecurityRule.hint: String get() = HINTS.getValue(this)

private val HINTS: Map<SecurityRule, String> = mapOf(
    SecurityRule.CREDENTIALS to "SSH/GPG keys, cloud and DB secrets, access tokens, browser login data",
    SecurityRule.SECRET_DUMPING_COMMANDS to
        "credential dumps, exfiltration, piping the network into a shell, offensive tooling",
    SecurityRule.VCS_PROTECTION_BYPASS to
        "git add -f (defeats .gitignore, which is often what was keeping a key out of the repo) and --no-verify " +
        "on commit/push (skips the hooks, where secret scanning runs). An ordinary git add . or git commit " +
        "-a is NOT affected",
    SecurityRule.OUTSIDE_PROJECT to "any absolute path argument that resolves outside the open project's own folder",
    SecurityRule.TEMP_DIR to "/tmp, /var/tmp, macOS /var/folders, %TEMP% — the project is exempt even when it sits under one",
    SecurityRule.SHELL_FILE_WRITE to
        "tee, cp, mv, rm, sed -i, dd of=, > redirects — writes with no diff to review, inside the project too",
    SecurityRule.OTHER_USER_HOME to "/home/<someone-else>, /Users/<someone-else>, /root",
    SecurityRule.NETWORK_MOUNT to "NFS, CIFS/SMB, SSHFS, UNC \\\\server\\share, removable media",
    SecurityRule.WSL_MOUNT to "any /mnt/* other than /mnt/c, under WSL only",
    SecurityRule.SYSTEM_DEVICE to
        "the whole of /dev — the disk (/dev/sda), memory (/dev/mem, /proc/<pid>/mem), the GPU, /dev/kvm, a bus, " +
        "a tty — and /dev/tcp/<host>/<port>, which is a network socket spelled as a file. /dev/null and " +
        "/dev/urandom are exempt",
    SecurityRule.PRIVILEGE_ESCALATION to
        "sudo, su, doas, pkexec, runuser, sudoedit and the desktop wrappers on Linux and macOS; osascript " +
        "asking for administrator privileges; runas, Start-Process -Verb RunAs and psexec on Windows; " +
        "wsl -u root",
    SecurityRule.PROXY_BYPASS to
        "an alternate --proxy, an inline http_proxy=, --noproxy — only when a proxy is actually declared",
    SecurityRule.TUNNELING to
        "outbound tunnels and command-and-control or exfiltration channels — ssh -R/-D/-L, ngrok, " +
        "cloudflared, chisel, frp, localtunnel, bore, iodine/dnscat2 DNS tunnels, and tor/proxychains " +
        "anonymisers",
    SecurityRule.BLOCKED_DOMAIN to "pastebin, transfer.sh, webhook.site, interact.sh, ngrok and the like, plus your own list",
    SecurityRule.DESTRUCTIVE_IAC to
        "terraform destroy, terraform apply -auto-approve, terraform state rm, pulumi destroy, terragrunt destroy",
    SecurityRule.DESTRUCTIVE_ORCHESTRATION to
        "kubectl delete namespace/--all, kubectl drain, helm uninstall, helm rollback without --dry-run",
    SecurityRule.DESTRUCTIVE_CLOUD to
        "aws s3 rb --force, aws rds delete-db-instance, ec2 terminate-instances, gcloud/az … delete",
    SecurityRule.DESTRUCTIVE_DATABASE to
        "DROP DATABASE/TABLE, TRUNCATE, mysqladmin drop, MongoDB dropDatabase/dropCollection, Redis FLUSHALL",
    SecurityRule.DESTRUCTIVE_CONTAINER to "docker system prune, docker volume rm, docker rm -f, docker-compose down -v",
    SecurityRule.DESTRUCTIVE_GIT to "git push --force, reset --hard, clean -fdx, filter-branch/filter-repo, branch -D",
    SecurityRule.DESTRUCTIVE_FILESYSTEM to
        "rm -rf at or near a root or a home, mkfs, shred, dd of= a disk — an ordinary rm -rf build/ is NOT " +
        "affected",
    SecurityRule.INHIBIT_RECOVERY to
        "destroying the means to recover — wbadmin delete, bcdedit recoveryenabled no, vssadmin resize " +
        "shadowstorage, WMI shadow-copy deletion, diskshadow, Disable-ComputerRestore, and macOS " +
        "tmutil disable",
    SecurityRule.PACKAGE_INSTALL_HOOK to
        "npm/pip/gem/cargo install of an arbitrary package — a post-install script runs code you did not review",
    SecurityRule.PERSISTENCE_MECHANISM to
        "crontab install, at, systemd timers, git core.hooksPath, writes under .git/hooks — code that runs LATER",
    SecurityRule.CODE_INJECTION to
        "LD_PRELOAD=, LD_LIBRARY_PATH= into a command, DYLD_INSERT_LIBRARIES — inject a library into a process",
    SecurityRule.HACKING_TOOL to
        "credential dumpers (mimikatz, lazagne, secretsdump), scanners (nmap, masscan, ffuf), exploitation " +
        "(sqlmap, metasploit), AD attack (rubeus, certipy, kerbrute), C2 (sliver, mythic), privesc " +
        "enumeration (linpeas, winpeas) — matched at command position, never as a bare mention",
    SecurityRule.REVERSE_SHELL to
        "an interactive shell wired to a socket — bash -i >& /dev/tcp, nc/ncat/socat -e, and the python/perl/" +
        "php/ruby/node/powershell one-liners that connect a socket to /bin/sh — matched by SHAPE, so an " +
        "unlisted spelling still trips",
    SecurityRule.PRIVESC_EXEC to
        "using an ordinary binary to spawn a shell or run a command it was not meant to — find -exec /bin/sh, " +
        "vim/less/awk/tar shell escapes, env/nice/timeout SHELL tricks, especially behind sudo — the " +
        "GTFOBins technique set",
    SecurityRule.CONTAINER_ESCAPE to
        "entering the host's namespaces (nsenter into PID 1, /proc/1/ns), mounting the host's root " +
        "filesystem into a container (-v /:/…, --mount source=/), and running a container with full " +
        "host control (--privileged, hostPID, privileged: true), dangerous capabilities (--cap-add SYS_ADMIN, " +
        "NET_ADMIN, SYS_RAWIO…), and disabled seccomp/AppArmor/SELinux/no-new-privileges " +
        "on docker, podman, nerdctl, kubectl and oc — the documented ways out onto the host",
    SecurityRule.RESOURCE_HIJACKING to
        "known mining binaries (xmrig, minerd, cpuminer, ethminer, cgminer, t-rex and the like) and the " +
        "stratum+tcp:// pool-protocol scheme they connect with — matched at command position",
    SecurityRule.DISABLE_DEFENCES to
        "disabling the host's protections — setenforce 0, stopping auditd/firewalld/apparmor, ufw disable, " +
        "flushing the firewall, spctl/csrutil disable on macOS, and disabling Windows Defender or the " +
        "firewall (Set-MpPreference, netsh advfirewall off, auditpol /clear)",
    SecurityRule.ANTI_FORENSIC to
        "clearing the shell history (history -c, unset HISTFILE, set +o history), vacuuming the systemd " +
        "journal, and the Windows equivalents (Clear-History, Set-PSReadlineOption SaveNothing, " +
        "wevtutil cl, Clear-EventLog) — matched at command position, never as a bare mention",
    SecurityRule.UNRESOLVED_VARIABLE to
        "the launch environment is expanded FIRST, so this is only what nothing could resolve — cat \$CREDS " +
        "with CREDS set nowhere the plugin can read",
    SecurityRule.SCRIPT_EXECUTION to
        "source x.sh, bash x.sh, ./x.sh, python x.py — the file is READ and its commands judged; a script that " +
        "trips nothing runs unasked, and one that cannot be read at all is refused as unreadable",
    SecurityRule.RECURSION_LIMIT to
        "a variable defined through a variable, or a script running a script, more than $MAX_ANALYSIS_DEPTH " +
        "deep — or a cycle. Reaching the bound is itself the finding",
)
