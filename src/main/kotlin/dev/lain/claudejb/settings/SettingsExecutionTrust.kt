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

/**
 * Whether the user has said this project's source script and stdio MCP servers may run.
 *
 * Kept in the settings document like every other setting, which means the IDE's PasswordSafe. It used to be
 * a `PropertiesComponent` flag in `.idea/workspace.xml` — a security answer in a plaintext file inside the
 * repository, which is the one place this plugin's configuration is not allowed to be. Nobody who answered
 * the prompt before 5.6 is remembered, so the prompt appears once more and the answer lands in the safe.
 */
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
