package dev.lain.claudejb.process

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.annotations.TestOnly
import java.io.File

object ApiKeyApproval {

    private val log = thisLogger()

    private const val SUFFIX_LENGTH = 20

    private const val RESPONSES = "customApiKeyResponses"
    private const val APPROVED = "approved"
    private const val REJECTED = "rejected"

    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Json { prettyPrint = true }

    @TestOnly
    @Volatile
    internal var homeOverride: File? = null

    fun configFile(): File =
        File(homeOverride ?: File(System.getProperty("user.home").orEmpty()), ".claude.json")

    fun suffixOf(key: String): String = key.takeLast(SUFFIX_LENGTH)

    fun approve(key: String): Boolean {
        if (inert()) return false
        val suffix = suffixOf(key).takeIf { it.isNotBlank() } ?: return false
        val file = configFile()
        val root = readConfig(file) ?: return false

        val responses = root[RESPONSES] as? JsonObject
        val approved = (responses?.get(APPROVED) as? JsonArray).orEmpty()
        if (approved.any { it is JsonPrimitive && it.content == suffix }) return true

        val updated = buildJsonObject {
            root.forEach { (k, v) -> if (k != RESPONSES) put(k, v) }
            put(RESPONSES, withApproval(responses, approved, suffix))
        }
        return runCatching {
            file.writeText(writer.encodeToString(JsonObject.serializer(), updated))
            true
        }.getOrElse {
            log.warn("could not record the API key approval", it)
            false
        }
    }

    private fun withApproval(responses: JsonObject?, approved: List<JsonElement>, suffix: String) =
        buildJsonObject {
            responses?.forEach { (k, v) -> if (k != APPROVED && k != REJECTED) put(k, v) }
            put(
                APPROVED,
                buildJsonArray {
                    approved.forEach { add(it) }
                    add(JsonPrimitive(suffix))
                },
            )
            put(
                REJECTED,
                buildJsonArray {
                    (responses?.get(REJECTED) as? JsonArray).orEmpty()
                        .filterNot { it is JsonPrimitive && it.content == suffix }
                        .forEach { add(it) }
                },
            )
        }

    internal fun inert(): Boolean =
        homeOverride == null && ApplicationManager.getApplication()?.isUnitTestMode != false

    internal fun readConfig(): JsonObject? = readConfig(configFile())

    internal fun writeConfig(root: JsonObject): Boolean = runCatching {
        configFile().writeText(writer.encodeToString(JsonObject.serializer(), root))
        true
    }.getOrElse {
        log.warn("could not write ~/.claude.json", it)
        false
    }

    private fun readConfig(file: File): JsonObject? {
        if (!file.isFile) return null
        return runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrElse {
            log.warn("~/.claude.json is not readable JSON — leaving it untouched", it)
            null
        }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
