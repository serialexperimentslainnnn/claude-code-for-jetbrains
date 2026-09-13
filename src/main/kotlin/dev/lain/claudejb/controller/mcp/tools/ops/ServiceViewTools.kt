package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import dev.lain.claudejb.controller.mcp.FocusKeeper
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolException
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.concurrency.Promise
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal class ServiceViewTools(private val project: Project, scope: CoroutineScope) {

    private class Event(val seq: Long, val at: String, val type: String, val target: String, val contributor: String)

    private val tree = ServiceTree(project)
    private val events = ArrayDeque<Event>()
    private val seq = AtomicLong()

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(
            ServiceEventListener.TOPIC,
            ServiceEventListener { event ->
                val row = Event(
                    seq.incrementAndGet(),
                    Instant.now().toString(),
                    event.type.name,
                    event.target.toString(),
                    event.contributorClass.simpleName,
                )
                synchronized(events) {
                    events += row
                    while (events.size > RING) events.removeFirst()
                }
            },
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { connection.disconnect() }
    }

    fun domain(): ToolDomain = ToolDomain(
        "service_view",
        "The Services tool window in depth: the text of a node's console, expanding or extracting a node, and the events " +
            "the IDE raised on services since the session started",
        listOf(
            Tool(SERVICE_DATA, ::data),
            Tool(SERVICE_EXTRACT, ::extract),
            Tool(SERVICE_EXPAND, ::expand),
            Tool(SERVICE_EVENTS, ::events),
        ),
    )

    private suspend fun data(args: ToolArgs): ToolResult {
        val path = args.string("path")
        val tail = args.int("tail", DEFAULT_TAIL)
        val node = withContext(Dispatchers.Default) { tree.find(path) }
        val text = withContext(Dispatchers.EDT) {
            val component = node.descriptor.contentComponent ?: return@withContext null
            UIUtil.findComponentsOfType(component, EditorComponentImpl::class.java).map { it.editor.document.text }.joinToString("\n")
        }
        val lines = text?.lines()?.filter { it.isNotEmpty() }.orEmpty()
        return ToolResult.toon(
            buildJsonObject {
                put("path", node.path)
                put("available", text != null)
                put("lines", lines.size)
                put("text", lines.takeLast(tail).joinToString("\n"))
            },
        )
    }

    private suspend fun extract(args: ToolArgs): ToolResult =
        manage(args, "extract") { manager, node -> manager.extract(node.value, node.root.javaClass) }

    private suspend fun expand(args: ToolArgs): ToolResult =
        manage(args, "expand") { manager, node -> manager.expand(node.value, node.root.javaClass) }

    private suspend fun manage(args: ToolArgs, verb: String, act: (ServiceViewManager, ServiceNode) -> Promise<Void>): ToolResult {
        val path = args.string("path")
        val node = withContext(Dispatchers.Default) { tree.find(path) }
        val done = CompletableDeferred<Unit>()
        withContext(Dispatchers.EDT) {
            FocusKeeper.keeping(project) {
                val manager = ServiceViewManager.getInstance(project)
                manager.select(node.value, node.root.javaClass, true, false)
                    .thenAsync { act(manager, node) }
                    .onSuccess { done.complete(Unit) }
                    .onError { done.completeExceptionally(ToolException("the Services view could not $verb ${node.path}: ${it.message}")) }
            }
        }
        withTimeoutOrNull(TIMEOUT_MILLIS) { done.await() } ?: throw ToolException("the Services view did not $verb ${node.path} in time")
        return ToolResult.toon(
            buildJsonObject {
                put("path", node.path)
                put(verb, true)
            },
        )
    }

    private suspend fun events(args: ToolArgs): ToolResult {
        val since = args.int("since", 0).toLong()
        val max = args.int("max", DEFAULT_MAX)
        val rows = synchronized(events) { events.filter { it.seq > since } }
        return ToolResult.toon(
            buildJsonObject {
                put("since", since)
                put("last", rows.lastOrNull()?.seq ?: since)
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("events", buildJsonArray { rows.takeLast(max).forEach { add(row(it)) } })
            },
        )
    }

    private fun row(event: Event): JsonObject = buildJsonObject {
        put("seq", event.seq)
        put("at", event.at)
        put("type", event.type)
        put("target", event.target)
        put("contributor", event.contributor)
    }

    companion object {

        private const val RING = 500
        private const val DEFAULT_TAIL = 200
        private const val DEFAULT_MAX = 100
        private const val TIMEOUT_MILLIS = 10_000L

        private val PATH = Param("path", "The node's path as services lists it")

        val SERVICE_DATA = ToolSpec(
            "service_data",
            "The text of a Services node's detail panel when it holds a console or an editor (a container's log, a run's " +
                "output, a database session): the last tail lines. available=false when the node shows no text.",
            listOf(PATH, Param("tail", "Lines from the end (default $DEFAULT_TAIL)", type = "integer", required = false)),
        )

        val SERVICE_EXTRACT = ToolSpec(
            "service_extract",
            "Extracts a Services node into its own tab of the tool window, as the view's Extract does, without focus.",
            listOf(PATH),
            mutates = true,
        )

        val SERVICE_EXPAND = ToolSpec(
            "service_expand",
            "Expands a Services node in the tree so its children show, without focus.",
            listOf(PATH),
            mutates = true,
        )

        val SERVICE_EVENTS = ToolSpec(
            "service_events",
            "The events the IDE raised on services since this server started (added, removed, changed, reset), each with a " +
                "sequence number; pass since to get only what happened after the last one you saw.",
            listOf(
                Param("since", "Sequence number of the last event already seen (default 0)", type = "integer", required = false),
                Param("max", "Maximum events (default $DEFAULT_MAX)", type = "integer", required = false),
            ),
        )
    }
}
