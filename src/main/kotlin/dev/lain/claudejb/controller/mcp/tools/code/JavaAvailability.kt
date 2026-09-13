package dev.lain.claudejb.controller.mcp.tools.code

import dev.lain.claudejb.util.InstalledPlugins

internal object JavaAvailability {

    const val PLUGIN_ID = "com.intellij.java"

    fun isEnabled(): Boolean = InstalledPlugins.isEnabled(PLUGIN_ID)
}
