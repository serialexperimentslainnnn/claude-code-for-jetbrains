package dev.lain.claudejb.util

import com.intellij.ide.plugins.PluginManager

object InstalledPlugins {

    fun isEnabled(pluginId: String): Boolean = PluginManager.isPluginInstalled(PluginIds.of(pluginId))
}
