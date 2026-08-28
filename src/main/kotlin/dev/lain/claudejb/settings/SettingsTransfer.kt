package dev.lain.claudejb.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal object SettingsTransfer {

    const val FORMAT = 1

    const val FILE_NAME = "claude-code-settings.json"

    const val EXTENSION = "json"

    val WITHHELD = setOf("envVars")

    enum class Part(val label: String) {
        GENERAL("General settings"),
        GUARD("Sensitive Guard settings and whitelists"),
        ALERT_LOG("Sensitive Guard alert history"),
    }

    private val GUARD_OWNED_FIELDS = setOf(
        "guardMode",
        "guardDisabledUntil",
        "disabledSecurityRules",
        "securityRuleSuspensions",
        "securityExtraBlockedDomains",
        "sensitiveExtraGlobs",
        "securityCommandWhitelist",
        "securityCategoryWhitelists",
        "securityRuleWhitelists",
    )

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun export(state: ClaudeSettings.State): String {
        val body = JsonObject(encode(state).filterKeys { it !in WITHHELD })
        val document = JsonObject(mapOf(KEY_FORMAT to JsonPrimitive(FORMAT), KEY_SETTINGS to body))
        return JSON.encodeToString(JsonObject.serializer(), document)
    }

    fun import(body: String): ClaudeSettings.State? {
        val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val settings = root[KEY_SETTINGS] as? JsonObject ?: return null
        val clean = JsonObject(settings.filterKeys { it !in WITHHELD })
        val state = runCatching { JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), clean) }
            .getOrNull() ?: return null
        return UntrustedState.fromImportedFile(state)
    }

    fun copyScope(from: SettingsScope, to: SettingsScope, parts: Set<Part>): Boolean {
        var copied = false
        val documentParts = parts - Part.ALERT_LOG
        if (documentParts.isNotEmpty()) copied = mergeDocument(from, to, documentParts)
        if (Part.ALERT_LOG in parts) {
            read(from.guardLogName)?.let {
                SecretStore.set(to.guardLogName, it)
                copied = true
            }
        }
        return copied
    }

    fun holdsSettings(scope: SettingsScope): Boolean = read(scope.secretName) != null

    private fun mergeDocument(from: SettingsScope, to: SettingsScope, parts: Set<Part>): Boolean {
        val source = read(from.secretName)?.let { parse(it) } ?: return false
        val target = read(to.secretName)?.let { parse(it) } ?: encode(ClaudeSettings.State())
        val wanted = parts.flatMapTo(mutableSetOf()) { part ->
            when (part) {
                Part.GUARD -> GUARD_OWNED_FIELDS
                Part.GENERAL -> source.keys - GUARD_OWNED_FIELDS
                Part.ALERT_LOG -> emptySet()
            }
        }
        val merged = JsonObject(target + source.filterKeys { it in wanted })
        return SecretStore.setVerified(to.secretName, JSON.encodeToString(JsonObject.serializer(), merged))
    }

    private fun read(name: String): String? = runCatching { SecretStore.get(name) }.getOrNull()

    private fun parse(body: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun encode(state: ClaudeSettings.State): JsonObject =
        JSON.encodeToJsonElement(ClaudeSettings.State.serializer(), state).jsonObject

    private const val KEY_FORMAT = "format"

    private const val KEY_SETTINGS = "settings"
}
