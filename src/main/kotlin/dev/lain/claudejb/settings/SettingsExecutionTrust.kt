package dev.lain.claudejb.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val LENIENT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun ClaudeSettings.isExecutionTrusted(): Boolean = state.executionTrusted

fun ClaudeSettings.setExecutionTrusted(trusted: Boolean) {
    update { it.executionTrusted = trusted }
}

fun ClaudeSettings.hasRiskyExecConfig(): Boolean =
    state.sourceScript.isNotBlank() || customMcpServersHaveStdioCommand()

fun ClaudeSettings.requiresTrustPrompt(): Boolean = hasRiskyExecConfig() && !isExecutionTrusted()

private fun ClaudeSettings.customMcpServersHaveStdioCommand(): Boolean {
    val raw = state.customMcpServers.trim()
    if (raw.isEmpty()) return false
    val root = runCatching { LENIENT_JSON.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
    return root.values.any { server ->
        val obj = server as? JsonObject ?: return@any false
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val command = obj["command"]?.jsonPrimitive?.contentOrNull
        !command.isNullOrBlank() && (type == null || type.equals("stdio", ignoreCase = true))
    }
}
