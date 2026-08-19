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
 *  - [VersionControlRules] — a version-control command that switches a safeguard OFF (`git add -f`, `--no-verify`).
 *  - [DestructiveCommands] — the second axis: an irreversible action on the user's own systems.
 *  - [CodeExecution] — package install hooks, persistence mechanisms, library injection.
 *  - [TempDirs] — the system temporary directory.
 *  - [SystemDevices] — any device node, the Windows device namespace, another process's memory.
 *  - [ShellFileWrites] — a command that writes or modifies a file, i.e. a write with no diff to review.
 *  - [ProxyRules] — egress that routes around the proxy the user declared.
 *  - [DangerousDomains] — the curated staging/exfiltration destinations.
 *  - [EnvIndirection] — a destination hidden behind a variable the guard cannot resolve.
 *  - [ScriptExecution] — a script file being sourced or launched, whose contents never reach this guard.
 *  - [DevToolScripts] / [DevToolChecksums] — the build wrappers whose BODY is not worth reading, and the
 *    vendor-published sums that say a file of that name really is the tool it claims to be.
 *
 * ### The surfaces, by [SecurityCategory]
 *
 * **[SecurityCategory.SENSITIVE_DATA]** — [SecurityRule.CREDENTIALS] matches by shape, **wherever the file sits, the
 * open project included**, and never anchored to a specific home (see [CredentialPaths]). That "wherever" is
 * load-bearing and it is the one place the project root buys nothing: a `.env` or a service-account key inside a
 * repository is the ordinary case rather than the exotic one, and the repository is precisely what the agent is
 * allowed to write to, so "the user put it there themselves" is not something this code is in a position to assume.
 * [SecurityRule.SECRET_DUMPING_COMMANDS] catches commands that dump a secret at rest, exfiltrate a file, pipe the
 * network into a shell, or invoke recognised offensive/LOLBIN tooling (see [CommandRules]);
 * [SecurityRule.VCS_PROTECTION_BYPASS] catches a command asking for the safeguards that keep a secret OUT of a
 * repository to be skipped (see [VersionControlRules]).
 *
 * **[SecurityCategory.FOREIGN_TERRITORY]** — another user's home, a network/removable mount, a foreign WSL drive:
 * not agentic development, but lateral movement. Exempt inside the open project, because a hit here is entirely its
 * LOCATION and that location is the workspace the user opened — see [ForeignTerritory], and [classify] for why a
 * place may be exempted and a threat may not.
 *
 * **[SecurityCategory.DESTRUCTIVE_OPERATION]** — the second axis, and the only one that is not about an attacker:
 * an irreversible action on the user's own infrastructure, cluster, cloud, database, containers, git history or
 * filesystem. One narrow rule per vector so switching one off never opens the others — see [DestructiveCommands].
 *
 * **[SecurityCategory.CODE_EXECUTION]** — an install hook, a persistence mechanism, a preloaded library: code that
 * runs because of this call but was not written for it, now or after the session ends — see [CodeExecution].
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
 * ### The verdict IS the toggle, and nothing else — no caller is trusted
 * **An enforced rule DENIES. A disabled rule ASKS. There is no third case, and who is calling does not enter into
 * it.** The switch in Settings ▸ Claude Code ▸ Security is therefore the whole of the policy, and it says one thing
 * in two directions:
 *  - **rule enforced (the default) → DENY**, for every caller — the agent's own tools exactly like an MCP server or
 *    a Skill, under `bypassPermissions` exactly like under `default`. There is no "authorise it just this once" in
 *    the chat: the only key is the toggle, and it lives in Settings, where a decision of that weight is taken
 *    deliberately rather than under the pressure of a stalled turn.
 *  - **rule disabled → ASK**, for every caller, every time — never ALLOW. Detection ([classify]) runs
 *    **unconditionally** whatever that set says, so switching a rule off does not stop the guard watching; it means
 *    "I want to decide this one myself, every time". The card it produces is drawn as a red guard alert naming the
 *    rule ([Decision.rule]), so an open lock is never quiet.
 *
 * **This replaced a caller-trust matrix, and the deletion is the point.** The guard used to hold an allowlist of the
 * agent's own tool names, ask THEM for confirmation, and hard-deny everyone else. Two things were wrong with it.
 * It made the meaning of a rule depend on which tool happened to carry the call, so one credential read was a card
 * and an identical one a wall, decided by a name that arrives **on the wire** — the one input a guard against prompt
 * injection should not be taking policy from. And the confirmation itself was the hole: an ASK on an enforced rule is
 * an Accept button on a call the guard has just called dangerous, put in front of a user in the middle of their work,
 * which is precisely the condition under which people click it. What is left is a lock with one documented key.
 *
 * ### The one control that LIFTS a block — and the fence around it
 * [Policy.commandWhitelist] pre-approves an **exact command** the user typed into Settings, in the cold. It is
 * fenced on four sides, and each fence is load-bearing: it is authored only in Settings and never from the wire or
 * from a card (pre-authorising something dangerous must not be a button offered mid-task); it is matched as the
 * WHOLE command, de-obfuscated on both sides, so `terraform destroy` does not authorise `terraform destroy && rm
 * -rf /`; it lifts only a [SecurityRule.whitelistable] rule, which is an action rule and never a wall; and that last
 * guarantee is **structural rather than promised**, because [classify] asks the walls first, so a command that trips
 * one is reported AS the wall and a wall is not whitelistable. There is no way to allow-list `cat ~/.ssh/id_rsa`.
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
 * once made. Close it by widening the patterns — there is no caller left to trust instead, and no mode, and no
 * button (see the verdict section above: the tool's name decides nothing).
 *
 * PURE: no IDE, no filesystem, no OS sniffing. [Policy] carries everything (assembled on the IDE side from settings
 * + [dev.lain.claudejb.session.RemoteMounts]), so every rule is unit-testable — for security code, a requirement.
 */
object SensitiveGuard {

    /** What to do with a tool call that trips the guard. */
    enum class Verdict { ALLOW, ASK, DENY }

    // There was an `AGENT_TOOLS` allowlist of the agent's own tool names here, and an `isTrustedCaller` reading it.
    // Both are gone with the caller-trust matrix (see the class doc): a rule's verdict no longer depends on which
    // tool carries the call, so the list decided nothing, and a curated trust allowlist that grants no trust is
    // worse than none — it reads, to the next person, as if some caller were still privileged. It also had a
    // failure mode of its own that is worth remembering as an argument AGAINST reintroducing it: the list went
    // stale as the CLI grew tools, and every first-party name missing from it was hard-denied, indistinguishable
    // from an MCP server being blocked. A policy input that has to be maintained against someone else's release
    // cadence is a policy input that fails silently. It must not come back as dormant code either —
    // `ReachabilityContractTest` would fail the build over it, which is that gate working.

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
        /**
         * Exact commands the user pre-approved in Settings — the ONE control that lifts a block rather than
         * widening one. Empty by default.
         *
         * **Authored in the cold, never from the wire.** It is written on the Settings page and nowhere else: not
         * from a permission card, not by the model, not by an MCP server. That is the same discipline
         * [globs] and [extraBlockedDomains] follow, inverted — those may only widen the net from Settings, this
         * may only narrow it from Settings — and it is why there is no one-click "always allow this" on a guard
         * alert. Pre-authorising a dangerous command under the pressure of a stalled turn is exactly the decision
         * this design refuses to offer.
         *
         * See [liftedByWhitelist] for how an entry is matched, and [SecurityRule.whitelistable] for what it can
         * never lift.
         */
        val commandWhitelist: List<String> = emptyList(),
    )

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
    data class Decision(
        val verdict: Verdict,
        val reason: String?,
        /**
         * Which rule tripped — null only on [Verdict.ALLOW], where nothing did.
         *
         * Carried as the enum rather than left implicit in [reason], because the permission card names the rule
         * and the only other way to know it there would be to parse the prose back out. That parse would be a
         * second, weaker copy of a classification already made: the wording is written for a human and is free to
         * change, so a card keyed on it would start saying "unknown rule" the day somebody improves a sentence.
         */
        val rule: SecurityRule? = null,
    )

    /**
     * [Verdict] plus its explanation, in a single classification pass.
     *
     * **Takes no tool name, and that is the policy rather than a simplification.** It used to, in order to look the
     * caller up in a trust allowlist; the verdict no longer asks who is calling (see the class doc), so a parameter
     * for it would be one this function never reads — which suggests the opposite of the actual design to everyone
     * who reads the signature afterwards.
     */
    fun evaluate(input: JsonObject, policy: Policy): Decision {
        val hit = classify(input, policy) ?: return Decision(Verdict.ALLOW, null)
        if (liftedByWhitelist(input, hit, policy)) return Decision(Verdict.ALLOW, null)
        return Decision(verdictFor(hit, policy), reasonFor(hit, policy), hit.rule)
    }

    /**
     * **Enforced is a wall; disabled is a question.** The whole verdict, and there is deliberately nothing else in
     * it — no caller, no permission mode, no per-call override.
     *
     * This was a four-branch decision over caller trust and a `deniesEveryCaller` flag, and collapsing it is the
     * security change rather than a tidy-up: an ASK on an ENFORCED rule was an Accept button on a call the guard
     * had just classified as dangerous, so the strongest statement the plugin can make ended in a dialog the user
     * clicks through while thinking about something else. The lever moved to the one place where it is a decision
     * instead of a reflex — the toggle in Settings — and the outcome here follows it exactly.
     */
    private fun verdictFor(hit: Hit, policy: Policy): Verdict =
        if (isEnforced(hit, policy)) Verdict.DENY else Verdict.ASK

    /**
     * Does the user's always-allow list cover this exact call?
     *
     * Four conditions, and every one of them is a fence rather than a convenience:
     *  1. **The rule must be [SecurityRule.whitelistable]** — an action rule. A wall never is, and because
     *     [classify] asks the walls first, a command that trips one is reported AS the wall and never reaches here.
     *  2. **Both sides go through [CommandRules.deobfuscate] and the same variable expansion**, so
     *     `t""erraform destroy` cannot dodge an entry written normally — and an entry written obfuscated cannot
     *     quietly cover more than it appears to.
     *  3. **Whole-command equality**, after collapsing whitespace. Not `startsWith`, not `contains`:
     *     `terraform destroy` must not authorise `terraform destroy && rm -rf /`, which is a different string.
     *  4. **Every command the call carries must be approved** ([all], not [any]). A `Bash` input naming two
     *     commands, one of them whitelisted, is not a whitelisted call — and a call carrying no command at all
     *     (a path-shaped hit) is never lifted, since there is nothing to have approved.
     */
    private fun liftedByWhitelist(input: JsonObject, hit: Hit, policy: Policy): Boolean {
        if (!hit.rule.whitelistable || policy.commandWhitelist.isEmpty()) return false
        val approved = policy.commandWhitelist.map { canonicalCommand(it, policy) }.filter { it.isNotEmpty() }.toSet()
        if (approved.isEmpty()) return false
        val issued = ToolInputScanner.commandCandidates(input)
        if (issued.isEmpty()) return false
        return issued.all { canonicalCommand(it, policy) in approved }
    }

    /** One spelling for both sides of a whitelist comparison: de-obfuscated, variable-expanded, whitespace
     *  collapsed, trimmed. Never lower-cased — a shell is case-sensitive and so is this. */
    private fun canonicalCommand(command: String, policy: Policy): String =
        CommandRules.deobfuscate(command, policy.home, policy.envValues)
            .replace(Regex("""\s+"""), " ")
            .trim()

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
        // **The open project is the sanctioned zone for every LOCATION rule, credentials included.** Folded before
        // the containment test, and that is the security half of this line: "inside the project" means inside the
        // SUBTREE, not "spelled with the project root at the front". `GuardPaths.normalize` does not collapse
        // `..`, so `<root>/../../tmp/payload` passes a plain prefix test and would collect the exemption of a
        // place it is not in.
        val projRoot = policy.projectRoot?.let { GuardPaths.fold(GuardPaths.normalize(it, policy.home)) }
        val outsideProject = paths.filter { projRoot == null || !GuardPaths.under(GuardPaths.fold(it), projRoot) }

        // The severity ordering, in one place and in one expression, so it can be READ as an ordering. The three
        // groups below are a split for the human and for the complexity budget — a chain of sixteen early returns
        // in one function is neither reviewable nor within detekt's limits — and the order across them is exactly
        // the order the rules are declared in.
        return placeRules(paths, outsideProject, policy)
            ?: actionRules(input, policy, depth)
            ?: weakRules(input, outsideProject, policy, depth)
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

        // Judged on the OUTSIDE-PROJECT subset: a credential file the user brought into their own repository is
        // theirs, and the agent is working in that repository at their request.
        //
        // This exemption was removed once and put back, so the reasoning is worth keeping written down rather
        // than rediscovered. Removing it is defensible on paper — a `.env` in a repo is the ordinary case, and
        // the repository is space the agent can WRITE to, so "the user put it there" is an assumption. What it
        // costs in practice is the whole tool: the strongest rule in the set then fires on the one directory
        // every single call is aimed at, so ordinary work in any project that holds a `.env`, a `*.pem` or a
        // service-account key becomes a wall. That is not a stricter guard, it is an uninstalled one — and an
        // uninstalled guard protects nothing at all, which is the only measure that counts here.
        //
        // The line the package holds instead: **exempt a PLACE, never a THREAT.** The project root is a place the
        // user opened deliberately. Nothing here exempts a KIND of file, and the same credential one directory
        // outside the project is caught (see the test of exactly that name).
        val matchers = policy.globs.map { CredentialPaths.compile(it, policy.home) }
        return outsideProject.firstOrNull { p -> matchers.any { it.matches(p) } }
            ?.let { Hit(SecurityRule.CREDENTIALS, "reads credentials or key material outside the project: $it") }
    }

    /** What the call DOES: where it talks to, what it runs, and what it would not let the guard see. */
    private fun actionRules(input: JsonObject, policy: Policy, depth: Int): Hit? {
        commandFamilies(input, policy)?.let { return it }

        // ── the OPAQUE pair: what the guard could not read, once it has tried ────────────────────────────
        // Below every rule that can say something CONCRETE and above every rule that only says "worth a glance":
        // if a rule above named the actual danger, that wording is the better one.
        //
        // Scripts recurse at EVERY depth (bounded), because that is the whole point: a script that sources a
        // script is exactly how a payload is put one file further from the request.
        scriptFindings(input, policy, depth)?.let { return it }

        // Command substitution — `$(cat /etc/shadow)`, `` `mimikatz` `` — is judged by the SAME recursion: the
        // inner command is classified, so it is reported as the credential read or the hacking tool it actually
        // is, not as a generic "unresolvable". Only a substitution whose inner command trips nothing falls
        // through to the opaque rule below, which still refuses it for hiding the OUTER destination.
        substitutionFindings(input, policy, depth)?.let { return it }

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
     * The families recognised by the SHAPE OF A COMMAND rather than by a path — asked in severity order, first
     * hit wins the wording.
     *
     * Split out of [actionRules] rather than inlined there, and the split is not only detekt's return-count
     * budget: this is the list that grows. Every new command family is one more entry here and one more file
     * beside this one, which is the package's own rule — a rule is a file, never a branch in the verdict — and
     * keeping them together is what lets the ordering be READ as an ordering instead of reconstructed from a
     * chain of early returns interleaved with the opaque rules and the recursion bound.
     *
     * The order, and why each step is where it is:
     *  1. a **blocked destination** — reads as strongly as a credential dump and more specifically than "a
     *     dangerous command": a call that is both `curl --upload-file` AND aimed at a paste site is best
     *     described by the site;
     *  2. a **secret-dumping command** — the actual secret leaving;
     *  3. a **version-control safeguard being skipped** — a door left open, which is weaker than one already
     *     walked through;
     *  4. a **destructive operation** — not confidentiality at all, but "this is about to delete your production
     *     database" outranks every remaining claim about a command's shape;
     *  5. **code execution / persistence** — someone else's code, now or after the session;
     *  6. a **proxy bypass** — the narrowest of the set, worth saying only when nothing worse is true.
     */
    private fun commandFamilies(input: JsonObject, policy: Policy): Hit? {
        // Each family is a probe returning its own [Hit] (rule + wording) or null, asked in severity order — the
        // FIRST hit wins. A list rather than a chain of early returns, and the list IS the ordering: adding a
        // command family is one more entry, never a new branch, and the order is readable as an order (see the
        // per-step reasoning in this function's KDoc). The blocked-destination probe is first because a call that
        // is both an exfiltration AND something else is best described by where it is sending the data.
        val families: List<() -> Hit?> = listOf(
            {
                DangerousDomains.blockedHit(ToolInputScanner.urlCandidates(input), policy.extraBlockedDomains)
                    ?.let { Hit(SecurityRule.BLOCKED_DOMAIN, "talks to a known staging or exfiltration service: $it") }
            },
            {
                CommandRules.dangerousCommand(input, policy.home, policy.envValues)
                    ?.let { Hit(SecurityRule.SECRET_DUMPING_COMMANDS, "runs a command that can expose secrets: $it") }
            },
            // The intrusion-technique family (named tool / reverse shell / GTFOBins escape). Above the destructive
            // axis because "an attacker is operating on this box" outranks "a command with no undo", and below the
            // secret rules because a technique that ALSO exfiltrates is best named as the exfiltration.
            {
                IntrusionTechniques.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "runs a recognised intrusion technique: ${it.text}") }
            },
            {
                VersionControlRules.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "switches off a version-control safeguard: ${it.text}") }
            },
            {
                DestructiveCommands.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "runs an irreversible destructive operation: ${it.text}") }
            },
            {
                CodeExecution.hit(input, policy.home, policy.envValues)
                    ?.let { Hit(it.rule, "makes this machine run code from elsewhere: ${it.text}") }
            },
            {
                ProxyRules.proxyHit(input, policy)
                    ?.let { Hit(SecurityRule.PROXY_BYPASS, "routes around the proxy you declared: $it") }
            },
        )
        return families.firstNotNullOfOrNull { it() }
    }

    /**
     * The weak-claim tail, in increasing weakness. These say the action is worth a glance, not that it is
     * dangerous, so anything that is also one of the rules above is worded as that — a
     * `curl --upload-file /tmp/dump …` reads as exfiltration, not as a temp file.
     */
    private fun weakRules(
        input: JsonObject,
        outsideProject: List<String>,
        policy: Policy,
        depth: Int,
    ): Hit? {
        // Exempt inside the project for the same reason the credential rule is: the finding here is ENTIRELY the
        // location, and the location is the workspace the user opened. A project that sits under /tmp would
        // otherwise deny its own every `Read` and `Edit` — that is not blocking a threat, it is blocking the work
        // — while a /tmp path OUTSIDE the project still trips, which is the case the rule exists for.
        TempDirs.tempHit(outsideProject)?.let {
            return Hit(SecurityRule.TEMP_DIR, "acts on the system temporary directory: $it")
        }

        val projRoot = policy.projectRoot?.let { GuardPaths.fold(GuardPaths.normalize(it, policy.home)) }

        // **A shell write is a card ONLY when it touches somewhere outside the open project** — not globally.
        // Blocking every `mkdir build`, `rm -rf node_modules`, `cp` and `sed -i` inside the user's OWN project was
        // the noisiest rule in the set and the wrong criterion: what matters is not that a write has no diff, it
        // is WHERE it lands. An in-project write is ordinary development; a write to `/etc`, another user's home,
        // a device or anywhere off the workspace is the one worth a card. Sensitive locations already have their
        // own stronger rules (credentials, foreign, device, temp) that win the wording; this catches the plain
        // outside-project write that no other rule sees, since `OUTSIDE_PROJECT` below reads location keys only.
        //
        // AT DEPTH 0 ONLY, the same decision that keeps script analysis usable: a script is MADE of file
        // operations, so asking "does this write" of a file the agent did not author in this request would card
        // every build wrapper and setup script. WHERE a script writes is still judged — the location rules run at
        // every depth. With no project context (`projRoot == null`) the write is a card, which is fail-closed.
        //
        // "Outside" means an ABSOLUTE candidate that resolves outside the root — NOT any candidate the containment
        // string-match calls outside. A relative token (`tee`, `cp`, `a.txt`, `build/`) is in-project by
        // construction, because the working directory the binary launches in IS the project root; treating those
        // as outside is what would put the whole rule back to firing globally, which is the bug this replaces.
        val writesOutside = projRoot == null || outsideProject.any { GuardPaths.isAbsolute(it) }
        if (depth == 0 && writesOutside) {
            ShellFileWrites.shellFileWrite(input)?.let {
                return Hit(SecurityRule.SHELL_FILE_WRITE, "writes or modifies files outside the project: $it")
            }
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
            // A known build wrapper or tool entrypoint is NOT READ, so it produces no hit at all. This is the
            // narrowest fix for a real and total failure: a build wrapper is MADE of the things the rules look for
            // — `command -v java >/dev/null 2>&1`, `JAVACMD=$JAVA_HOME/bin/java`, half its body quoted — so
            // reading `./gradlew` yields an unreviewed write and a script that cannot be read, and the user
            // experiences the plugin refusing to build their own project with no lever, since SCRIPT_EXECUTION is
            // not whitelistable. The file stops being an input to the analysis; the analysis itself is untouched.
            // What this does NOT do is exempt the COMMAND: `terraform destroy` is still DESTRUCTIVE_IAC, because
            // that rule reads the command line and never the tool's own file. See [DevToolScripts].
            if (isExemptDevTool(script)) continue
            val text = policy.fileReader?.invoke(script)
                ?: return Hit(SecurityRule.SCRIPT_EXECUTION, "runs a script this guard could not read: $script")
            val inner = classifyScript(text, policy, depth + 1) ?: continue
            return Hit(inner.rule, "${inner.text} — inside the script it runs: $script")
        }
        return null
    }

    /**
     * Is [script] a development tool whose BODY the guard should not read? By NAME (or directory) alone — see
     * [DevToolScripts] for why reading a build wrapper's body produces three findings that are all the analysis
     * meeting a file it was not designed for, and none of them true.
     *
     * **There used to be a checksum gate here** ([DevToolChecksums]) that, for an artifact whose published hash
     * the plugin shipped, required the file on disk to match it. It is gone, and the reason is that it could
     * BLOCK the very thing this exemption exists to protect: the shipped baseline pinned specific
     * `gradle-wrapper.jar` hashes, so a developer on any other Gradle version had their `./gradlew` fail the
     * match, lose the exemption, get its body read, and hit false findings on their own build. A plugin whose job
     * is to stay out of ordinary development cannot ship a rule that blocks the most ordinary command there is.
     * Tamper-resistance of the exemption comes from the fact that CREATING a file with one of these names is
     * itself a wall (a shell write, or a reviewable diff), which is the bound [DevToolScripts] already documents.
     */
    private fun isExemptDevTool(script: String): Boolean = DevToolScripts.isKnownDevTool(script)

    /**
     * A command substitution — `$(inner)` or `` `inner` `` — as its own inner command.
     *
     * `cat $(cat /etc/shadow)` and `` `mimikatz` `` name a destination the guard cannot resolve, but the INNER
     * command is right there in the text, so it is classified by the same recursion the scripts use: the finding
     * is the credential read, the hacking tool, the destructive verb it actually is, at that rule's own severity
     * — not a generic "unresolvable". A substitution whose inner command trips nothing returns null and falls
     * through to [EnvIndirection], which still refuses it for hiding the OUTER destination.
     *
     * `[^()]` inside the `$(…)` capture means a NESTED substitution is not matched by this one pattern — that is
     * left to the recursion bound: `$($($(…)))` reaches [MAX_ANALYSIS_DEPTH] and is [SecurityRule.RECURSION_LIMIT].
     */
    private val COMMAND_SUBSTITUTION = Regex("""\$\(([^()]*)\)|`([^`]*)`""")

    private fun substitutionFindings(input: JsonObject, policy: Policy, depth: Int): Hit? {
        if (depth >= MAX_ANALYSIS_DEPTH) return null
        for (command in ToolInputScanner.commandCandidates(input)) {
            for (match in COMMAND_SUBSTITUTION.findAll(command)) {
                val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
                if (inner.isEmpty()) continue
                val hit = classifyScript(inner, policy, depth + 1) ?: continue
                return Hit(hit.rule, "${hit.text} — inside a command substitution: $inner")
            }
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
