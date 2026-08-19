package dev.lain.claudejb.settings

import dev.lain.claudejb.permission.SecurityRule

internal object LegacySecurityToggles {

    private class Superseded(
        val rule: SecurityRule,
        val isEnforced: (ClaudeSettings.State) -> Boolean,
        val retire: (ClaudeSettings.State) -> Unit,
    )

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
