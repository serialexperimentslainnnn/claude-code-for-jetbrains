package dev.lain.claudejb.controller.mcp

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.mcp.McpServer
import dev.lain.claudejb.model.mcp.MetaTools
import dev.lain.claudejb.model.mcp.OutputBudget
import dev.lain.claudejb.model.mcp.TokenRing
import dev.lain.claudejb.model.session.launch.IdeServer
import dev.lain.claudejb.model.session.launch.McpConfigBuilder
import dev.lain.claudejb.model.session.launch.SessionLauncher
import dev.lain.claudejb.model.settings.ClaudeSettings
import dev.lain.claudejb.model.settings.guard.sensitiveDecision
import dev.lain.claudejb.util.PluginIdentity
import dev.lain.claudejb.util.thisLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
internal class IdeMcpService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val log = thisLogger()
    private val tokens = TokenRing()
    private val expected = AtomicInteger()
    private var home: SocketHome? = null
    private var endpoints: List<ServerEndpoint> = emptyList()
    private var rotation: Job? = null
    private var servingWithoutChat = false

    @Synchronized
    fun sockets(): Map<IdeServer, String> {
        start()
        return endpoints.associate { it.server to it.socket.toString() }
    }

    @Synchronized
    fun serveWithoutChat() {
        if (servingWithoutChat) return
        servingWithoutChat = true
        val sockets = runCatching { sockets() }
            .onFailure { log.warn("The chat page could not be shown and the IDE MCP servers could not start either", it) }
            .getOrDefault(emptyMap())
        if (sockets.isEmpty()) return
        val config = McpConfigBuilder.mcpConfigJson("", sockets, SessionLauncher.resolveHelper())
        log.info("the chat page could not be shown; the IDE MCP servers stay reachable under " + home?.dir + ": " + config)
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification(WITHOUT_CHAT_TITLE, WITHOUT_CHAT_TEXT, NotificationType.WARNING)
        if (config != null) {
            val copy = NotificationAction.createSimple("Copy MCP configuration") {
                CopyPasteManager.getInstance().setContents(StringSelection(config))
            }
            notification.addAction(copy)
        }
        notification.notify(project)
    }

    fun expectConnections(count: Int) {
        expected.addAndGet(count)
    }

    private fun start() {
        if (home != null) return
        val home = SocketHome.create(listOf(Path.of(PathManager.getTempPath()), Path.of(System.getProperty("java.io.tmpdir"))))
        home.writeToken(tokens.token)
        val gate = GuardGate { ClaudeSettings.getInstance(project).sensitiveDecision(it, project.basePath) }
        endpoints = IdeServer.entries.mapNotNull { server ->
            val catalog = IdeToolCatalog.catalog(server, project, scope)
            if (catalog.domains.isEmpty()) return@mapNotNull null
            val mcp = McpServer(server.key, PluginIdentity.PLUGIN_VERSION, MetaTools(catalog, gate, OutputBudget()))
            ServerEndpoint(server, home.socket(server), mcp, tokens, scope, ::connected).also { it.start() }
        }
        rotation = scope.launch {
            while (isActive) {
                delay(TokenRing.ROTATION_MILLIS)
                home.writeToken(tokens.rotate())
            }
        }
        this.home = home
        log.info("IDE MCP servers listening under ${home.dir}: ${endpoints.joinToString { it.server.key }}")
    }

    private suspend fun connected(): Boolean {
        if (expected.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) return true
        log.warn("an MCP client connected that no chat tab of this project announced")
        val mustApprove = ClaudeSettings.getInstance(project).state.ideMcp.approveClients
        val verdict = CompletableDeferred<Boolean>()
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(PluginIdentity.NOTIFICATION_GROUP)
            .createNotification(
                "An unknown client connected to the IDE MCP servers",
                if (mustApprove) HELD_TEXT else INFORMED_TEXT,
                NotificationType.WARNING,
            )
        if (mustApprove) {
            notification.addAction(NotificationAction.createSimpleExpiring("Allow") { verdict.complete(true) })
            notification.addAction(NotificationAction.createSimpleExpiring("Reject") { verdict.complete(false) })
            notification.whenExpired { verdict.complete(false) }
        }
        notification.notify(project)
        return !mustApprove || verdict.await()
    }

    override fun dispose() {
        rotation?.cancel()
        endpoints.forEach { it.close() }
        home?.remove()
    }

    companion object {

        const val INFORMED_TEXT = "Something other than this project's chat tabs opened a connection. If that was not you, close the " +
            "project: the sockets and their token die with it."
        const val HELD_TEXT = "Something other than this project's chat tabs opened a connection. It is held until you answer; " +
            "closing this notice rejects it."
        const val WITHOUT_CHAT_TITLE = "The chat could not be shown, but the IDE MCP servers are up"
        const val WITHOUT_CHAT_TEXT = "This is what happens when the IDE runs split between a frontend and a backend: the plugin, " +
            "its sockets and the claude process all live on the backend, where the chat page cannot be drawn. The servers " +
            "listen anyway, so any MCP client on that machine drives the IDE: pass the copied configuration to it as its " +
            "MCP servers (claude takes it with --mcp-config). docs/MCP_CLIENT.md describes the wire."

        fun getInstance(project: Project): IdeMcpService = project.service()
    }
}
