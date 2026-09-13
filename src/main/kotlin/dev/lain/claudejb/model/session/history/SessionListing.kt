package dev.lain.claudejb.model.session.history

import com.intellij.openapi.project.Project
import dev.lain.claudejb.model.session.transcript.SessionRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

object SessionListing {

    private const val MAX_LISTED_SESSIONS = 30

    data class Metadata(val firstPrompt: String?, val gitBranch: String?, val createdAt: String?)

    fun list(project: Project): List<SessionRef> {
        val base = project.basePath ?: return emptyList()
        return SessionStore.listFiles(base).take(MAX_LISTED_SESSIONS).mapNotNull { path ->
            val id = path.fileName.toString().removeSuffix(".jsonl")
            val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return@mapNotNull null
            val title = SessionTitleReader.pickTitle(lines) ?: id
            val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
            val meta = parseMetadata(lines)
            SessionRef(id, title, mtime, meta.firstPrompt, meta.gitBranch, meta.createdAt)
        }
    }

    fun parseMetadata(lines: List<String>): Metadata {
        val acc = MetadataAccumulator()
        for (line in lines) {
            acc.absorb(SessionTranscriptReader.parseRecord(line) ?: continue)
            if (acc.isComplete) break
        }
        return acc.build()
    }

    private class MetadataAccumulator {
        private var firstPrompt: String? = null
        private var branch: String? = null
        private var createdAt: String? = null

        val isComplete: Boolean get() = firstPrompt != null && branch != null && createdAt != null

        fun absorb(obj: JsonObject) {
            if (branch == null) branch = obj.nonBlank("gitBranch")
            if (createdAt == null) createdAt = obj.nonBlank("timestamp")
            if (firstPrompt == null && obj["type"]?.jsonPrimitive?.contentOrNull == "user") {
                firstPrompt = firstUserText(obj)
            }
        }

        fun build() = Metadata(firstPrompt, branch, createdAt)

        private fun JsonObject.nonBlank(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun firstUserText(obj: JsonObject): String? {
        val content = (obj["message"] as? JsonObject)?.get("content") ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotBlank() }

            is JsonArray -> content.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            else -> null
        }
    }
}
