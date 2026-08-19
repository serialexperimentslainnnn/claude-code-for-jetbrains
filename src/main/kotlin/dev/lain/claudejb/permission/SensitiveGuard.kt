package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A guardrail against an agent — by accident, or by prompt injection — reading what a real attacker would come for,
 * running what a real attacker would run, or writing where nobody will look.
 *
 * Read the whole doc before touching a rule: the value here is that it is thought through, not that it is long. It
 * reacts to curated surfaces and nothing else, so ordinary development trips it as little as the mandate allows — a
 * guard that cries wolf is a guard the user switches off, and then it protects nothing.
 *
 * **It protects two parties, and the second one is easy to forget.** The user's machine, obviously — their keys,
 * their other users' homes, their disks. And the MODEL: every refusal here is a misuse control on the provider's
 * side of the same call, because a model talked into exfiltrating a key by a poisoned repository, a web page or a
 * tool result is a model being used against its own policy. Nothing in a prompt can argue with this code, so a
 * successful injection ends at a wall the model cannot be persuaded to open — which is worth more to whoever
 * trained it than to anyone else.
 *
 * ### Where the rules live
 * This object is the **policy and the verdict**: what the guard knows about the caller, which rules are enforced,
 * and the single [classify] pass that produces both the verdict and its wording. Each rule family, and each phase it
 * runs through, is a file of its own in this package — split by seam, not by size:
 *  - [ToolInputScanner] — the input surface: every string leaf as a path candidate, every command-shaped key as a
 *    command, every URL as a destination. Everything below is matched against what it produces.
 *  - [GuardPaths] — the path phase: one canonical form, containment, and the bounded off-thread real-path resolve.
 *  - [SecurityRule] — the two-level vocabulary every rule below is named in, and the storage of what is switched off.
 *  - [CredentialPaths] — credentials and key material ([CredentialPaths.SENSITIVE_GLOBS] + the glob engine).
 *  - [ForeignTerritory] — another user's home, network/UNC mounts, foreign WSL drives.
 *  - [CommandRules] — dangerous commands ([CommandRules.DANGEROUS_COMMANDS]) and the de-obfuscation applied
 *    before matching them.
 *  - [TempDirs] — the system temporary directory.
 *  - [SystemDevices] — the raw block device, physical memory, another process's memory.
 *  - [ShellFileWrites] — a command that writes or modifies a file, i.e. a write with no diff to review.
 *  - [ProxyRules] — egress that routes around the proxy the user declared.
 *  - [DangerousDomains] — the curated staging/exfiltration destinations.
 *  - [EnvIndirection] — a destination hidden behind a variable the guard cannot resolve.
 *  - [ScriptExecution] — a script file being sourced or launched, whose contents never reach this guard.
 *
 * ### The surfaces, by [SecurityCategory]
 *
 * **[SecurityCategory.SENSITIVE_DATA]** — [SecurityRule.CREDENTIALS] matches by shape, wherever the file sits, never
 * anchored to a specific home (see [CredentialPaths]); [SecurityRule.SECRET_DUMPING_COMMANDS] catches commands that
 * dump a secret at rest, exfiltrate a file, pipe the network into a shell, or invoke recognised offensive/LOLBIN
 * tooling (see [CommandRules]).
 *
 * **[SecurityCategory.FOREIGN_TERRITORY]** — another user's home, a network/removable mount, a foreign WSL drive:
 * not agentic development, but lateral movement. The only exemption is the open project's own root — see
 * [ForeignTerritory]. This is the one category that denies **every** caller (below).
 *
 * **[SecurityCategory.SYSTEM_INTEGRITY]** — [SecurityRule.SYSTEM_DEVICE]: the device that BACKS the filesystem, or
 * live memory, addressed directly. Nothing about ordinary development touches those nodes, so it is a stronger claim
 * than a credential glob and wins the wording over one — see [SystemDevices].
 *
 * **[SecurityCategory.NETWORK_EGRESS]** — [SecurityRule.BLOCKED_DOMAIN] (a curated set of anonymous
 * drop/collect services — see [DangerousDomains]) and [SecurityRule.PROXY_BYPASS] (a command naming a different
 * proxy, or asking to skip the declared one — see [ProxyRules]).
 *
 * **[SecurityCategory.FILESYSTEM_BOUNDARY]** — the three weakest claims, and they are about the SURFACE rather than
 * about danger:
 *  - [SecurityRule.TEMP_DIR]: `/tmp` and its equivalents, the one world-writable place outside the project and
 *    outside every review surface, so it is where an agent stages what is not meant to be looked at. Matched by
 *    SEGMENT (`/tmpfoo` and `~/tmp` are not it), and exempt inside the open project like the other location rules
 *    — see [TempDirs].
 *  - [SecurityRule.SHELL_FILE_WRITE]: a `tee`, a `>` redirect or a `sed -i`. `Bash` is not in
 *    `DiffPresenter.REVIEWABLE_TOOLS`, so such a call mutates the filesystem with **zero review surface** — and
 *    that is as true inside the project as outside it, which is why this one rule is deliberately NOT exempted by
 *    the project root. It is the noisiest rule in the set (an agent runs `mkdir -p`, `touch` and `rm` constantly)
 *    and that is an accepted trade, not an oversight — see [ShellFileWrites].
 *  - [SecurityRule.OUTSIDE_PROJECT]: any ABSOLUTE **location** candidate ([ToolInputScanner.locationCandidates] —
 *    `file_path`, `path`, `uri`, `destination`… — never a command's own tokens, never a search pattern, never a
 *    payload) that resolves outside the open project and is not already caught by a rule above. The weakest claim
 *    of all, so it is checked last. It exists because `Read` (and every non-reviewable tool) is not covered by
 *    [PermissionBroker]'s root-containment check — that only gates the reviewable WRITE tools
 *    (`DiffPresenter.REVIEWABLE_TOOLS`) — so without this rule an agent could read anywhere on disk, outside the
 *    project, without ever being asked.
 *
 * **Command and pattern and payload tokens are excluded from that last rule on purpose, unlike the rules above
 * it.** CREDENTIALS/FOREIGN/SECRET_DUMPING_COMMANDS all scan a command's tokens too, because a genuine path really
 * can live inside one (`cat ~/.ssh/id_rsa`) and their match is a SPECIFIC shape — a credential glob, another user's
 * home, a UNC prefix. "Is this absolute and not under the project" has no such shape: a `sed`/`grep`/`rg` regex
 * delimited by slashes, or `Grep`'s own `pattern` argument, is absolute-LOOKING by pure coincidence of syntax, and
 * treating either as a location candidate turned an ordinary search into an unrelated "outside the project" card.
 *
 * ### Verdict, by trust of the CALLER — an allowlist, not a blacklist
 * The caller is trusted **only if it is one of the agent's own tools** ([AGENT_TOOLS]). Everything else — every MCP
 * server, every Skill, anything unrecognised — is third-party, because a blacklist of "bad" prefixes is exactly the
 * thing an attacker names their way around. By default this is a **hard lock**:
 *  - a **trusted** tool that trips any rule outside [SecurityCategory.FOREIGN_TERRITORY] → **ASK** (a card, every
 *    time, even under `bypassPermissions`): the user may authorise their own agent to read their own key, once,
 *    explicitly;
 *  - a **third-party** caller that trips one of those → **DENY**;
 *  - **anyone** who trips a [SecurityCategory.FOREIGN_TERRITORY] rule → **DENY**.
 *
 * ### Per-rule enforcement (Settings ▸ Claude Code ▸ Security) — never a silent allow
 * Every [SecurityRule] enforces by default; what the user can switch off is listed in
 * [Policy.disabledRules], and an empty set — the default — is the original hard lock exactly. Detection
 * ([classify]) runs **unconditionally**, regardless of that set: turning a rule off never skips recognising a
 * match. What it changes is [evaluate]'s OUTCOME: a disabled rule's hit is **downgraded from DENY to ASK**, for
 * every caller, including third-party ones — never to ALLOW. So "disabling a rule" means "I want to decide this one
 * myself, every time", not "stop watching for this".
 *
 * Two things are tunable *without* a toggle, and both only **widen** the net: the sensitive-path list
 * ([Policy.globs] = the built-in [CredentialPaths.SENSITIVE_GLOBS] plus the user's extras) and the blocked-domain
 * list ([Policy.extraBlockedDomains]). Neither can shrink its built-in half.
 *
 * ### Why this is enforceable even in `bypassPermissions`
 * The plugin launches the binary in `default` mode **always** — `acceptEdits`/`bypassPermissions` are implemented
 * here by auto-approving in [PermissionBroker] (`SessionLauncher.binaryPermissionMode`). Every tool call arrives as
 * a `can_use_tool` request whatever mode the user picked, so "never auto-approve this one" is the plugin's call to
 * make. The mode itself is untouched; that branch is simply not reached.
 *
 * ### Why the whole input, not a key list
 * A file argument is `file_path` — until an MCP server calls it `path`, `target`, `uri`, `destination`, or
 * something no one has seen. [ToolInputScanner.pathCandidates] walks **every string leaf** of the input, skipping
 * URLs and multi-line blobs so a `Write`'s *contents* are not mistaken for a filename, and a command key is
 * tokenised instead of matched whole because the paths there genuinely live inside the text.
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

    /**
     * The agent's OWN tools — the allowlist of trusted callers. Anything not in here (MCP, Skills, unknown) is
     * third-party and denied by default when it trips the guard.
     *
     * Kept in sync with the binary's built-in tool set — cross-referenced against the vendored SDK's own schema
     * (`node_modules/@anthropic-ai/claude-agent-sdk/sdk-tools.d.ts`, `ToolInputSchemas`), which is the project's
     * declared protocol source of truth. A REAL incident: this list had gone stale as the CLI grew its own
     * orchestration surface (background tasks, cron, worktrees…), so those native, first-party tool calls were
     * silently falling into the "third-party" branch and getting hard-DENIED instead of asking — indistinguishable,
     * from the user's chair, from an MCP server being blocked. `Skill` and any `mcp__*`-prefixed name are
     * DELIBERATELY excluded: a Skill's *content* is third-party (community/user-authored), same tier as MCP, by
     * design — see the class doc's caller-trust matrix.
     */
    val AGENT_TOOLS: Set<String> = setOf(
        "Bash", "Read", "Edit", "Write", "MultiEdit", "NotebookEdit", "NotebookRead",
        "Glob", "Grep", "LS", "Task", "Agent", "TodoWrite", "WebFetch", "WebSearch", "ExitPlanMode",
        "EnterPlanMode", "EnterWorktree", "ExitWorktree",
        "TaskCreate", "TaskGet", "TaskUpdate", "TaskList", "TaskOutput", "TaskStop",
        "CronCreate", "CronDelete", "CronList", "ScheduleWakeup", "SendMessage",
        "ListMcpResources", "ReadMcpResourceDir", "ReadMcpResource", "RefreshMcpTools",
        "Artifact", "ClaudeDesign", "DesignSync", "Monitor", "Projects", "ProposeSkills",
        "PushNotification", "RemoteTrigger", "REPL", "ReportFindings", "SendFeedback",
        "ShowOnboardingRolePicker", "Workflow",
        // Re-audited against `claude` 2.1.222 / SDK 0.3.222. Entries are only ever ADDED here, never removed:
        // this is a TRUST allowlist, not an inventory. A name that no longer exists costs nothing, while a
        // first-party name that is missing falls into the third-party branch and is hard-DENIED — the 4.4.0
        // incident described above. NB the CLI can also retire a tool per-session (it ships distinct
        // "is disabled for this session" / "is not available in this context" messages, and Glob/Grep do get
        // withdrawn in some sessions), which is another reason absence here must never be inferred from one run.
        "AskUserQuestion", "Mcp", "FileRead", "FileEdit", "FileWrite",
        // ToolSearch was absent, and it is the one that mattered most: it is how the agent loads the schema of
        // every DEFERRED tool (web, tasks, cron, worktrees), so on a session that defers them, the call that
        // unlocks all the others was the one landing in the untrusted branch. Found by diffing this list
        // against a live session's actual tool inventory rather than against the SDK's type names — those are
        // not the runtime registry (the SDK calls them FileRead/FileEdit/FileWrite; the tools are Read/Edit/Write).
        "ToolSearch",
    )

    /** Everything the guard needs to judge a call. Assembled by the IDE side; pure input here. */
    data class Policy(
        val globs: List<String> = CredentialPaths.SENSITIVE_GLOBS,
        /** The user's home, for expanding `~`/`$HOME`/`%USERPROFILE%`. */
        val home: String? = null,
        /** The current username — anyone else's home directory is foreign territory. */
        val currentUser: String? = null,
        /** Network/removable mount points discovered on this host — treated as foreign. */
        val guardedRoots: List<String> = emptyList(),
        /**
         * **A fact about the host, not a switch.** True when this machine is WSL, which is the only place
         * `/mnt/<x>` means "a Windows drive surfaced under the Linux root" — everywhere else `/mnt/data` is an
         * ordinary directory somebody made. Whether a hit is then ENFORCED is [SecurityRule.WSL_MOUNT]'s business,
         * i.e. [disabledRules]'; this decides whether there is anything to detect at all.
         */
        val wslHost: Boolean = false,
        /** The open project. A path under it is exempt from the FOREIGN rules — never from the credential globs. */
        val projectRoot: String? = null,
        /**
         * Optional **canonicaliser**: given a candidate path, return its real on-disk path (symlinks and `..`
         * resolved), or null if it cannot be resolved. Injected by the IDE side because it touches the filesystem;
         * the guard stays pure. When present, every RESOLVABLE-looking candidate (see `GuardPaths.looksResolvable`)
         * is judged on BOTH its literal and its resolved form, so a symlink `proj/innocent → ~/.ssh/id_rsa`, or
         * `proj/../../../etc/shadow`, cannot launder a path past the rules by hiding its true target. A resolver
         * that throws, returns null, or is too slow (see [GuardPaths.expandWithResolved]) just leaves the literal.
         *
         * **Caller contract — this WILL be called from whatever thread invokes [evaluate].**
         * A typical implementation (`File(x).canonicalPath`) is a blocking syscall with **no JDK-level timeout and
         * no interrupt**: on a hung/unresponsive network mount it can block the calling thread forever. [SensitiveGuard]
         * defends against that itself (bounded, off-thread, per [GuardPaths.expandWithResolved]) — but do not add
         * further blocking work inside this lambda beyond a single stat-like call, since the bound assumes that shape.
         */
        val pathResolver: ((String) -> String?)? = null,
        /**
         * **The environment the session will actually run with**, so a path written as `$CREDS` can be judged as
         * the path it names instead of as four characters no rule matches.
         *
         * This is the difference between analysing a call and refusing it: with the value in hand,
         * `cat $CREDS` is a credential read and gets the credential rule's own wording; without it, the only
         * honest answer would be a card on every command that mentions a variable — which is most of them, and
         * that is a rule users switch off in an afternoon.
         *
         * Assembled on the IDE side from the settings' own env block plus this IDE's environment (what
         * `ClaudeProcess` inherits). A variable defined ONLY inside a sourced script is not here — sourcing spawns
         * a shell and this policy is rebuilt on every `can_use_tool` — so such a name stays unresolved and ends in
         * a card, which is the right answer rather than a gap: `EnvScriptLoader` reads that script separately and
         * runs the same rules over it before it is ever sourced.
         *
         * Expanded TRANSITIVELY and to a fixpoint, bounded by [MAX_ANALYSIS_DEPTH] — see [GuardPaths.expandEnv].
         */
        val envValues: Map<String, String> = emptyMap(),
        /**
         * Optional **bounded reader**: given a path, return that file's text, or null when it cannot or should not
         * be read (missing, unreadable, too large, outside what the IDE side is willing to open).
         *
         * It is what turns [SecurityRule.SCRIPT_EXECUTION] from a blanket refusal into an analysis: a
         * `./gradlew build` whose script trips no rule runs unasked, and a `source /tmp/x.sh` that dumps a key is
         * refused *as a key dump*, naming the script it came from. Injected for the same reason [pathResolver] is
         * — the guard is pure and does not touch the filesystem — and it carries the same caller contract: it runs
         * on whatever thread called [evaluate], so the implementation must be bounded in size and time and must
         * never block on a network mount.
         *
         * Null (the default, and every unit test that does not opt in) means the guard cannot read a script, so
         * running one is opaque and worth a card. That is the fail-closed direction on purpose.
         */
        val fileReader: ((String) -> String?)? = null,
        /**
         * The rules the user switched OFF in Settings ▸ Claude Code ▸ Security. Empty by default, and that default
         * is the security model: **enforcement is not a list anyone has to remember to extend**, so a rule added
         * to [SecurityRule] tomorrow is enforced the moment it exists.
         *
         * Turning one off **never silently ALLOWs** a call that trips it — detection ([classify]) always runs
         * regardless; it only downgrades the OUTCOME from the hard block (DENY) to a permission card (ASK), for
         * every caller, so disabling a rule is never quiet. A trusted agent tool already gets a card either way
         * for everything outside [SecurityCategory.FOREIGN_TERRITORY], so for those rules this only ever changes
         * what an untrusted (MCP/Skill) caller gets: DENY when enforced, ASK when not.
         */
        val disabledRules: Set<SecurityRule> = emptySet(),
        /** The declared `HTTP_PROXY`, if any — a **data gate** for [SecurityRule.PROXY_BYPASS] (see [ProxyRules]). */
        val httpProxy: String? = null,
        /** The declared `HTTPS_PROXY`, if any. */
        val httpsProxy: String? = null,
        /** The declared `NO_PROXY` hosts: destinations the user already said may skip the proxy. */
        val noProxyHosts: List<String> = emptyList(),
        /** The user's OWN blocked domains, **added** to [DangerousDomains.BLOCKED_DOMAINS], never replacing it. */
        val extraBlockedDomains: List<String> = emptyList(),
    )

    // ── origin: trusted only if it is one of the agent's own tools ───────────────────────────────────────

    /**
     * True only for the agent's OWN tools. Everything else — MCP, Skills, unknown — is third-party.
     *
     * **A bare-name match, verified NOT to be a spoofing vector.** [AGENT_TOOLS] is a plain string set, so this
     * looks naively vulnerable to an MCP server registering a tool named exactly `Read` and walking into the
     * trusted branch by name collision. It isn't: the SDK's own published protocol (`sdk.d.ts`, `CanUseTool`'s
     * `tool_name` — "Fully-qualified MCP tool name, e.g. `mcp__server__tool_name`") states the wire name of every
     * MCP-provided tool is structurally prefixed `mcp__<server>__`, never bare — a server cannot suppress its own
     * prefix, so `Read` on the wire is always the native tool. The one documented way to make a call ARRIVE under
     * a bare first-party name while executing something else is `Options.toolAliases` (`{Bash: 'mcp__server__x'}`)
     * — but that is a launch-time option set by whoever configures the SDK, not by the MCP server itself, and this
     * plugin never sets it (`SessionLauncher.buildArgs` has no `toolAliases`). **This assumption breaks the day
     * `toolAliases` support is added here** — re-verify this comment before wiring it up.
     */
    fun isTrustedCaller(toolName: String): Boolean = toolName in AGENT_TOOLS

    // ── the decision ─────────────────────────────────────────────────────────────────────────────────────

    /** Path to the toggles in Settings — appended to every card/transcript reason, enforced or not, so the lever is
     *  always discoverable from the block/prompt itself, not just from documentation. */
    private const val SETTINGS_PATH = "Settings ▸ Claude Code ▸ Security"

    /**
     * A verdict together with the reason behind it — the ONLY form callers use.
     *
     * Both are produced from ONE [classify] pass, and that is the reason this is the only entry point:
     * classification is not cheap — [GuardPaths.expandWithResolved] canonicalises every path candidate on
     * disk, under a timeout, precisely because a `stat()` on a dead network mount can block. A verdict-only
     * and a reason-only function used to exist alongside this one, each running [classify] again on its own;
     * neither had a production caller, and paying for classification twice to answer one question is waste
     * the user experiences as latency on a permission card.
     */
    data class Decision(val verdict: Verdict, val reason: String?)

    /** [Verdict] plus its explanation, in a single classification pass. */
    fun evaluate(toolName: String, input: JsonObject, policy: Policy): Decision {
        val hit = classify(input, policy) ?: return Decision(Verdict.ALLOW, null)
        return Decision(verdictFor(toolName, hit, policy), reasonFor(hit, policy))
    }

    private fun verdictFor(toolName: String, hit: Hit, policy: Policy): Verdict {
        val enforced = isEnforced(hit, policy)
        if (hit.rule.deniesEveryCaller) {
            // Enforced (default): DENY for every caller, no exception — reaching into someone else's space, or
            // structuring a call so it cannot be analysed. Disabled in Settings: downgraded to ASK for every
            // caller instead — still a card every single time, never a silent allow.
            return if (enforced) Verdict.DENY else Verdict.ASK
        }
        // Everything else: a trusted agent tool always gets a card regardless of the toggle — the toggle only ever
        // changes an UNTRUSTED (MCP/Skill) caller's outcome: DENY when enforced, ASK when not.
        if (!enforced) return Verdict.ASK
        return if (isTrustedCaller(toolName)) Verdict.ASK else Verdict.DENY
    }

    /**
     * Whether [hit]'s rule is currently enforced (vs. downgraded to ASK) per [policy].
     *
     * One set membership test, where this used to be a five-branch `when` with a three-branch `when` nested inside
     * it for the one category that had sub-rules. [SecurityRule] carries the category, so a rule IS its own reason
     * and there is nothing left to look up twice — see [SecurityCategory] for why that shape replaced the other.
     */
    private fun isEnforced(hit: Hit, policy: Policy): Boolean = hit.rule !in policy.disabledRules

    /** The one-line reason a call tripped the guard (for the card / transcript), from [evaluate]'s [Decision].
     *  Always names where to change this — see [SETTINGS_PATH] — whether the rule is enforced or downgraded. */
    private fun reasonFor(hit: Hit, policy: Policy): String =
        if (isEnforced(hit, policy)) {
            "${hit.text} — disable this in $SETTINGS_PATH"
        } else {
            "${hit.text} (downgraded to a prompt: disabled in $SETTINGS_PATH)"
        }

    /**
     * The result of one [classify] pass: which rule tripped, and the human-readable text for [reasonFor].
     *
     * A plain pair, where this used to be a five-case sealed hierarchy. That hierarchy existed for exactly one
     * reason — to make "FOREIGN without a `ForeignReason`" unrepresentable, after the reason had been a nullable
     * field forcing an unreachable `null ->` branch in [isEnforced]. Promoting those three sub-rules to
     * first-class [SecurityRule] constants removes the state it defended against, so the defence went with it.
     */
    private data class Hit(val rule: SecurityRule, val text: String)

    /**
     * Classification + human reason, or null. **Order = severity**: the first hit wins the wording, so the
     * strongest claim is asked first and [SecurityRule.OUTSIDE_PROJECT] — the weakest — is asked last.
     *
     * The **project root is the sanctioned zone**: a file the user brought into their own repo is theirs, under
     * their responsibility, so a credential file *inside the project* is not blocked. Outside it, a credential is
     * caught. FOREIGN territory is exempt inside the project too (you opened it on purpose), and so is the
     * temporary directory when the project itself sits under one. The three rules that judge an ACTION rather than
     * a place — a dangerous command, a shell file write, egress — are location-independent: running `mimikatz` is
     * dangerous whatever the working directory, and a `tee` has no diff to review wherever it lands.
     *
     * Pure detection: runs identically regardless of [Policy.disabledRules] — those only affect [evaluate]'s
     * OUTCOME (see [isEnforced]), never whether a match is found at all.
     *
     * **Takes no tool name, on purpose.** Classification is by the SHAPE of the input — the paths it names, the
     * command it carries — never by what the caller is called. A name is attacker-supplied: an MCP server picks
     * its own tool names, so a rule keyed on one could be walked around by choosing a different name. The tool
     * name governs only *caller trust* ([isTrustedCaller], applied in [verdictFor] after this returns), which is
     * an allowlist and fails closed. This signature used to accept a `toolName` it never read, which suggested
     * the opposite of the actual design.
     */
    private fun classify(input: JsonObject, policy: Policy, depth: Int = 0): Hit? {
        // Every candidate is judged on its literal form AND its resolved real path (symlink/`..` laundering).
        // This phase runs BEFORE the cheap command-text tests below, always, on every call — including one whose
        // command carries no path candidate at all. That ordering is deliberate, not an oversight: FOREIGN must
        // keep winning the wording (see "Order = severity") for a call that trips both a foreign path and a
        // dangerous command, and severity can only be compared once both have actually been computed.
        // The cost is real (a resolver call per candidate, bounded by GuardPaths.expandWithResolved) and paid
        // unconditionally rather than short-circuited by a cheap pre-check, on purpose: a pre-check fast enough to
        // skip this phase would have to be looser than the rule it is guarding, which is exactly the shape of the
        // false positives this file's own history (`isUnc`, `substituteAssignments`) was built by DISCOVERING one.
        val paths = GuardPaths.expandWithResolved(
            ToolInputScanner.pathCandidates(input, policy.home, policy.envValues),
            policy,
        )
        val projRoot = policy.projectRoot?.let { GuardPaths.normalize(it, policy.home) }
        val outsideProject = paths.filter { projRoot == null || !GuardPaths.under(it, projRoot) }

        // The severity ordering, in one place and in one expression, so it can be READ as an ordering. The three
        // groups below are a split for the human and for the complexity budget — a chain of eleven early returns
        // in one function is neither reviewable nor within detekt's limits — and the order across them is exactly
        // the order the rules are declared in.
        return placeRules(paths, outsideProject, policy)
            ?: actionRules(input, policy, depth)
            ?: weakRules(input, outsideProject, projRoot, policy)
    }

    /** The strongest claims, and all three are about a PLACE: someone else's space, the disk itself, a key file. */
    private fun placeRules(paths: List<String>, outsideProject: List<String>, policy: Policy): Hit? {
        ForeignTerritory.foreignHit(paths, policy)?.let {
            return Hit(it.rule, "reaches outside your own space: ${it.path}")
        }

        // Ahead of the credential globs: a request for the block device under the filesystem, or for another
        // process's live memory, is a stronger claim than "this path looks like a key file", and the wording the
        // user reads should be the stronger one. Judged on the full candidate list rather than on the
        // outside-project subset — `/dev/sda` is not somewhere a project can contain.
        SystemDevices.deviceHit(paths)?.let {
            return Hit(SecurityRule.SYSTEM_DEVICE, "addresses a raw system device: $it")
        }

        val matchers = policy.globs.map { CredentialPaths.compile(it, policy.home) }
        return outsideProject.firstOrNull { p -> matchers.any { it.matches(p) } }
            ?.let { Hit(SecurityRule.CREDENTIALS, "reads credentials or key material outside the project: $it") }
    }

    /** What the call DOES: where it talks to, what it runs, and what it would not let the guard see. */
    private fun actionRules(input: JsonObject, policy: Policy, depth: Int): Hit? {
        // A destination reads as strongly as a credential dump and more specifically than "a dangerous command":
        // if a call is both `curl --upload-file` AND aimed at a paste site, the site is the informative half.
        DangerousDomains.blockedHit(ToolInputScanner.urlCandidates(input), policy.extraBlockedDomains)?.let {
            return Hit(SecurityRule.BLOCKED_DOMAIN, "talks to a known staging or exfiltration service: $it")
        }

        CommandRules.dangerousCommand(input, policy.home, policy.envValues)?.let {
            return Hit(SecurityRule.SECRET_DUMPING_COMMANDS, "runs a command that can expose secrets: $it")
        }

        // After the dangerous-command rule, because it is the narrower claim of the two: "this routes around your
        // proxy" is worth saying only when nothing worse is true of the same command.
        ProxyRules.proxyHit(input, policy)?.let {
            return Hit(SecurityRule.PROXY_BYPASS, "routes around the proxy you declared: $it")
        }

        // ── the OPAQUE pair: what the guard could not read, once it has tried ────────────────────────────
        // Below every rule that can say something CONCRETE and above every rule that only says "worth a glance":
        // if a rule above named the actual danger, that wording is the better one.
        //
        // Scripts recurse at EVERY depth (bounded), because that is the whole point: a script that sources a
        // script is exactly how a payload is put one file further from the request.
        scriptFindings(input, policy, depth)?.let { return it }

        // The variable rule, by contrast, is AT DEPTH 0 ONLY, and that is the decision that makes it survivable:
        // inside a file the guard is reading, a `$JAVACMD` or a `$(cd …)` is what a build wrapper is MADE of, so
        // asking "could every variable be resolved" of it would put a card on every build. What is judged inside a
        // script is what a script can DO — credentials, exfiltration, devices, foreign paths, writes — with every
        // variable the launch environment can resolve already resolved.
        if (depth > 0) return null
        return EnvIndirection.indirectionHit(input, policy)?.let {
            val what = if (it.rule == SecurityRule.RECURSION_LIMIT) {
                "hides its destination behind more than $MAX_ANALYSIS_DEPTH levels of variable, or a cycle"
            } else {
                "acts on a destination hidden behind a variable nothing here can resolve"
            }
            Hit(it.rule, "$what: ${it.text}")
        }
    }

    /**
     * The weak-claim tail, in increasing weakness. These say the action is worth a glance, not that it is
     * dangerous, so anything that is also one of the rules above is worded as that — a
     * `curl --upload-file /tmp/dump …` reads as exfiltration, not as a temp file.
     */
    private fun weakRules(
        input: JsonObject,
        outsideProject: List<String>,
        projRoot: String?,
        policy: Policy,
    ): Hit? {
        // Judged on `outsideProject` for the same reason the credential rule is (see [TempDirs]).
        TempDirs.tempHit(outsideProject)?.let {
            return Hit(SecurityRule.TEMP_DIR, "acts on the system temporary directory: $it")
        }

        // Location-INDEPENDENT, and deliberately not exempted by the project root: the point is that the write
        // has no diff to review, which is as true of a file in the project as of one outside it.
        ShellFileWrites.shellFileWrite(input)?.let {
            return Hit(SecurityRule.SHELL_FILE_WRITE, "writes or modifies files through a shell command: $it")
        }

        // LAST of all: with no open project there is nothing to be "outside" of, so this rule only fires when
        // projRoot is known. Checked against LOCATION candidates ONLY (file_path, path, uri, destination…) —
        // never a command's own tokens, which are code (a regex delimiter, a sed substitution, a bare flag),
        // not each one an argument naming where the call acts; testing them the same way turned an ordinary
        // `grep -P '/pattern/'` into an unrelated "outside the project" card. Absolute only — a relative
        // candidate resolves under the working directory — and folded first, so `../../etc/passwd` cannot
        // spell its way past the check by outrunning `GuardPaths.under`'s plain string comparison.
        if (projRoot == null) return null
        return ToolInputScanner.locationCandidates(input, policy.home, policy.envValues)
            .filter { GuardPaths.isAbsolute(it) }
            .map { GuardPaths.fold(it) }
            .firstOrNull { !GuardPaths.under(it, projRoot) }
            ?.let { Hit(SecurityRule.OUTSIDE_PROJECT, "reaches outside the project: $it") }
    }

    /**
     * Reads every script this call runs and judges its CONTENTS with the whole rule set — the analysis that makes
     * [SecurityRule.SCRIPT_EXECUTION] a rule about what a script does rather than about the fact that one ran.
     *
     * Three outcomes, in the order they are decided:
     *  1. **Deeper than [MAX_ANALYSIS_DEPTH]** — a script running a script running a script, or a cycle. Reaching
     *     the bound IS the finding ([SecurityRule.RECURSION_LIMIT], a hard block): nothing legitimate needs six
     *     files to say what it does, and this is also what makes the recursion terminate on the thread that reads
     *     the binary's entire stdout.
     *  2. **Unreadable** — no reader configured, the file does not exist yet, it is too large, the read failed.
     *     The guard genuinely cannot know, so it asks. This is the fail-closed branch and it is the ONLY one that
     *     fires for a script the guard cannot open.
     *  3. **Read** — the text is judged as if it were a command, so every rule applies to it, and a hit is
     *     reported as that rule's own finding with the script named. A script that trips nothing returns null and
     *     the call proceeds unasked, which is what keeps `./gradlew build` free.
     *
     * The nested call is `classify(…, depth + 1)`, so a `source` inside the script is followed by exactly the same
     * code — one implementation for both levels, rather than a second, weaker scanner for file contents.
     */
    private fun scriptFindings(input: JsonObject, policy: Policy, depth: Int): Hit? {
        val scripts = ScriptExecution.scriptsIn(input, policy)
        if (scripts.isEmpty()) return null
        if (depth >= MAX_ANALYSIS_DEPTH) {
            return Hit(SecurityRule.RECURSION_LIMIT, "runs scripts nested deeper than $MAX_ANALYSIS_DEPTH: ${scripts.first()}")
        }
        for (script in scripts) {
            val text = policy.fileReader?.invoke(script)
                ?: return Hit(SecurityRule.SCRIPT_EXECUTION, "runs a script this guard could not read: $script")
            val inner = classifyScript(text, policy, depth + 1) ?: continue
            return Hit(inner.rule, "${inner.text} — inside the script it runs: $script")
        }
        return null
    }

    /**
     * A script's text, judged as the command it is.
     *
     * Wrapped under a `command` key rather than given a scanner of its own, deliberately: that is the shape
     * [ToolInputScanner] already tokenises, de-obfuscates and expands, so the file's contents are judged by
     * exactly the rules its caller was judged by. A second path for file contents would be a second, weaker
     * answer to the same question.
     */
    private fun classifyScript(text: String, policy: Policy, depth: Int): Hit? =
        classify(buildJsonObject { put("command", text) }, policy, depth)
}
