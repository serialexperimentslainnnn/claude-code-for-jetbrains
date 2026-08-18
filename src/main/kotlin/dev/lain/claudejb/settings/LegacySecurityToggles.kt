package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule

/**
 * Adopts the seven `securityBlock*` booleans into [ClaudeSettings.State.disabledSecurityRules], once.
 *
 * ### Why a migration exists at all for a boolean that was simply replaced
 * [SettingsStore] is **field-agnostic and versionless**: the document is the serialization of
 * [ClaudeSettings.State], read with `ignoreUnknownKeys` and `coerceInputValues`. That is what makes adding a
 * field free — and it is exactly why removing one is not. A deleted field is not a load error; it is a **silent
 * reset to the property's default**. So dropping `securityBlockTempDirs` in the same release that introduced the
 * rule set would have turned that rule back ON for everyone who had deliberately switched it off, with nothing
 * anywhere saying so. The old fields therefore stay for one release as inputs to this fold and nothing else.
 *
 * ### It has to be idempotent, and it is
 * [ClaudeSettings.update] applies its block **twice by design** (once to the in-memory copy, once to the document
 * re-read from the safe), and this runs on every decode, so "append the rule id" must not append it twice. A
 * boolean that has already been adopted is `true`, which this reads as "nothing to say" — so the second and every
 * later pass is a no-op.
 *
 * ### What it deliberately does not do
 * It never drops an id it does not recognise from the stored CSV. A document written by a NEWER version can name
 * a rule this build has never heard of, and pruning it here would silently re-enable that rule the next time an
 * older IDE opened the project — a downgrade turning a user's decision off. The reader
 * ([ClaudeSettings.disabledSecurityRules]) ignores what it cannot resolve, which is where that belongs.
 */
internal object LegacySecurityToggles {

    /** One superseded boolean: the rule it meant, how to read it, and how to retire it. */
    private class Superseded(
        val rule: SecurityRule,
        val isEnforced: (ClaudeSettings.State) -> Boolean,
        val retire: (ClaudeSettings.State) -> Unit,
    )

    // TODO(5.6.0): delete this table, `adopt`, and the seven fields on ClaudeSettings.State it reads — one
    //  release after `disabledSecurityRules` shipped, every stored document has passed through the fold.
    private val SUPERSEDED = listOf(
        Superseded(
            SecurityRule.CREDENTIALS,
            { it.securityBlockCredentials },
            { it.securityBlockCredentials = true },
        ),
        Superseded(
            SecurityRule.SECRET_DUMPING_COMMANDS,
            { it.securityBlockDangerousCommands },
            { it.securityBlockDangerousCommands = true },
        ),
        Superseded(
            SecurityRule.TEMP_DIR,
            { it.securityBlockTempDirs },
            { it.securityBlockTempDirs = true },
        ),
        Superseded(
            SecurityRule.OTHER_USER_HOME,
            { it.securityBlockForeignOtherUserHome },
            { it.securityBlockForeignOtherUserHome = true },
        ),
        Superseded(
            SecurityRule.NETWORK_MOUNT,
            { it.securityBlockForeignNetworkMounts },
            { it.securityBlockForeignNetworkMounts = true },
        ),
        Superseded(
            SecurityRule.WSL_MOUNT,
            { it.securityBlockForeignWslMounts },
            { it.securityBlockForeignWslMounts = true },
        ),
        Superseded(
            SecurityRule.OUTSIDE_PROJECT,
            { it.securityBlockOutsideProject },
            { it.securityBlockOutsideProject = true },
        ),
    )

    /** Folds any `false` boolean into the disabled-rule set and retires it. A no-op when there is none. */
    fun adopt(state: ClaudeSettings.State) {
        val off = SUPERSEDED.filterNot { it.isEnforced(state) }
        if (off.isEmpty()) return
        val ids = LinkedHashSet<String>()
        state.disabledSecurityRules.split(',').map { it.trim() }.filterTo(ids) { it.isNotEmpty() }
        off.forEach { ids += it.rule.name }
        state.disabledSecurityRules = ids.joinToString(",")
        off.forEach { it.retire(state) }
    }
}
