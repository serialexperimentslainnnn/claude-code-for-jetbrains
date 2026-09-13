package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ServiceTools(private val project: Project, private val scope: CoroutineScope) {

    private val tree = ServiceTree(project)

    fun domain(): ToolDomain = ToolDomain(
        "services",
        "The Services tool window as the user sees it — Docker, Kubernetes, run dashboards, databases, whatever the installed " +
            "plugins contribute — and the actions the IDE itself offers on each node",
        listOf(
            Tool(SERVICES, ::services),
            Tool(SERVICE_ACTIONS, ::serviceActions),
            Tool(SERVICE_ACTION, ::serviceAction),
            Tool(SERVICE_OPEN, ::serviceOpen),
        ),
    )

    private suspend fun services(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val filter = args.optionalString("filter").orEmpty()
        val walk = withContext(Dispatchers.Default) { tree.walk(if (filter.isEmpty()) max else FILTER_WALK_CEILING) }
        val matching = walk.nodes.filter { filter.isEmpty() || it.matches(filter) }
        val rows = matching.take(max)
        return ToolResult.toon(
            buildJsonObject {
                put("count", matching.size)
                put("truncated", walk.truncated || matching.size > rows.size)
                put("services", buildJsonArray { rows.forEach { add(row(it)) } })
            },
        )
    }

    private fun row(node: ServiceNode): JsonObject = buildJsonObject {
        put("path", node.path)
        put("name", node.name)
        put("kind", node.kind)
        put("state", node.state)
    }

    private suspend fun serviceActions(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val max = args.int("max", DEFAULT_MAX)
        val node = withContext(Dispatchers.Default) { tree.find(path) }
        val (entries, fromTree) = withContext(Dispatchers.EDT) {
            ServiceActions(project, node).run {
                reveal()
                list() to fromTree
            }
        }
        val rows = entries.take(max)
        return ToolResult.toon(
            buildJsonObject {
                put("path", node.path)
                put("context", if (fromTree) "tree" else "synthetic")
                put("count", entries.size)
                put("truncated", entries.size > rows.size)
                put(
                    "actions",
                    buildJsonArray {
                        rows.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("id", entry.id)
                                    put("text", entry.text)
                                    put("enabled", entry.enabled)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun serviceAction(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val action = args.string("action")
        val node = withContext(Dispatchers.Default) { tree.find(path) }
        val entry = withContext(Dispatchers.EDT) {
            ServiceActions(project, node).run {
                reveal()
                perform(action, scope)
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("path", node.path)
                put("action", entry.text)
                put("id", entry.id)
                put("dispatched", true)
            },
        )
    }

    private suspend fun serviceOpen(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val node = withContext(Dispatchers.Default) { tree.find(path) }
        val window = withContext(Dispatchers.EDT) { ServiceActions(project, node).reveal() }
        return ToolResult.toon(
            buildJsonObject {
                put("path", node.path)
                put("opened", true)
                put("window", window)
            },
        )
    }

    companion object {

        private const val DEFAULT_MAX = 200
        private const val FILTER_WALK_CEILING = 5_000

        val SERVICES = ToolSpec(
            "services",
            "The Services tree as the user sees it, flattened in tree order: every node's path (Contributor/parent/child), " +
                "name, kind (the contributing plugin's class) and state (the grey text beside it, e.g. running or exited). " +
                "Use it before any other services tool: the path is how a node is named to them. " +
                "filter keeps only nodes whose path, state or kind contains the text.",
            listOf(
                Param("max", "Maximum nodes to return (default $DEFAULT_MAX)", type = "integer", required = false),
                Param("filter", "Case-insensitive text a node's path, state or kind must contain", required = false),
            ),
        )

        val SERVICE_ACTIONS = ToolSpec(
            "service_actions",
            "The actions the IDE offers on one Services node — its toolbar and context menu, flattened — with the action id, " +
                "the text the user sees and whether it is enabled right now. Dynamic submenus the IDE builds on demand are not " +
                "expanded. Use it to learn what service_action can do on a node.",
            listOf(
                Param("path", "The node's path as services lists it"),
                Param("max", "Maximum actions to return (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )

        val SERVICE_ACTION = ToolSpec(
            "service_action",
            "Performs one of the IDE's own actions on a Services node — start, stop, restart, show logs, attach, delete, " +
                "whatever service_actions listed — exactly as clicking it in the Services tool window would, with the node " +
                "selected. Use it once service_actions shows the action enabled. It returns as soon as the action is dispatched; " +
                "a dialog the action opens is the user's to finish.",
            listOf(
                Param("path", "The node's path as services lists it"),
                Param("action", "The action's id or its text, as service_actions lists them"),
            ),
            mutates = true,
        )

        val SERVICE_OPEN = ToolSpec(
            "service_open",
            "Reveals a node in the Services tool window, selecting it and showing the window without taking the focus, " +
                "so the user sees what you are talking about. Use it after finding a node with services.",
            listOf(Param("path", "The node's path as services lists it")),
        )
    }
}
