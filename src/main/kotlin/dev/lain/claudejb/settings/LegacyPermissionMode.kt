package dev.lain.claudejb.settings

import dev.lain.claudejb.session.PermissionMode

/**
 * Which permission mode a REPOSITORY is allowed to choose for the user — the one thing the legacy migration
 * may not take on trust.
 *
 * **Why this exists.** `.idea/claude-code.xml` is a file that lives in a repository and gets committed. Until
 * 5.5.0 that was its own answer: the settings were per project, so a mode written in a project's file governed
 * that project and nothing else. The settings are GLOBAL now, and [LegacyProjectSettings] adopts such a file
 * wholesale the first time the plugin runs with nothing stored yet — a fresh install opening its first
 * project. A `permissionMode` of `bypassPermissions` in a repository the user merely opened would therefore
 * become their permission mode **for every project they open afterwards**, silently, having never been asked.
 *
 * [dev.lain.claudejb.permission.SensitiveGuard] still runs ahead of every auto-approval regardless of mode, so
 * this was never a total bypass — but "not total" is not consent. The rule here is narrow and absolute: **a
 * legacy file may not silently weaken security.** Everything else in it is adopted as before; a mode weaker
 * than the default is dropped, the user keeps the default, and [LegacySettingsNotice] says so out loud.
 *
 * Nothing is *asked* here, deliberately. The migration runs on the first read of the settings, off the EDT and
 * long before any session exists, and the trust prompt that would be the natural place to ask is a launch-time
 * modal owned by the session. A question nobody is there to answer must resolve to the safe side, so it is not
 * asked: it is applied, and reported. Setting the mode on purpose is one combo in Settings ▸ Claude Code.
 */
internal object LegacyPermissionMode {

    /** The mode a refused document ends up with: exactly the one a fresh install has. */
    val SAFE: String = ClaudeSettings.State().permissionMode

    /**
     * True when [wire] would make the plugin ask the user LESS than the default does — the only reason to
     * refuse a value the migration would otherwise adopt.
     *
     * The classification is an exhaustive `when` over [PermissionMode] on purpose: a mode added to that enum
     * later does not compile until somebody decides which side of this line it falls on. Deciding by hand
     * beats defaulting, in either direction.
     *  - `default` — the baseline. Every tool call is a card.
     *  - `plan` — read-only planning, stricter than the baseline, never a weakening.
     *  - `acceptEdits` / `bypassPermissions` — the plugin's own auto-approval in
     *    [dev.lain.claudejb.permission.PermissionBroker]: cards stop appearing.
     *  - `dontAsk` / `auto` — handed to the binary verbatim (`SessionLauncher.binaryPermissionMode` only
     *    rewrites the two above), so the binary can settle a call without ever sending `can_use_tool`. That is
     *    strictly worse than the two above, which at least still pass through the plugin's guard.
     *  - anything unrecognised — refused. An unknown string is not a safe string: it reaches the binary as
     *    `--permission-mode <it>`, so a value this build has never heard of may still mean something
     *    permissive over there. Failing closed costs a user with a newer binary one combo in Settings.
     *
     * Blank (or absent) is not a choice at all — it is what an XML without the field looks like — and
     * [ClaudeSettings.applyTo] already reads it as the default, so it is adopted quietly.
     */
    fun weakensSecurity(wire: String?): Boolean {
        if (wire.isNullOrBlank()) return false
        return when (PermissionMode.from(wire)) {
            PermissionMode.DEFAULT -> false
            PermissionMode.PLAN -> false
            PermissionMode.ACCEPT_EDITS -> true
            PermissionMode.BYPASS -> true
            PermissionMode.DONT_ASK -> true
            PermissionMode.AUTO -> true
            null -> true
        }
    }
}
