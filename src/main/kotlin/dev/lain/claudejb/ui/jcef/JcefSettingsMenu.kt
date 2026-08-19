package dev.lain.claudejb.ui.jcef

import dev.lain.claudejb.permission.SecurityCategory
import dev.lain.claudejb.permission.SecurityRule
import dev.lain.claudejb.protocol.ModelInfo
import dev.lain.claudejb.session.ClaudeSession
import dev.lain.claudejb.session.EffortLevel
import dev.lain.claudejb.session.PermissionMode
import dev.lain.claudejb.session.ToolNaming
import dev.lain.claudejb.settings.ClaudeSettings
import dev.lain.claudejb.settings.SecuritySuspensions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * The composer's ⚙ menu: the settings worth changing without leaving the chat.
 *
 * **Deliberately NOT the whole Settings page.** That page has forty-odd fields, and three of them are a JSON
 * document, a table of environment variables and a free-text path — a popup that reproduced those would be the
 * page again, with two interfaces writing one configuration and the drift that follows. What is here is the
 * set you change WHILE working; everything else is one row away, behind *Open Plugin Settings*.
 *
 * **Three groups are the composer's own pills, and they have to agree with them.** Model, effort and
 * permission mode are drawn from the LIVE SESSION rather than from the stored default, and choosing one goes
 * through the same `ClaudeSession.change*` call the pill makes ([applyToSession]) before the choice is
 * persisted as the default the next chat launches with. Drawing the stored value instead would let two
 * controls answer the same question differently, which is worse than not offering the row at all.
 *
 * **Five settings are launch flags and say so.** Setting sources, allowed tools, disallowed tools and the two
 * MCP switches only reach the binary through `ClaudeSettings.applyTo`, i.e. at the next start — so they are
 * marked `deferred` and the page prints that over the group heading. Nothing here restarts a session to make
 * one of them take effect.
 *
 * **The keys are a closed set and the page never invents one.** A row that is not a single boolean field
 * carries a composite key — `mode:acceptEdits`, `rule:TEMP_DIR`, `always:Bash` — which the page returns verbatim
 * and never interprets. [apply] re-checks the suffix against the catalogue the row was built from (the session's
 * model list, [EffortLevel], [PermissionMode], [SecurityRule], [ClaudeSession.SETTING_SOURCES],
 * [ToolNaming.BUILTIN_TOOLS]) before writing anything, so "closed set" stays true because the value is VERIFIED,
 * not because the string came from somewhere trusted. A settings write is the last place to accept an arbitrary
 * string from a browser, and eleven of these rows are the deterministic guard's own switches.
 *
 * **One group has a third level.** A row may carry a `sub`, and the security rules are the only rows that do:
 * eleven switches in one flat list is a wall, so they are drawn under their own [SecurityCategory]. A row without
 * a `sub` behaves exactly as it did before that existed.
 */
internal object JcefSettingsMenu {

    /**
     * What the live session currently has selected — the three answers that must not come from storage.
     *
     * A plain value rather than the session itself so the menu can be built, and its keys checked against
     * [apply], without an IDE `Project`: the only thing that needs a session is reading these four fields off
     * one.
     */
    internal data class Selected(
        val models: List<ModelInfo>,
        val model: String,
        val effort: String?,
        val mode: String,
    )

    /** The menu for [session]'s chat. */
    fun json(state: ClaudeSettings.State, session: ClaudeSession): JsonArray = json(state, selectedIn(session))

    /** The menu, in the order it is drawn. Groups are a UI concern, so they travel with the entries. */
    internal fun json(state: ClaudeSettings.State, selected: Selected): JsonArray = buildJsonArray {
        modelRows(selected)
        effortRows(selected)
        modeRows(selected)
        chatRows(state)
        securityRows(state)
        sourceRows(state)
        toolRows(ALLOW, "Allowed tools", state.allowedTools, deferred = true)
        toolRows(DENY, "Disallowed tools", state.disallowedTools, deferred = true)
        toolRows(ALWAYS, "Always allowed tools", state.alwaysAllowTools, deferred = false)
        mcpRows(state)
    }

    /**
     * Applies [key] to the settings document, or answers false when this build does not know it.
     *
     * PURE over [ClaudeSettings.State], and therefore a DELTA: it is called from inside `ClaudeSettings.update`,
     * whose block runs twice — once against the in-memory copy, once against the document re-read from the
     * safe — so it writes only the field the key names and does nothing else.
     *
     * [models] is the session's own catalogue, and it is what makes `model:<id>` checkable at all: there is no
     * static list of model ids in this plugin, so the only honest allowlist is the one the binary reported.
     *
     * `always:` is not handled here — see [alwaysAllowTool].
     */
    fun apply(state: ClaudeSettings.State, key: String, on: Boolean, models: List<String>): Boolean {
        val prefix = key.substringBefore(':', missingDelimiterValue = "")
        if (prefix.isEmpty()) return applyFlag(state, key, on)
        val value = key.substringAfter(':')
        return applyChoice(state, prefix, value, on, models) ?: applyList(state, prefix, value, on) ?: false
    }

    /**
     * Mirrors a model / effort / permission-mode row onto the running session, and does nothing for any other
     * key.
     *
     * Called AFTER [apply] has accepted the key, so the suffix has already been checked against its catalogue;
     * this deliberately re-derives nothing. It is also called OUTSIDE `ClaudeSettings.update`, because
     * `changePermissionMode` opens an update of its own and a nested read-modify-write on the same document is
     * exactly the race that serialising those writes exists to prevent.
     */
    fun applyToSession(session: ClaudeSession, key: String, on: Boolean) {
        if (!on) return
        val value = key.substringAfter(':', missingDelimiterValue = "")
        when (key.substringBefore(':', missingDelimiterValue = "")) {
            MODEL -> session.settings.changeModel(value)
            EFFORT -> session.settings.changeEffort(value)
            MODE -> session.settings.changePermissionMode(value)
            else -> {}
        }
    }

    /**
     * `always:Bash` → `Bash`; null for any other key, and for a name outside [ToolNaming.BUILTIN_TOOLS].
     *
     * **This group changes what "Always allow" is, and the change is deliberate.** The set used to be granted
     * only by answering a real permission card for that tool, and the Settings page offered nothing but
     * revocation — so a tool could not be auto-approved before it had asked at least once. From this menu
     * `Bash`, `Write` or `Edit` can be pre-authorised having asked for nothing.
     *
     * What still holds, and is why that is a trade rather than a hole: `SensitiveGuard` runs BEFORE any
     * auto-approval and has no opt-out, so a credential file, a dangerous command, the system temp folder and
     * foreign territory still raise a card for a tool that is always allowed; and `PermissionBroker` still
     * refuses to auto-approve a reviewable write outside the project root. The set widens what may run unasked
     * INSIDE the project, never what the guard lets past.
     *
     * Answered here instead of in [apply] because the set owns its own persistence
     * (`ClaudeSettings.alwaysAllow`), which writes through `update` itself — and that call cannot be made from
     * inside another `update` block without nesting one read-modify-write of the document inside another.
     */
    fun alwaysAllowTool(key: String): String? {
        if (!key.startsWith("$ALWAYS:")) return null
        return key.removePrefix("$ALWAYS:").takeIf { it in ToolNaming.BUILTIN_TOOLS }
    }

    // ── The groups ───────────────────────────────────────────────────────────────────────────────────────

    private fun JsonArrayBuilder.modelRows(selected: Selected) {
        // Same filter the composer pill applies: the floating "default" alias duplicates the concrete tier and
        // carries no version, so it is not offered as a choice anywhere.
        selected.models.filter { it.value != ClaudeSession.RECOMMENDED_ALIAS }.forEach { m ->
            val label = JcefModelLabels.modelDisplayLabel(m)
            entry("$MODEL:${m.value}", "Model", label, m.value == selected.model, radio = true)
        }
    }

    private fun JsonArrayBuilder.effortRows(selected: Selected) {
        EffortLevel.entries.forEach { level ->
            val label = level.wire.replaceFirstChar { it.uppercase() }
            entry("$EFFORT:${level.wire}", "Effort", label, level.wire == selected.effort, radio = true)
        }
    }

    private fun JsonArrayBuilder.modeRows(selected: Selected) {
        ClaudeSession.PERMISSION_MODES.forEach { wire ->
            entry("$MODE:$wire", "Permission mode", PermissionMode.labelFor(wire), wire == selected.mode, radio = true)
        }
    }

    // NB two of these four are launch-time and the group does not say so: file checkpointing is an environment
    // variable the process is spawned with, and partial messages is `--include-partial-messages`. Neither can
    // move under a running binary. They are not marked `deferred` because the marker is drawn on the GROUP
    // heading, so a group with two immediate rows and two launch-time ones cannot be labelled truthfully
    // either way — telling them apart means splitting the group, which is a decision about the menu's shape.
    private fun JsonArrayBuilder.chatRows(s: ClaudeSettings.State) {
        entry("restoreChats", "Chat", "Restore open chats on startup", s.restoreOpenChatsOnStartup)
        entry("reduceMotion", "Chat", "Reduce motion", s.reduceMotion)
        entry("checkpointing", "Chat", "Let Claude rewind file changes", s.enableFileCheckpointing)
        entry("partialMessages", "Chat", "Stream partial messages", s.includePartialMessages)
    }

    /**
     * The rules the guard is made of, one row each, **inside a sub-level per [SecurityCategory]**.
     *
     * They are here because the moment you want one is the moment it has just refused something — and a trip to a
     * settings dialog then is a trip taken while annoyed. Turning one off never grants anything silently: it
     * downgrades a refusal to a card you still have to answer.
     *
     * This is the one group with a third level, and it earned it by growing: seven flat rows were a list, twelve
     * are a wall. The sub-level is the rule's own [SecurityCategory], so the menu cannot invent a grouping the
     * guard does not have — and a row's `sub` is the only thing that differs from every other group here.
     *
     * **Polarity: a row is `on` when the rule is ENFORCED**, which is the same direction as the seven booleans
     * this replaced (`Block credential files` checked = blocking). The stored field is the DISABLED set, so an
     * `on:false` ADDS to it — [applyRule] is where that inversion lives, once.
     */
    private fun JsonArrayBuilder.securityRows(s: ClaudeSettings.State) {
        val disabled = csvItems(s.disabledSecurityRules)
        // A rule SUSPENDED from a block is open right now, so the row has to read open. Without this the menu
        // showed it enforced while the guard was letting it through to a card — the one failure mode this file
        // already refuses everywhere else, a switch that says something different from what is in force.
        val now = System.currentTimeMillis()
        val suspended = SecuritySuspensions.active(s.securityRuleSuspensions, now) +
            SecuritySuspensions.sessionSuspended()
        SecurityCategory.entries.forEach { category ->
            SecurityRule.of(category).forEach { rule ->
                val enforced = rule.name !in disabled && rule !in suspended
                entry("$RULE:${rule.name}", "Security", rule.label, enforced, sub = category.label)
            }
        }
    }

    private fun JsonArrayBuilder.sourceRows(s: ClaudeSettings.State) {
        ClaudeSession.SETTING_SOURCES.forEach { source ->
            val label = source.replaceFirstChar { it.uppercase() }
            entry("$SOURCE:$source", "Setting sources", label, csvHas(s.settingSources, source), deferred = true)
        }
    }

    private fun JsonArrayBuilder.toolRows(prefix: String, group: String, csv: String, deferred: Boolean) {
        ToolNaming.BUILTIN_TOOLS.forEach { tool ->
            entry("$prefix:$tool", group, tool, csvHas(csv, tool), deferred = deferred)
        }
    }

    private fun JsonArrayBuilder.mcpRows(s: ClaudeSettings.State) {
        entry("ideMcp", "MCP", "JetBrains MCP server", s.ideMcpEnabled, deferred = true)
        entry("strictMcp", "MCP", "Only the MCP servers configured here", s.strictMcpConfig, deferred = true)
    }

    /**
     * One row. `type` and `deferred` are always emitted rather than left to the page's defaults: the page
     * decides the group heading from `deferred`, and a value it has to infer is one that can be inferred
     * differently on the next module that reads it.
     *
     * [sub] is the exception, and it is omitted when there is none **on purpose**: a row without it is a row of
     * the group itself, exactly as every row was before the security rules needed a third level, so the other
     * nine groups are untouched by its existence rather than opted out of it.
     */
    private fun JsonArrayBuilder.entry(
        key: String,
        group: String,
        label: String,
        on: Boolean,
        radio: Boolean = false,
        deferred: Boolean = false,
        sub: String? = null,
    ) = addJsonObject {
        put("key", key)
        put("group", group)
        if (sub != null) put("sub", sub)
        put("label", label)
        put("on", on)
        put("type", if (radio) TYPE_RADIO else TYPE_CHECK)
        put("deferred", deferred)
    }

    // ── Writing ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Key → setter, one per single boolean field. A lookup table rather than a `when`: the branches are all the
     * same shape (assign one field), so a `when` here is only cyclomatic complexity with no decision in it —
     * this file's `securityRows`/`entry` pairing already treats "one row, one field" as data, not control flow.
     */
    private val FLAG_SETTERS: Map<String, (ClaudeSettings.State, Boolean) -> Unit> = mapOf(
        "restoreChats" to { s, on -> s.restoreOpenChatsOnStartup = on },
        "reduceMotion" to { s, on -> s.reduceMotion = on },
        "checkpointing" to { s, on -> s.enableFileCheckpointing = on },
        "partialMessages" to { s, on -> s.includePartialMessages = on },
        "ideMcp" to { s, on -> s.ideMcpEnabled = on },
        "strictMcp" to { s, on -> s.strictMcpConfig = on },
    )

    /** Null is not a case here: an unknown plain key is simply not ours. */
    private fun applyFlag(state: ClaudeSettings.State, key: String, on: Boolean): Boolean {
        val setter = FLAG_SETTERS[key] ?: return false
        setter(state, on)
        return true
    }

    /** The three single-valued groups. Null when [prefix] names none of them. */
    private fun applyChoice(
        state: ClaudeSettings.State,
        prefix: String,
        value: String,
        on: Boolean,
        models: List<String>,
    ): Boolean? = when (prefix) {
        MODEL -> select(value in models, on) { state.model = value }
        EFFORT -> select(EffortLevel.from(value) != null, on) { state.effort = value }
        MODE -> select(PermissionMode.from(value) != null, on) { state.permissionMode = value }
        else -> null
    }

    /** The multi-valued groups, each persisted as one CSV field. Null when [prefix] names none of them. */
    private fun applyList(state: ClaudeSettings.State, prefix: String, value: String, on: Boolean): Boolean? =
        when (prefix) {
            RULE -> applyRule(state, value, on)

            SOURCE -> toggle(value in ClaudeSession.SETTING_SOURCES, state.settingSources, value, on) {
                state.settingSources = it
            }

            ALLOW -> toggle(value in ToolNaming.BUILTIN_TOOLS, state.allowedTools, value, on) {
                state.allowedTools = it
            }

            DENY -> toggle(value in ToolNaming.BUILTIN_TOOLS, state.disallowedTools, value, on) {
                state.disallowedTools = it
            }

            else -> null
        }

    /**
     * `rule:TEMP_DIR` → add or remove that rule from the guard's DISABLED set. False for a name outside
     * [SecurityRule], which is what keeps the key set closed against a browser.
     *
     * **The inversion lives here and only here**: the row is `on` when the rule is ENFORCED, and the field stores
     * what is switched OFF, so `on` and the CSV membership are opposites. Written through
     * [SecurityRule.canonicalCsv] rather than left in toggle order, because the Settings page rebuilds the same
     * field from its checkboxes and compares the two spellings to decide whether it has been edited.
     */
    private fun applyRule(state: ClaudeSettings.State, value: String, on: Boolean): Boolean {
        val rule = SecurityRule.from(value) ?: return false
        val next = csvToggle(state.disabledSecurityRules, rule.name, on = !on)
        state.disabledSecurityRules = SecurityRule.canonicalCsv(csvItems(next))
        // Enforcing it again CANCELS whatever was suspending it, both storages. Otherwise the switch would not
        // do what it says: the timed suspension would keep the rule open and the row would flip itself back at
        // the next push. It is also what revokes every command approved under this rule — those are honoured
        // only while the rule is open, so closing it is the revocation.
        if (on) {
            state.securityRuleSuspensions =
                SecuritySuspensions.without(state.securityRuleSuspensions, rule, System.currentTimeMillis())
            SecuritySuspensions.releaseSessionScoped(rule)
        }
        return true
    }

    /**
     * One option of a radio group: [write] it when it was chosen, and refuse outright when [known] is false.
     *
     * **An `on:false` is accepted and IGNORED**, which is the only answer that leaves the setting valid. These
     * three groups have no empty state — every chat launches with some model, some effort and some permission
     * mode — so honouring a deselection would mean inventing a replacement value, and a blank `permissionMode`
     * is the "reset to default" bug with extra steps. It answers true rather than false because the key IS one
     * this build knows: false is reserved for a key that does not exist, which the caller reports as a rename
     * that lost its other half.
     */
    private fun select(known: Boolean, on: Boolean, write: () -> Unit): Boolean {
        if (!known) return false
        if (on) write()
        return true
    }

    /** Adds or removes [value] from a CSV field, refusing outright when [known] is false. */
    private fun toggle(known: Boolean, csv: String, value: String, on: Boolean, write: (String) -> Unit): Boolean {
        if (!known) return false
        write(csvToggle(csv, value, on))
        return true
    }

    // ── CSV fields ───────────────────────────────────────────────────────────────────────────────────────

    private fun csvItems(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun csvHas(csv: String, value: String): Boolean = value in csvItems(csv)

    /**
     * The CSV a checkbox group persists as, with [value] added or removed.
     *
     * Rebuilt from the parsed items rather than edited as text: appending a name to a blank field, or removing
     * the last one, is where a stray leading or trailing comma comes from — and every reader of these fields
     * splits on the comma, so an empty item becomes a tool named "" in a launch flag.
     */
    private fun csvToggle(csv: String, value: String, on: Boolean): String {
        val current = csvItems(csv)
        val next = if (on) current + value else current.filterNot { it == value }
        return next.distinct().joinToString(",")
    }

    private fun selectedIn(session: ClaudeSession) = Selected(
        models = session.models,
        model = session.model ?: session.preferredDefaultModel(),
        effort = session.effort,
        mode = session.permissionMode,
    )

    // The key prefixes. Constants because each is written in two places that must not drift — the row [json]
    // emits and the branch [apply] validates it in — and a rename that reaches only one of them is a control
    // the user can press and nothing answers.
    private const val MODEL = "model"
    private const val EFFORT = "effort"
    private const val MODE = "mode"
    private const val RULE = "rule"
    private const val SOURCE = "source"
    private const val ALLOW = "allow"
    private const val DENY = "deny"
    private const val ALWAYS = "always"

    private const val TYPE_CHECK = "check"
    private const val TYPE_RADIO = "radio"
}
