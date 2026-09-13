package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.util.InstalledPlugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object HttpClientGateway {

    fun available(): Boolean = InstalledPlugins.isEnabled(PLUGIN_ID) && configurationType() != null

    suspend fun configurationFor(project: Project, psiFile: PsiFile, path: String): RunnerAndConfigurationSettings {
        val type = configurationType()
            ?: throw ToolException("the HTTP Client plugin ($PLUGIN_ID) is not installed in this IDE, so nothing runs .http files")
        val settings = readAction {
            ConfigurationContext(psiFile).configurationsFromContext
                ?.firstOrNull { it.configurationType === type }
                ?.configurationSettings
        } ?: throw ToolException("the HTTP Client offers no run configuration for $path; it runs .http and .rest request files")
        withContext(Dispatchers.EDT) {
            val manager = RunManager.getInstance(project)
            if (!manager.hasSettings(settings)) manager.setTemporaryConfiguration(settings)
        }
        return settings
    }

    private fun configurationType(): ConfigurationType? =
        ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull { type ->
            (type.javaClass.classLoader as? PluginAwareClassLoader)?.pluginId?.idString == PLUGIN_ID &&
                (type.id.contains(HTTP, ignoreCase = true) || type.displayName.contains(HTTP, ignoreCase = true))
        }

    private const val PLUGIN_ID = "com.jetbrains.restClient"
    private const val HTTP = "HTTP"
}
