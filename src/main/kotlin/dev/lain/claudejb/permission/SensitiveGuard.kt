package dev.lain.claudejb.permission

import kotlinx.serialization.json.JsonObject

/**
 * A guardrail against an agent — by accident, or by prompt injection — reading what a real attacker would come for,
 * or running what a real attacker would run.
 *
 * Read the whole doc before touching a rule: the value here is that it is thought through, not that it is long. It
 * reacts to three curated surfaces and nothing else, so ordinary development never trips it — a guard that cries
 * wolf is a guard the user switches off, and then it protects nothing.
 *
 * ### Where the rules live
 * This object is the **policy and the verdict**: what the guard knows about the caller, which toggles apply, and
 * the single [classify] pass that produces both the verdict and its wording. Each rule family, and each phase it
 * runs through, is a file of its own in this package — split by seam, not by size:
 *  - [ToolInputScanner] — the input surface: every string leaf as a path candidate, every command-shaped key as a
 *    command. Everything below is matched against what it produces.
 *  - [GuardPaths] — the path phase: one canonical form, containment, and the bounded off-thread real-path resolve.
 *  - [CredentialPaths] — rule 1, credentials and key material ([CredentialPaths.SENSITIVE_GLOBS] + the glob engine).
 *  - [ForeignTerritory] — rule 2, another user's home, network/UNC mounts, foreign WSL drives.
 *  - [CommandRules] — rule 3, dangerous commands ([CommandRules.DANGEROUS_COMMANDS]) and the de-obfuscation applied
 *    before matching them.
 *  - [TempDirs] — rule 4, the system temporary directory.
 *
 * ### 1. Credentials & key material — [CredentialPaths.SENSITIVE_GLOBS]
 * Matched **by shape, wherever the file sits**, never anchored to a specific home — see [CredentialPaths].
 *
 * ### 2. Foreign territory — [Category.FOREIGN]
 * Another user's home, a network/removable mount, a foreign WSL drive: not agentic development, but lateral
 * movement. The only exemption is the open project's own root — see [ForeignTerritory].
 *
 * ### 3. Dangerous commands — [CommandRules.DANGEROUS_COMMANDS]
 * Commands that dump a secret at rest, exfiltrate a file, pipe the network into a shell, or invoke recognised
 * offensive/LOLBIN tooling — see [CommandRules].
 *
 * ### 4. The system temporary directory — [Category.TEMP_DIR]
 * `/tmp` and its equivalents: the one world-writable place outside the project and outside every review
 * surface, so it is where an agent stages what is not meant to be looked at. Unlike the three above it makes
 * no claim that the path is *sensitive* — only that an action there is never silent. Matched by SEGMENT
 * (`/tmpfoo` and `~/tmp` are not it), and exempt inside the open project like the other location rules — see
 * [TempDirs] for both, and for why the plugin's own reading of a background task's output file is not
 * affected by any of it while the agent's read of that same file is.
 *
 * ### Verdict, by trust of the CALLER — an allowlist, not a blacklist
 * The caller is trusted **only if it is one of the agent's own tools** ([AGENT_TOOLS]). Everything else — every MCP
 * server, every Skill, anything unrecognised — is third-party, because a blacklist of "bad" prefixes is exactly the
 * thing an attacker names their way around. By default this is a **hard lock**:
 *  - a **trusted** tool that trips rule 1, 3 or 4 → **ASK** (a card, every time, even under `bypassPermissions`):
 *    the user may authorise their own agent to read their own key, once, explicitly;
 *  - a **third-party** caller that trips rule 1, 3 or 4 → **DENY**;
 *  - **anyone** who trips rule 2 (foreign territory) → **DENY**.
 *
 * ### Per-rule enforcement toggles (Settings ▸ Claude Code ▸ Security) — never a silent allow
 * Each rule — CREDENTIAL, DANGEROUS_COMMAND, TEMP_DIR, and each of FOREIGN's three sub-rules ([ForeignReason]) —
 * has its own
 * `enforce*` field on [Policy], defaulting to `true` (reproducing the original hard lock exactly). Detection
 * ([classify]) runs **unconditionally**, regardless of these toggles — turning one off never skips recognising a
 * match. What it changes is [verdict]'s OUTCOME: a disabled rule's hit is **downgraded from DENY to ASK**, for every
 * caller, including third-party ones — never to ALLOW. So "disabling a rule" means "I want to decide this one
 * myself, every time", not "stop watching for this". The one thing tunable *without* a toggle is the sensitive-path
 * list itself, and only **additively**: the effective globs are the built-in [CredentialPaths.SENSITIVE_GLOBS] plus
 * the user's extras — the built-ins cannot be individually removed from that list.
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
 * URLs and multi-line blobs so a `Write`'s *contents* are not mistaken for a filename. The one thing it goes by
 * key for is the reverse question — whether a leaf is a *location* at all: a payload key
 * (`ToolInputScanner.CONTENT_KEY`: `old_string`, `content`…) carries the text a call writes, not a place it
 * touches, and a command key carries a text the paths genuinely live inside. Both are named there, with why.
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

    /** Which surface a call tripped — decides severity ([verdict]) and the card's wording ([reason]). */
    enum class Category { CREDENTIAL, FOREIGN, DANGEROUS_COMMAND, TEMP_DIR }

    /** Which FOREIGN sub-rule tripped — lets [Policy]'s per-rule toggles govern FOREIGN at finer grain than the
     *  category as a whole (see the three `enforceForeign*` fields below). */
    enum class ForeignReason { OTHER_USER_HOME, NETWORK_MOUNT, WSL_MOUNT }

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
        /** WSL only: treat every `/mnt/<x>` where x ≠ c as foreign. */
        val blockForeignWslMounts: Boolean = false,
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
         * **Caller contract — this WILL be called from whatever thread invokes [verdict]/[reason]/[classify].**
         * A typical implementation (`File(x).canonicalPath`) is a blocking syscall with **no JDK-level timeout and
         * no interrupt**: on a hung/unresponsive network mount it can block the calling thread forever. [SensitiveGuard]
         * defends against that itself (bounded, off-thread, per [GuardPaths.expandWithResolved]) — but do not add
         * further blocking work inside this lambda beyond a single stat-like call, since the bound assumes that shape.
         */
        val pathResolver: ((String) -> String?)? = null,
        /**
         * Enforcement toggles — Settings ▸ Claude Code ▸ Security, one per rule. Defaults (`true`) reproduce the
         * original hard-lock behaviour exactly. Turning one **off never silently ALLOWs** a call that trips it —
         * detection ([classify]) always runs regardless; the toggle only downgrades the OUTCOME from the hard
         * block (DENY) to a permission card (ASK), for every caller, so disabling a rule is never quiet. A trusted
         * agent tool that trips CREDENTIAL/DANGEROUS_COMMAND/TEMP_DIR already gets a card either way — those
         * toggles only ever change what an untrusted (MCP/Skill) caller gets: DENY when enforced, ASK when not.
         */
        val enforceCredentials: Boolean = true,
        val enforceDangerousCommands: Boolean = true,
        /**
         * Rule 4: an action on the system temporary directory ([TempDirs]). Off, every hit is a card instead
         * of a block — the answer for a workflow that genuinely lives in the temp directory *outside* the open
         * project (an out-of-tree build, a shared scratch area), which the project-root exemption cannot cover.
         */
        val enforceTempDirs: Boolean = true,
        /** Sub-rule of FOREIGN: another user's home directory, or `/root` when we aren't root. */
        val enforceForeignOtherUserHome: Boolean = true,
        /** Sub-rule of FOREIGN: a UNC path or a discovered network/removable mount ([guardedRoots]). */
        val enforceForeignNetworkMounts: Boolean = true,
        /** Sub-rule of FOREIGN: a non-`/mnt/c` WSL drive (only meaningful when [blockForeignWslMounts] is true). */
        val enforceForeignWslMounts: Boolean = true,
    )

    // ── origin: trusted only if it is one of the agent's own tools ───────────────────────────────────────

    /** True only for the agent's OWN tools. Everything else — MCP, Skills, unknown — is third-party. */
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
        val result = classify(input, policy) ?: return Decision(Verdict.ALLOW, null)
        return Decision(verdictFor(toolName, result, policy), reasonFor(result, policy))
    }

    private fun verdictFor(toolName: String, result: Classification, policy: Policy): Verdict {
        val enforced = isEnforced(result, policy)
        if (result.category == Category.FOREIGN) {
            // Enforced (default): DENY for every caller, no exception. Disabled in Settings: downgraded to ASK for
            // every caller instead — still a card every single time, never a silent allow.
            return if (enforced) Verdict.DENY else Verdict.ASK
        }
        // Credentials / dangerous commands: a trusted agent tool always gets a card regardless of this toggle —
        // the toggle only ever changes an UNTRUSTED (MCP/Skill) caller's outcome: DENY when enforced, ASK when not.
        if (!enforced) return Verdict.ASK
        return if (isTrustedCaller(toolName)) Verdict.ASK else Verdict.DENY
    }

    /** Whether [result]'s specific rule is currently enforced (vs. downgraded to ASK) per [policy]'s toggles. */
    private fun isEnforced(result: Classification, policy: Policy): Boolean = when (result.category) {
        Category.CREDENTIAL -> policy.enforceCredentials

        Category.DANGEROUS_COMMAND -> policy.enforceDangerousCommands

        Category.TEMP_DIR -> policy.enforceTempDirs

        Category.FOREIGN -> when (result.foreignReason) {
            ForeignReason.OTHER_USER_HOME -> policy.enforceForeignOtherUserHome
            ForeignReason.NETWORK_MOUNT -> policy.enforceForeignNetworkMounts
            ForeignReason.WSL_MOUNT -> policy.enforceForeignWslMounts
            null -> true // unreachable in practice — classify() always tags a FOREIGN hit with its sub-rule
        }
    }

    /** The one-line reason a call tripped the guard (for the card / transcript), from [evaluate]'s [Decision].
     *  Always names where to change this — see [SETTINGS_PATH] — whether the rule is enforced or downgraded. */
    private fun reasonFor(result: Classification, policy: Policy): String =
        if (isEnforced(result, policy)) {
            "${result.text} — disable this in $SETTINGS_PATH"
        } else {
            "${result.text} (downgraded to a prompt: disabled in $SETTINGS_PATH)"
        }

    /** [Category] + [ForeignReason] (FOREIGN only) + human-readable text. */
    private data class Classification(val category: Category, val foreignReason: ForeignReason? = null, val text: String)

    /**
     * Classification + human reason, or null. Order = severity: FOREIGN wins the wording, TEMP_DIR loses it.
     *
     * The **project root is the sanctioned zone**: a file the user brought into their own repo is theirs, under
     * their responsibility, so a credential file *inside the project* is not blocked. Outside it, a credential is
     * caught. FOREIGN territory is exempt inside the project too (you opened it on purpose), and so is the
     * temporary directory when the project itself sits under one. A dangerous **command**
     * is location-independent — running `mimikatz` is dangerous whatever the working directory — so it is judged
     * regardless of the project boundary.
     *
     * Pure detection: runs identically regardless of [Policy]'s enforcement toggles — those only affect [verdict]'s
     * OUTCOME (see [isEnforced]), never whether a match is found at all.
     *
     * **Takes no tool name, on purpose.** Classification is by the SHAPE of the input — the paths it names, the
     * command it carries — never by what the caller is called. A name is attacker-supplied: an MCP server picks
     * its own tool names, so a rule keyed on one could be walked around by choosing a different name. The tool
     * name governs only *caller trust* ([isTrustedCaller], applied in [verdict] after this returns), which is
     * an allowlist and fails closed. This signature used to accept a `toolName` it never read, which suggested
     * the opposite of the actual design.
     */
    private fun classify(input: JsonObject, policy: Policy): Classification? {
        // Every candidate is judged on its literal form AND its resolved real path (symlink/`..` laundering).
        val paths = GuardPaths.expandWithResolved(ToolInputScanner.pathCandidates(input, policy.home), policy)

        ForeignTerritory.foreignHit(paths, policy)?.let {
            return Classification(Category.FOREIGN, it.reason, "reaches outside your own space: ${it.path}")
        }

        val projRoot = policy.projectRoot?.let { GuardPaths.normalize(it, policy.home) }
        val outsideProject = paths.filter { projRoot == null || !GuardPaths.under(it, projRoot) }
        val matchers = policy.globs.map { CredentialPaths.compile(it, policy.home) }
        outsideProject.firstOrNull { p -> matchers.any { it.matches(p) } }
            ?.let { return Classification(Category.CREDENTIAL, text = "reads credentials or key material outside the project: $it") }

        CommandRules.dangerousCommand(input)?.let {
            return Classification(Category.DANGEROUS_COMMAND, text = "runs a command that can expose secrets: $it")
        }

        // LAST, because it is the weakest claim of the four: the other three say the path or the command is
        // dangerous, this one only says the action is worth a glance. Anything that is also one of the above
        // should be worded as that, so a `curl --upload-file /tmp/dump …` reads as exfiltration, not as a
        // temp file. Judged on `outsideProject` for the same reason rules 1 and 2 are (see [TempDirs]).
        TempDirs.tempHit(outsideProject)?.let {
            return Classification(Category.TEMP_DIR, text = "acts on the system temporary directory: $it")
        }

        return null
    }
}
