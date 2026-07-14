package dev.lain.claudejb.util

import com.intellij.ide.plugins.PluginManager

/**
 * "Is plugin X installed and enabled?" — using **public** API only, and in a way that works on every supported IDE.
 *
 * Two traps sit on this innocent-looking question, and both are invisible to the compiler:
 *  - `PluginId.getId(…)` called from Kotlin binds to `PluginId.Companion` (`PluginId` is a Kotlin class since
 *    2025.2), a symbol absent from older IDEs → `NoSuchFieldError` at runtime on 2024.2–2025.1. Hence [PluginIds],
 *    a Java shim: javac emits a plain static call that resolves everywhere.
 *  - Scanning `PluginManagerCore.getPlugins()` instead avoids that, but the method is `@ApiStatus.Internal` and the
 *    Marketplace **rejects** plugins that touch internal API (this project already had a release blocked over
 *    `findEnabledPlugin`).
 *
 * So: build the id in Java, ask through the public [PluginManager.isPluginInstalled]. Both traps were caught by
 * `verifyPlugin` (against IC-251 and IU-262 respectively), never by the compiler.
 */
object InstalledPlugins {

    /** True when a plugin with this id is installed and enabled in the running IDE. */
    fun isEnabled(pluginId: String): Boolean = PluginManager.isPluginInstalled(PluginIds.of(pluginId))
}
