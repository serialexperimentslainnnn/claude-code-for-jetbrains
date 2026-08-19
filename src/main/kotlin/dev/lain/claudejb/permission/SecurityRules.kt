package dev.lain.claudejb.permission

/**
 * The two-level vocabulary of [SensitiveGuard]: a **category** is what the UI groups by, a **rule** is what one
 * toggle switches and what one hit names.
 *
 * ### Why one enum replaced two enums and seven booleans
 * The guard used to carry a flat `Category`, a `ForeignReason` sub-enum for the one category that needed finer
 * grain, and one `enforce*` boolean per rule on `SensitiveGuard.Policy`. Three consequences, all of them paid
 * for more than once: the sub-enum existed only because FOREIGN had three rules and nothing else did, so the
 * shape said "this category is special" when the real fact is that a category has rules; `isEnforced` was a
 * five-branch `when` with a nested three-branch `when` inside it, which is a lookup written as control flow;
 * and **a new rule was enforced only if somebody remembered to add its boolean and wire it**, which is a
 * security default decided by diligence.
 *
 * The set of DISABLED rules is the storage instead (`SensitiveGuard.Policy.disabledRules`), and zero-trust falls
 * out of the default: an empty set enforces everything, so a rule added tomorrow is on the moment it exists, and
 * forgetting to wire its toggle fails safe rather than silently off.
 *
 * ### Granularity is what keeps the off switch survivable
 * Every rule here is deliberately **narrow**, and that is a security property rather than a cosmetic one. The
 * permission card carries a one-click "disable this rule" link, so **a rule is the blast radius of that click**:
 * a single "block destructive operations" toggle would mean a user who needs `terraform destroy` also opens
 * `DROP DATABASE` and `git push --force` in the same gesture. Seven destructive rules instead of one is what lets
 * the click open exactly the vector that fired and leave every other one — including the ones nobody has thought
 * of yet — enforced. Bulk toggles exist, but only on the Settings surfaces, reached on purpose.
 *
 * ### The constant names are the persisted ids, and they are a wire format
 * `ClaudeSettings.State.disabledSecurityRules` stores them by [Enum.name], the composer's ⚙ menu sends them as
 * `rule:<NAME>`, and both are verified through [SecurityRule.from]. **Renaming a constant therefore silently
 * re-enables that rule** for everyone who had turned it off — the id would no longer resolve and the CSV entry
 * would be dropped. Add constants freely; rename one only with a migration.
 */
enum class SecurityCategory(val label: String) {
    /** What an attacker comes for, and the commands that fetch it. */
    SENSITIVE_DATA("Sensitive data"),

    /** Where a call is allowed to act, and how it is allowed to act there. */
    FILESYSTEM_BOUNDARY("Filesystem boundary"),

    /** Space that is not this machine's own: another user, another host, another OS' drive. */
    FOREIGN_TERRITORY("Foreign territory"),

    /** The machine underneath the filesystem. */
    SYSTEM_INTEGRITY("System integrity"),

    /** Where the call talks to, and through what. */
    NETWORK_EGRESS("Network egress"),

    /**
     * **The second axis, and the only one that is not about an attacker.**
     *
     * Every other category answers "is somebody stealing something". This one answers "is this action
     * irreversible", and a misread instruction is enough to trip it — no hostile model required. `terraform
     * destroy` against the wrong workspace, a `DROP DATABASE` meant for the test instance, an `rm -rf` whose
     * variable turned out empty: legitimate commands with no undo, which is why they are judged by shape and
     * never by where they run.
     */
    DESTRUCTIVE_OPERATION("Destructive operations"),

    /**
     * Turning this machine into something that runs someone else's code — now, or after the session ends.
     *
     * Not a path and not a domain, so nothing in the location or egress families can see any of it: a package
     * install runs its author's post-install script, a cron entry or a git hook runs again tomorrow, and an
     * `LD_PRELOAD` runs inside a process that was trusted to do something else.
     */
    CODE_EXECUTION("Code execution & persistence"),

    /**
     * **Intrusion techniques — the attacker's own kill chain, recognised so it can be stopped.**
     *
     * This is the category the guard exists FOR: a prompt injection is an attacker acting through the agent, and
     * an attacker does not stop at one file — they walk a chain (reconnaissance, credential access, defence
     * evasion, lateral movement, C2). Every other category protects one resource; this one recognises the
     * *adversary's methods*, mapped to MITRE ATT&CK tactics, so the deepest coverage in the whole set lives here.
     *
     * **It is a detector, not an attacker.** Nothing here runs a technique — each rule reads a command and
     * decides to STOP it, exactly as a Yara signature or an EDR rule names malware in order to catch it. Knowing
     * how an intrusion works is the precondition for detecting one, which is the whole of detection engineering.
     *
     * **Two design lines keep it from rotting or crying wolf**, and both are lessons this package already paid
     * for:
     *  - A curated list of tool names is a *blacklist*, and a blacklist is what you miss the next tool with (the
     *    `/dev` enumeration, the stale `AGENT_TOOLS`). So the curated half is paired with SHAPE-based rules that
     *    catch the unknown — an outbound connection to an undeclared host by its form, not by a name.
     *  - The dual-use floor is intact: `whoami`, `id`, `ps`, `find`, `sudo` are what an honest agent runs all
     *    day, so discovery is closed by *reading an enumeration FILE*, never by a command name. A rule that
     *    interrupts routine work is a rule switched off, taking the rest of the category with it.
     *
     * **The whole category disables as one toggle** — the population that legitimately needs these techniques
     * (an authorised red-team engagement on the user's own machine) turns the category off in Settings, in the
     * cold, does the work, and turns it back on. Disabling it opens NONE of the confidentiality or destructive
     * walls: it is one deliberate choice with a bounded blast radius, which is the whole point of the grouping.
     */
    INTRUSION_TECHNIQUE("Intrusion techniques"),

    /**
     * **What the guard cannot read, it cannot judge** — and a call it cannot judge must not be waved through.
     *
     * Every other category answers "is this thing bad". These answer "is this thing knowable", which is the
     * question a rule set gets walked around at: a destination hidden behind a variable whose value lives in the
     * process environment, and a command hidden inside a file. Both were reachable with no rule saying a word,
     * because each ends in the guard matching a string that does not contain what will actually happen.
     */
    OPAQUE("Opaque to the guard"),
}

/**
 * How deep the guard follows an indirection before it stops following and starts refusing — one bound, shared by
 * the two things that can nest: a variable defined in terms of another variable, and a script that runs a script.
 *
 * Five is past any real configuration and any real build wrapper, and the number is not the point: **reaching it
 * is itself the finding**. Anything that needs a sixth hop to say where it is going, or a sixth file to say what
 * it runs, is structured to be unanalysable, and [SecurityRule.RECURSION_LIMIT] answers that with a block rather
 * than a shrug — a card is for "the plugin cannot see this", and this is "something went to trouble so it could
 * not be seen".
 *
 * It is also what makes both recursions TERMINATE on the thread that reads the binary's entire stdout, which is
 * the thread nothing in this package may hang (see [GuardPaths.expandWithResolved]).
 */
internal const val MAX_ANALYSIS_DEPTH = 5

/**
 * One switchable rule of [SensitiveGuard]. See [SecurityCategory] for why this shape.
 *
 * Four strings, each with one audience, because the same sentence cannot serve all of them:
 *  - [label] is the row both settings surfaces draw (the composer's ⚙ menu and Settings ▸ Security);
 *  - [hint] is the examples only the Settings page has room for;
 *  - [blockedReason] and [blockedWhy] are what the **model** is told when the rule fires — what it cannot do,
 *    and why that is worth refusing.
 *
 * One string per purpose, in one place, because the two surfaces disagreeing about what a rule is called is how a
 * user turns off a rule they thought was another one — and because a block message assembled at the call site is
 * a message that drifts from the toggle it is telling the user about.
 *
 * **[blockedReason] and [blockedWhy] deliberately never name the Settings path.** The model is not told where the
 * off switch is: telling a possibly-hijacked agent which lever to ask the user to pull is a workaround with extra
 * steps. The human gets that link instead, on the card.
 */
enum class SecurityRule(
    val category: SecurityCategory,
    val label: String,
    val hint: String,
    /** What the model is told it cannot do — one sentence, second person, no jargon and no rule id. */
    val blockedReason: String,
    /** Why that is refused — the mechanism, so the answer reads as a reason rather than as a policy citation. */
    val blockedWhy: String,
    /**
     * May the always-allow list lift this rule's block for one exact command? **Defaults to false**, and the
     * default is the security property: a rule added next year cannot be whitelisted past until somebody
     * deliberately decides it can be.
     *
     * True only on rules that judge an ACTION — a destructive command, an install, a shell write, a version-control
     * safeguard being skipped. Never on a wall: credentials, foreign territory, a device, egress, or something the
     * guard could not read. That guarantee is structural rather than a promise, because the walls are asked first
     * in [SensitiveGuard]'s severity ordering, so a command that trips one is reported AS the wall — and a wall is
     * not whitelistable. There is no way to allow-list `cat ~/.ssh/id_rsa`.
     */
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

        /**
         * The rule this id names, or null — **case-sensitive and exact**, because it is a wire format.
         *
         * Every id reaching the guard comes from somewhere the user could have typed (a stored CSV) or from a
         * browser (the ⚙ menu's `rule:<NAME>` key), and an unknown id resolves to null, which the callers turn
         * into "no such rule": a garbled entry can therefore only ever fail to DISABLE something. That direction
         * is the whole reason the storage is the disabled set rather than the enabled one.
         */
        fun from(id: String): SecurityRule? = entries.firstOrNull { it.name == id }

        /** The rules of one category, in declaration order — the order both UIs draw them in. */
        fun of(category: SecurityCategory): List<SecurityRule> = entries.filter { it.category == category }

        /**
         * The ONE canonical spelling of a stored disabled set: known ids in declaration order, then any id this
         * build cannot resolve, in the order it arrived.
         *
         * It exists because **two surfaces write this field** — the Settings page rebuilds it from one checkbox
         * per rule, the composer's ⚙ menu toggles one entry of it — and a set has no inherent order, so
         * without one owner for the spelling the two produce different strings for the same configuration. The
         * visible cost of that is small and confusing: the Settings page compares its own rendering against the
         * stored text to decide whether anything was edited, so a menu-written order made the page open with
         * *Apply* already enabled and nothing on it changed.
         *
         * Unknown ids are kept rather than pruned: one can only come from a newer version, and dropping it here
         * would re-enable, on somebody's next OK, a rule they turned off in a later IDE.
         */
        fun canonicalCsv(ids: Collection<String>): String {
            val trimmed = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val known = trimmed.mapNotNull { from(it) }.distinct().sortedBy { it.ordinal }.map { it.name }
            val unknown = trimmed.filter { from(it) == null }
            return (known + unknown).joinToString(",")
        }
    }
}
