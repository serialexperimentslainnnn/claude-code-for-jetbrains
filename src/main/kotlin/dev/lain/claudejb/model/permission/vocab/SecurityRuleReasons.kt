package dev.lain.claudejb.model.permission.vocab

val SecurityRule.blockedReason: String get() = BLOCKED.getValue(this).reason

val SecurityRule.blockedWhy: String get() = BLOCKED.getValue(this).why

private class BlockText(val reason: String, val why: String)

private val BLOCKED: Map<SecurityRule, BlockText> = mapOf(
    SecurityRule.CREDENTIALS to BlockText(
        "You can't read credentials or sensitive data.",
        "This data can be exfiltrated to an attacker or to the server you're sending this session information.",
    ),
    SecurityRule.SECRET_DUMPING_COMMANDS to BlockText(
        "You can't run this command.",
        "It can dump stored secrets, exfiltrate data, or hand an attacker a way to run code on this machine.",
    ),
    SecurityRule.VCS_PROTECTION_BYPASS to BlockText(
        "You can't skip a version-control safeguard.",
        "Forcing an ignored file into the repository, or committing with the hooks disabled, is how a credential " +
            "ends up in history — where it stays after the commit is gone and has to be rotated.",
    ),
    SecurityRule.OUTSIDE_PROJECT to BlockText(
        "You can't act on files outside the open project.",
        "A path outside the project is not something the user brought into this session on purpose, and it is " +
            "exactly where an attacker's instructions would try to send you.",
    ),
    SecurityRule.TEMP_DIR to BlockText(
        "You can't act on the system temporary directory.",
        "It is a world-writable location with no review, and it's where data gets staged before it is sent out.",
    ),
    SecurityRule.SHELL_FILE_WRITE to BlockText(
        "You can't write or modify files through a shell command.",
        "A shell write has no diff for the user to review, so a malicious change here would land unnoticed.",
    ),
    SecurityRule.OTHER_USER_HOME to BlockText(
        "You can't access another user's home directory.",
        "That is someone else's private space on this machine, with no legitimate reason for this session to be " +
            "in it.",
    ),
    SecurityRule.NETWORK_MOUNT to BlockText(
        "You can't access a network or removable mount.",
        "That is a path onto another machine or share — exactly how data leaves this one.",
    ),
    SecurityRule.WSL_MOUNT to BlockText(
        "You can't access another WSL drive.",
        "That is a path off this machine's own filesystem, the same reason a network mount is refused.",
    ),
    SecurityRule.SYSTEM_DEVICE to BlockText(
        "You can't address a raw system device.",
        "That bypasses the filesystem and every permission check it would normally apply — reading raw memory or " +
            "a disk directly, or opening a network connection disguised as a file.",
    ),
    SecurityRule.PRIVILEGE_ESCALATION to BlockText(
        "You can't run this with elevated privileges.",
        "Every other rule here is scoped to what this account may already do. Root is outside that scope: it " +
            "reaches any file on the machine, and a mistake made there is not recoverable by the user who " +
            "approved it.",
    ),
    SecurityRule.PROXY_BYPASS to BlockText(
        "You can't route network traffic around the proxy that's declared.",
        "Bypassing it hides that traffic from whatever inspection or logging the user put the proxy there for.",
    ),
    SecurityRule.TUNNELING to BlockText(
        "You can't open a network tunnel or route through an anonymiser.",
        "A reverse or dynamic tunnel turns this machine into an entry point or an exfiltration channel, " +
            "and an anonymiser hides where traffic goes. Legitimate port-forwarding is the user's to " +
            "whitelist, not ours to leave open.",
    ),
    SecurityRule.BLOCKED_DOMAIN to BlockText(
        "You can't talk to this destination.",
        "It's a known anonymous drop/collect service — exactly the kind of place stolen data or a payload gets " +
            "staged.",
    ),
    SecurityRule.DESTRUCTIVE_IAC to BlockText(
        "You can't tear down infrastructure.",
        "It deletes real cloud resources — databases, storage, whole environments — in seconds, and there is no " +
            "undo once the state is applied.",
    ),
    SecurityRule.DESTRUCTIVE_ORCHESTRATION to BlockText(
        "You can't delete cluster resources at scale.",
        "Deleting a namespace, draining a node or uninstalling a release removes running workloads and their " +
            "data at once, and a live cluster does not put them back.",
    ),
    SecurityRule.DESTRUCTIVE_CLOUD to BlockText(
        "You can't delete cloud resources.",
        "Removing a bucket, a database instance or a VM destroys the data it holds, and the provider does not " +
            "restore a deleted resource.",
    ),
    SecurityRule.DESTRUCTIVE_DATABASE to BlockText(
        "You can't drop or wipe a database.",
        "Dropping, truncating or flushing erases stored data irreversibly, and it is one statement away from " +
            "destroying production.",
    ),
    SecurityRule.DESTRUCTIVE_CONTAINER to BlockText(
        "You can't prune or remove containers and volumes.",
        "Pruning and volume removal delete the data inside them, and -v/prune reach volumes a running app still " +
            "depends on.",
    ),
    SecurityRule.DESTRUCTIVE_GIT to BlockText(
        "You can't rewrite or discard git history destructively.",
        "A force-push, hard reset or filter rewrite erases commits — a whole team's work on a shared branch — " +
            "and clean -fdx deletes untracked files with no recovery.",
    ),
    SecurityRule.DESTRUCTIVE_FILESYSTEM to BlockText(
        "You can't mass-delete the filesystem or overwrite a disk.",
        "A recursive delete near a root or home, a reformat or a raw disk write destroys data across the machine " +
            "at once, with nothing to roll back to.",
    ),
    SecurityRule.INHIBIT_RECOVERY to BlockText(
        "You can't disable or destroy the system's recovery.",
        "Deleting backups and shadow copies or turning recovery off removes the only way back from a " +
            "destructive change — it is the step ransomware takes before it encrypts, and nothing in " +
            "development needs it.",
    ),
    SecurityRule.PACKAGE_INSTALL_HOOK to BlockText(
        "You can't install an arbitrary package.",
        "Package managers run install-time scripts, so installing an untrusted package executes its author's " +
            "code on this machine — the primary software-supply-chain attack.",
    ),
    SecurityRule.PERSISTENCE_MECHANISM to BlockText(
        "You can't install a persistence mechanism.",
        "A cron entry, a timer or a git hook makes code run again after this session ends — how an attacker " +
            "keeps access — and it runs outside anything the user is watching.",
    ),
    SecurityRule.CODE_INJECTION to BlockText(
        "You can't inject a library into a process.",
        "Preloading a library forces your code into another program's memory, bypassing what that program was " +
            "trusted to do — a classic hijack and evasion primitive.",
    ),
    SecurityRule.HACKING_TOOL to BlockText(
        "You can't run this intrusion tool.",
        "It is purpose-built for attacking systems — dumping credentials, scanning for a way in, or running an " +
            "exploit — and nothing in ordinary development invokes it.",
    ),
    SecurityRule.REVERSE_SHELL to BlockText(
        "You can't open a reverse or bind shell.",
        "Wiring a shell to a network socket hands remote control of this machine to whoever is on the other end " +
            "— it is the payload an intrusion drops first and has no legitimate use in development.",
    ),
    SecurityRule.PRIVESC_EXEC to BlockText(
        "You can't use that binary to escape to a shell.",
        "These are the documented GTFOBins escapes: a trusted tool coerced into spawning a shell or running a " +
            "command, which is how a restricted context — or a sudo rule — becomes full command execution.",
    ),
    SecurityRule.CONTAINER_ESCAPE to BlockText(
        "You can't break a container out onto the host.",
        "Entering PID 1's namespaces, mounting the host's root filesystem, or running a --privileged " +
            "container hands it full control of the machine it runs on. That has legitimate uses, so it is " +
            "whitelistable — but it is a decision with a record, not a default, exactly like sudo.",
    ),
    SecurityRule.RESOURCE_HIJACKING to BlockText(
        "You can't run a cryptocurrency miner.",
        "Mining software exists to spend this machine's CPU, GPU and power on someone else's behalf. A " +
            "coding session never runs one, and it is a common payload dropped after a machine is " +
            "compromised.",
    ),
    SecurityRule.DISABLE_DEFENCES to BlockText(
        "You can't disable the machine's security defences.",
        "Turning off the firewall, the audit system, SELinux/AppArmor, Gatekeeper or the antivirus removes " +
            "the protection an attack has to get past. A legitimate need is the user's to whitelist, not " +
            "ours to leave open.",
    ),
    SecurityRule.ANTI_FORENSIC to BlockText(
        "You can't erase the record of what happened here.",
        "A coding session has no legitimate reason to wipe the shell history or the system logs. Clearing " +
            "the trail is what an intrusion does to hide, and it destroys the evidence of everything else " +
            "that was done.",
    ),
    SecurityRule.UNRESOLVED_VARIABLE to BlockText(
        "You can't act on a destination hidden behind a variable this session can't resolve.",
        "An unverifiable destination could be anything, including one an attacker chose.",
    ),
    SecurityRule.SCRIPT_EXECUTION to BlockText(
        "You can't run this script.",
        "Its contents could not be read and judged, so there is no way to know it's safe before it runs.",
    ),
    SecurityRule.RECURSION_LIMIT to BlockText(
        "You can't use indirection this deep.",
        "A variable or script chain built this many layers deep is structured to avoid being analysed — " +
            "reaching the limit is itself the sign of that.",
    ),
)
