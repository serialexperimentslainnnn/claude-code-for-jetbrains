package dev.lain.claudejb.controller.github

import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.util.InstalledPlugins

internal object GitHubAvailability {

    const val PLUGIN_ID = "org.jetbrains.plugins.github"

    const val MISSING = "the GitHub plugin ($PLUGIN_ID) is not installed or is disabled in this IDE"

    fun isEnabled(): Boolean = InstalledPlugins.isEnabled(PLUGIN_ID)

    fun require() {
        if (!isEnabled()) throw ToolException(MISSING)
    }
}
