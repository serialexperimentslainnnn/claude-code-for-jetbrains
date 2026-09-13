package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.util.PluginIds
import java.lang.reflect.InvocationTargetException

internal object PluginDetailsGateway {

    class Row(val id: String, val name: String, val version: String, val vendor: String, val enabled: Boolean)

    fun activePlugins(): List<Row> {
        val service = service() ?: throw ToolException(MISSING)
        return try {
            (call(service, "getActivePlugins") as Sequence<*>).filterNotNull().map(::row).toList()
        } catch (e: ReflectiveOperationException) {
            throw ToolException("the IDE's plugin details service refused: ${reason(e)}", e)
        }
    }

    private fun reason(e: ReflectiveOperationException): String? =
        if (e is InvocationTargetException) e.targetException.message else e.message

    private fun service(): Any? = runCatching { Class.forName(SERVICE).getMethod("getInstance").invoke(null) }.getOrNull()

    private fun row(details: Any): Row {
        val id = call(details, "getId").toString()
        return Row(
            id = id,
            name = call(details, "getName").toString(),
            version = call(details, "getVersion")?.toString().orEmpty(),
            vendor = call(details, "getVendor")?.let { call(it, "getName") }?.toString().orEmpty(),
            enabled = enabled(PluginIds.of(id)),
        )
    }

    private fun enabled(id: PluginId): Boolean = PluginManagerCore.isLoaded(id) && !PluginManagerCore.isDisabled(id)

    private fun call(target: Any, name: String): Any? = target.javaClass.getMethod(name).invoke(target)

    private const val SERVICE = "com.intellij.ide.plugins.PluginDetailsService"

    private const val MISSING = "listing the installed plugins needs the IDE's plugin details service, which arrived in 2026.2; " +
        "this IDE is older, so name the plugin you need and check its tool window or actions instead"
}
