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
     * **What the guard cannot read, it cannot judge** — and a call it cannot judge must not be waved through.
     *
     * Every other category answers "is this thing bad". These two answer "is this thing knowable", which is the
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
 * it runs, is structured to be unanalysable, and [SecurityRule.RECURSION_LIMIT] is the rule that says so — the
 * difference between "the plugin cannot see this" and "something went to trouble so it could not be seen".
 *
 * It is also what makes both recursions TERMINATE on the thread that reads the binary's entire stdout, which is
 * the thread nothing in this package may hang (see [GuardPaths.expandWithResolved]).
 */
internal const val MAX_ANALYSIS_DEPTH = 5

/**
 * One switchable rule of [SensitiveGuard]. See [SecurityCategory] for why this shape.
 *
 * [label] is the row both surfaces draw (the composer's ⚙ menu and Settings ▸ Security); [hint] is the examples
 * only the Settings page has room for. One string per purpose, in one place, because the two surfaces
 * disagreeing about what a rule is called is how a user turns off a rule they thought was another one.
 */
enum class SecurityRule(
    val category: SecurityCategory,
    val label: String,
    val hint: String,
    // There was a `deniesEveryCaller` flag here, true for the three foreign-territory rules and for
    // RECURSION_LIMIT, marking the ones that were a hard block rather than a card even for the agent's own tools.
    // EVERY enforced rule denies every caller now (see `SensitiveGuard.verdictFor`), so the flag carried no
    // information — and worse, it read as if the rules without it were somehow softer, which is precisely the
    // reading that made an enforced rule negotiable in the first place. What decides an outcome today is one fact
    // and it lives elsewhere: whether the user switched the rule off.
) {
    CREDENTIALS(
        SecurityCategory.SENSITIVE_DATA,
        "Block credential files",
        "SSH/GPG keys, cloud and DB secrets, access tokens, browser login data",
    ),
    SECRET_DUMPING_COMMANDS(
        SecurityCategory.SENSITIVE_DATA,
        "Block dangerous commands",
        "credential dumps, exfiltration, piping the network into a shell, offensive tooling",
    ),

    OUTSIDE_PROJECT(
        SecurityCategory.FILESYSTEM_BOUNDARY,
        "Block access outside the project",
        "any absolute path argument that resolves outside the open project's own folder",
    ),
    TEMP_DIR(
        SecurityCategory.FILESYSTEM_BOUNDARY,
        "Block the system temporary directory",
        "/tmp, /var/tmp, macOS /var/folders, %TEMP% — the project is exempt even when it sits under one",
    ),
    SHELL_FILE_WRITE(
        SecurityCategory.FILESYSTEM_BOUNDARY,
        "Block shell commands that write files",
        "tee, cp, mv, rm, sed -i, dd of=, > redirects — writes with no diff to review, inside the project too",
    ),

    OTHER_USER_HOME(
        SecurityCategory.FOREIGN_TERRITORY,
        "Block other users' home folders",
        "/home/<someone-else>, /Users/<someone-else>, /root",
    ),
    NETWORK_MOUNT(
        SecurityCategory.FOREIGN_TERRITORY,
        "Block network mounts",
        "NFS, CIFS/SMB, SSHFS, UNC \\\\server\\share, removable media",
    ),
    WSL_MOUNT(
        SecurityCategory.FOREIGN_TERRITORY,
        "Block other WSL drives",
        "any /mnt/* other than /mnt/c, under WSL only",
    ),

    SYSTEM_DEVICE(
        SecurityCategory.SYSTEM_INTEGRITY,
        "Block system devices",
        "/dev/sda, /dev/mem, /proc/<pid>/mem — and the pseudo-devices: /dev/null (hiding output is how a " +
            "problem hides), /dev/urandom, /dev/stdin (an injection surface), /dev/fd/<n>, a tty, a TPM",
    ),

    PROXY_BYPASS(
        SecurityCategory.NETWORK_EGRESS,
        "Block egress that bypasses the proxy",
        "an alternate --proxy, an inline http_proxy=, --noproxy — only when a proxy is actually declared",
    ),
    BLOCKED_DOMAIN(
        SecurityCategory.NETWORK_EGRESS,
        "Block staging and exfiltration domains",
        "pastebin, transfer.sh, webhook.site, interact.sh, ngrok and the like, plus your own list",
    ),

    UNRESOLVED_VARIABLE(
        SecurityCategory.OPAQUE,
        "Block a destination hidden behind a variable",
        "the launch environment is expanded FIRST, so this is only what nothing could resolve — cat \$CREDS " +
            "with CREDS set nowhere the plugin can read",
    ),
    SCRIPT_EXECUTION(
        SecurityCategory.OPAQUE,
        "Analyse scripts before they run",
        "source x.sh, bash x.sh, ./x.sh, python x.py — the file is READ and its commands judged; a script that " +
            "trips nothing runs unasked, and one that cannot be read at all is refused as unreadable",
    ),
    RECURSION_LIMIT(
        SecurityCategory.OPAQUE,
        "Block indirection deeper than the analysis follows",
        "a variable defined through a variable, or a script running a script, more than $MAX_ANALYSIS_DEPTH " +
            "deep — or a cycle. Reaching the bound is itself the finding",
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
         * It exists because **two surfaces write this field** — the Settings page rebuilds it from twelve
         * checkboxes, the composer's ⚙ menu toggles one entry of it — and a set has no inherent order, so
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
