package dev.lain.claudejb.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class PluginAgentIndex {

    private val log = logger<PluginAgentIndex>()

    object Kind {
        const val AGENT = "agent"
        const val SUBAGENT = "subagent"
        const val TASK = "backgroundtask"
        const val CHAT = "chat"
    }

    @Serializable
    data class Ref(val type: String, val id: String)

    @Serializable
    data class Node(
        val type: String,
        val id: String,
        val parent: Ref? = null,
        val childs: List<Ref> = emptyList(),
        val agentType: String? = null,
        val toolUseId: String? = null,
        val open: Boolean = true,
        val closedByUser: Boolean = false,
    )

    @Serializable
    data class SessionRecord(val nodes: List<Node> = emptyList())

    @Serializable
    data class Index(
        val version: Int = FORMAT_VERSION,
        val sessions: Map<String, SessionRecord> = emptyMap(),
    )

    private val cache = LinkedHashMap<String, SessionRecord>()
    private var loaded = false

    @Synchronized
    fun admit(sessionId: String, node: AgentNode) {
        val id = AgentMeta.bareAgentId(node.agentId)
        val parentId = node.parentAgentId?.let { AgentMeta.bareAgentId(it) }
        upsert(
            sessionId,
            id,
        ) { existing ->
            Node(
                type = if (parentId == null) Kind.AGENT else Kind.SUBAGENT,
                id = id,
                parent = parentId?.let { Ref(parentTypeOf(sessionId, it), it) } ?: Ref(Kind.CHAT, sessionId),
                agentType = node.meta.agentType,
                open = existing?.open ?: true,
                closedByUser = existing?.closedByUser ?: false,
            )
        }
    }

    @Synchronized
    fun admittedAgents(sessionId: String): List<String> = agents(sessionId).map { it.id }

    @Synchronized
    fun openAgents(sessionId: String): List<String> =
        agents(sessionId).filter { it.open && !it.closedByUser }.map { it.id }

    @Synchronized
    fun nodes(sessionId: String): List<Node> = session(sessionId).nodes

    @Synchronized
    fun setTabOpen(sessionId: String, agentId: String, open: Boolean) {
        val id = AgentMeta.bareAgentId(agentId)
        upsert(sessionId, id) { existing ->
            (existing ?: Node(type = Kind.AGENT, id = id, parent = Ref(Kind.CHAT, sessionId)))
                .copy(open = open, closedByUser = !open)
        }
    }

    @Synchronized
    fun recordTask(sessionId: String, taskId: String, toolUseId: String?, ownerAgentId: String?) {
        val owner = ownerAgentId?.let { AgentMeta.bareAgentId(it) }
        upsert(sessionId, taskId) { existing ->
            Node(
                type = Kind.TASK,
                id = taskId,
                parent = owner?.let { Ref(parentTypeOf(sessionId, it), it) }
                    ?: existing?.parent
                    ?: Ref(Kind.CHAT, sessionId),
                toolUseId = toolUseId ?: existing?.toolUseId,
                open = existing?.open ?: true,
                closedByUser = existing?.closedByUser ?: false,
            )
        }
    }

    @TestOnly
    @Synchronized
    fun taskIds(sessionId: String): List<String> =
        session(sessionId).nodes.filter { it.type == Kind.TASK }.map { it.id }

    @Synchronized
    fun forget(sessionId: String) {
        if (load().remove(sessionId) != null) flush()
    }

    private fun agents(sessionId: String): List<Node> =
        session(sessionId).nodes.filter { it.type == Kind.AGENT || it.type == Kind.SUBAGENT }

    private fun parentTypeOf(sessionId: String, parentId: String): String =
        session(sessionId).nodes.firstOrNull { it.id == parentId }?.type ?: Kind.AGENT

    private fun upsert(sessionId: String, id: String, build: (Node?) -> Node) {
        val session = session(sessionId)
        val nodes = session.nodes.toMutableList()
        val i = nodes.indexOfFirst { it.id == id }
        val next = build(nodes.getOrNull(i))
        if (i >= 0 && nodes[i] == next) return
        if (i >= 0) nodes[i] = next else nodes += next
        cache[sessionId] = SessionRecord(nodes)
        flush()
    }

    private fun session(sessionId: String): SessionRecord =
        load().getOrPut(sessionId) { SessionRecord() }

    private fun load(): LinkedHashMap<String, SessionRecord> {
        if (!loaded) {
            cache.clear()
            val body = indexFile()?.let { f -> runCatching { Files.readString(f) }.getOrNull() }.orEmpty()
            cache.putAll(decode(body))
            loaded = true
            if (body.isNotBlank() && !body.contains("\"version\":$FORMAT_VERSION")) flush()
        }
        return cache
    }

    private fun flush() {
        val file = indexFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, encode(cache))
        }.onFailure {
            log.warn("could not persist the agent index to ${file.parent}", it)
        }
    }

    private fun indexFile(): Path? = homeDir()?.let { Paths.get(it) }
        ?.let { it.resolve(DIR_IDE).resolve(DIR_PLUGIN).resolve(FILE) }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        const val FORMAT_VERSION = 3

        private const val DIR_IDE = "ide"
        private const val DIR_PLUGIN = "claude-code-native"
        private const val FILE = "agent-index.json"

        @Volatile
        var homeOverride: String? = defaultHome()

        private fun defaultHome(): String? =
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { "$it/.claude" }

        internal fun homeDir(): String? {
            homeOverride?.let { if (it != defaultHome()) return it }
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            return if (app == null || app.isUnitTestMode) null else homeOverride
        }

        fun getInstance(project: Project): PluginAgentIndex = project.service()

        fun encode(sessions: Map<String, SessionRecord>): String {
            val withChildren = sessions.mapValues { (_, rec) ->
                SessionRecord(
                    rec.nodes.map { node ->
                        node.copy(
                            childs = rec.nodes
                                .filter { it.parent?.id == node.id }
                                .map { Ref(it.type, it.id) },
                        )
                    },
                )
            }
            return runCatching { JSON.encodeToString(Index(FORMAT_VERSION, withChildren)) }.getOrDefault("")
        }

        fun decode(text: String): LinkedHashMap<String, SessionRecord> {
            val out = LinkedHashMap<String, SessionRecord>()
            if (text.isBlank()) return out
            runCatching { JSON.decodeFromString<Index>(text) }.getOrNull()?.let { index ->
                if (index.sessions.isNotEmpty()) {
                    index.sessions.forEach { (id, rec) -> out[id] = rec.normalised(id) }
                    return out
                }
            }
            runCatching { JSON.decodeFromString<Map<String, List<LegacyRecord>>>(text) }.getOrNull()
                ?.forEach { (id, legacy) ->
                    out[id] = SessionRecord(
                        legacy.map {
                            Node(
                                type = Kind.AGENT,
                                id = AgentMeta.bareAgentId(it.agentId),
                                parent = Ref(Kind.CHAT, id),
                                open = it.open,
                                closedByUser = it.closedByUser,
                            )
                        },
                    ).normalised(id)
                }
            return out
        }

        @Serializable
        private data class LegacyRecord(
            val agentId: String,
            val open: Boolean = true,
            val closedByUser: Boolean = false,
        )

        private fun SessionRecord.normalised(sessionId: String): SessionRecord {
            val seen = LinkedHashMap<String, Node>()
            nodes.forEach { n ->
                val id = if (n.type == Kind.TASK) n.id else AgentMeta.bareAgentId(n.id)
                val parent = n.parent?.let {
                    if (it.type == Kind.CHAT) Ref(Kind.CHAT, sessionId) else Ref(it.type, AgentMeta.bareAgentId(it.id))
                }
                seen.putIfAbsent(id, n.copy(id = id, parent = parent ?: Ref(Kind.CHAT, sessionId)))
            }
            return SessionRecord(seen.values.toList())
        }
    }
}
