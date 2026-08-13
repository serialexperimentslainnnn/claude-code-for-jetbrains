package dev.lain.claudejb.git

import dev.lain.claudejb.util.InstalledPlugins

/**
 * The single place that answers *"may we touch `git4idea` at all?"*.
 *
 * The Git dependency is declared **optional** (`META-INF/claude-git.xml`), so on an IDE where the Git plugin is
 * absent or disabled the `git4idea.*` classes are simply not in this plugin's classloader. Every caller therefore
 * asks here FIRST, and only [GitGateway] — reached exclusively behind that answer — names a git4idea type. An
 * optional dependency that is not satisfied must degrade to "no Git surface", never to a `NoClassDefFoundError`.
 *
 * The id is asked through [InstalledPlugins] rather than `PluginId.getId(…)` on purpose: see that file for the
 * two runtime traps (a Kotlin `PluginId` companion below 252, and the Marketplace's internal-API rejection).
 */
object GitAvailability {

    /** The bundled Git plugin's id, as declared in its own descriptor (`plugins/vcs-git`). */
    const val GIT_PLUGIN_ID = "Git4Idea"

    /** True when the bundled Git plugin is installed **and enabled** in the running IDE. */
    fun isGitPluginEnabled(): Boolean = InstalledPlugins.isEnabled(GIT_PLUGIN_ID)
}
