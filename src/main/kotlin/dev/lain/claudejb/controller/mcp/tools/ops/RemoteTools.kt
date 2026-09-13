package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.lain.claudejb.controller.mcp.IdeActions
import dev.lain.claudejb.controller.mcp.Reveal
import dev.lain.claudejb.controller.mcp.TargetContext
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import dev.lain.claudejb.util.InstalledPlugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RemoteTools(private val project: Project, private val actions: IdeActions, private val reveal: Reveal) {

    private class Closed(val pluginId: String, val label: String, val entries: Map<String, List<String>>)

    fun domain(): ToolDomain = ToolDomain(
        "remote",
        "The closed-source Tools menu entries the IDE may have: Deployment, SSH sessions, Qodana and the vulnerable " +
            "dependencies of the Problems view; each is reached through the actions the plugin registers on this IDE",
        listOf(
            Tool(DEPLOYMENT, ::deployment),
            Tool(SSH_SESSION, ::sshSession),
            Tool(QODANA, ::qodana),
            Tool(VULNERABLE_DEPENDENCIES, ::vulnerable),
        ),
    )

    private suspend fun deployment(args: ToolArgs): ToolResult = fire(DEPLOYMENT_PLUGIN, args.string("action"), args)

    private suspend fun sshSession(args: ToolArgs): ToolResult = fire(SSH_PLUGIN, "session", args)

    private suspend fun qodana(args: ToolArgs): ToolResult {
        val action = args.optionalString("action") ?: "results"
        if (action == "results") return problems(QODANA_GROUP, QODANA_TAB, args)
        return fire(QODANA_PLUGIN, action, args)
    }

    private suspend fun vulnerable(args: ToolArgs): ToolResult = problems(VULNERABLE_GROUP, VULNERABLE_TAB, args)

    private suspend fun fire(closed: Closed, action: String, args: ToolArgs): ToolResult {
        val fragments = closed.entries[action] ?: throw ToolException("action must be one of ${closed.entries.keys.joinToString()}")
        val id = withContext(Dispatchers.EDT) { if (InstalledPlugins.isEnabled(closed.pluginId)) discover(fragments) else null }
            ?: throw ToolException(missing(closed, fragments))
        val target = TargetContext.target(args)
        if (target.named) actions.dispatch(id, target) else actions.dispatch(id)
        return ToolResult.toon(
            buildJsonObject {
                put("plugin", closed.pluginId)
                put("action", action)
                put("id", id)
                put("dispatched", true)
            },
        )
    }

    private fun missing(closed: Closed, fragments: List<String>): String =
        if (!InstalledPlugins.isEnabled(closed.pluginId)) {
            "the ${closed.label} plugin (${closed.pluginId}) is not installed in this IDE"
        } else {
            "the ${closed.label} plugin registers no action matching ${fragments.joinToString()}; actions lists its ids"
        }

    private fun discover(fragments: List<String>): String? {
        val manager = ActionManager.getInstance()
        val ids = manager.getActionIdList("")
        return fragments.firstNotNullOfOrNull { fragment ->
            ids.firstOrNull { it.equals(fragment, ignoreCase = true) }
                ?: ids.firstOrNull { id -> id.contains(fragment, ignoreCase = true) && manager.getActionOrStub(id) != null }
        }
    }

    private suspend fun problems(group: String, tab: String, args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val (rows, tabId) = withContext(Dispatchers.EDT) {
            val collector = ProblemsCollector.getInstance(project)
            val all = collector.getProblemFiles().flatMap { collector.getFileProblems(it) } + collector.getOtherProblems()
            val matching = all.filter { it.group?.contains(group, ignoreCase = true) == true }
            val window = ProblemsViewToolWindowUtils.getToolWindow(project)
            val content = window?.contentManager?.contents?.firstOrNull { it.displayName?.contains(tab, ignoreCase = true) == true }
            val id = (content?.component as? ProblemsViewTab)?.getTabId()
            matching.map { problem ->
                buildJsonObject {
                    put("text", problem.text)
                    put("group", problem.group ?: "")
                    put("description", problem.description ?: "")
                }
            } to id
        }
        if (tabId != null) {
            reveal.problems(tabId)
        } else if (reveal.mirroring) {
            reveal.problems("")
        }
        return ToolResult.toon(
            buildJsonObject {
                put("group", group)
                put("tab", tabId ?: "")
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("problems", buildJsonArray { rows.take(max).forEach { add(it) } })
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX = 100
        private const val QODANA_GROUP = "Qodana"
        private const val QODANA_TAB = "Qodana"
        private const val VULNERABLE_GROUP = "Vulnerab"
        private const val VULNERABLE_TAB = "Vulnerable"

        private val DEPLOYMENT_PLUGIN = Closed(
            "com.jetbrains.plugins.webDeployment",
            "Deployment",
            linkedMapOf(
                "upload" to listOf("PublishGroup.Upload", "PublishGroup.UploadTo"),
                "download" to listOf("PublishGroup.Download", "PublishGroup.DownloadFrom"),
                "sync" to listOf("PublishGroup.SyncLocalVsRemote", "PublishGroup.SyncLocalVsRemoteWith"),
                "compare" to listOf("PublishGroup.CompareLocalVsRemote", "PublishGroup.CompareLocalVsRemoteWith"),
                "browse" to listOf("WebDeployment.BrowseServers", "ActivateRemoteHostToolWindow"),
                "configure" to listOf("WebDeployment.Configuration", "PublishGroup.Configure"),
            ),
        )

        private val SSH_PLUGIN = Closed(
            "intellij.ssh.plugin",
            "SSH",
            linkedMapOf("session" to listOf("com.jetbrains.plugins.remotesdk.console.RunSshConsoleAction", "RunSshConsoleAction")),
        )

        private val QODANA_PLUGIN = Closed(
            "org.intellij.qodana",
            "Qodana",
            linkedMapOf(
                "run" to listOf("Qodana.RunQodanaAction", "Qodana.RunAction"),
                "open" to listOf("Qodana.OpenReportAction", "Qodana.SarifFileReportAction"),
            ),
        )

        val DEPLOYMENT = ToolSpec(
            "deployment",
            "Tools ▸ Deployment through the Deployment plugin's own actions, discovered on this IDE: upload, download, sync, " +
                "compare, browse (Remote Host) or configure; with path the entry acts on that file. The plugin's dialog is " +
                "the user's to finish. Refused when the plugin is missing.",
            listOf(Param("action", "upload, download, sync, compare, browse or configure"), TargetContext.PARAMS.first()),
            mutates = true,
        )

        val SSH_SESSION = ToolSpec(
            "ssh_session",
            "Tools ▸ Start SSH Session through the SSH plugin's action: the IDE's host chooser opens for the user.",
            emptyList(),
            mutates = true,
        )

        val QODANA = ToolSpec(
            "qodana",
            "Qodana: action=results (default) returns the Qodana problems the IDE holds and shows the Qodana tab of the " +
                "Problems view; run and open fire the Qodana plugin's own actions, discovered on this IDE.",
            listOf(
                Param("action", "results (default), run or open", required = false),
                Param("max", "Maximum problems (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
            mutates = true,
        )

        val VULNERABLE_DEPENDENCIES = ToolSpec(
            "vulnerable_dependencies",
            "The vulnerable dependencies the Package Checker found, as the Problems view's Vulnerable Dependencies tab lists " +
                "them; the tab is shown without focus.",
            listOf(Param("max", "Maximum problems (default $DEFAULT_MAX)", type = "integer", required = false)),
        )
    }
}
