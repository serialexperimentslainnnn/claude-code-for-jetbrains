package dev.lain.claudejb.git

import dev.lain.claudejb.util.InstalledPlugins

object GitAvailability {

    const val GIT_PLUGIN_ID = "Git4Idea"

    fun isGitPluginEnabled(): Boolean = InstalledPlugins.isEnabled(GIT_PLUGIN_ID)
}
