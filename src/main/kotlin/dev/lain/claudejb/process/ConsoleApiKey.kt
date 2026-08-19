package dev.lain.claudejb.process

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ConsoleApiKey {

    private val log = thisLogger()

    private const val PRIMARY_API_KEY = "primaryApiKey"

    fun harvest(): String? {
        if (ApiKeyApproval.inert()) return null
        val root = ApiKeyApproval.readConfig() ?: return null
        val key = root[PRIMARY_API_KEY]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val stripped = buildJsonObject {
            root.forEach { (k, v) -> if (k != PRIMARY_API_KEY) put(k, v) }
        }
        if (!ApiKeyApproval.writeConfig(stripped)) {
            log.warn("could not strip $PRIMARY_API_KEY from ~/.claude.json — leaving the key where the binary put it")
            return null
        }
        return key
    }
}
