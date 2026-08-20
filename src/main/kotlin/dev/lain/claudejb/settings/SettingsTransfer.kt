package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Moving a project's configuration somewhere else: to a file the user picks, or from another IDE on this
 * machine.
 *
 * **Why it has to exist.** JetBrains' own *Import Settings* copies the other IDE's configuration directory —
 * its option XMLs and its plugins — and does not touch the system keychain. That did not matter while these
 * settings were one global document; since 6.0 they are one per IDE installation per project, so a freshly
 * imported PyCharm starts on an empty scope and inherits only the pre-6.0 shared document. Both halves here
 * close that hole, and each is a gesture the user makes rather than something that happens to them: silently
 * adopting another IDE's configuration is exactly how somebody ends up unable to explain why this project
 * behaves differently.
 *
 * **The two halves are not symmetric, on purpose.** A file leaves the machine, so [WITHHELD] never travels
 * in one. A scope-to-scope copy never leaves the keychain — same user, same machine, read from one encrypted
 * entry and written to another — so everything travels.
 */
internal object SettingsTransfer {

    const val FORMAT = 1

    const val FILE_NAME = "claude-code-settings.json"

    const val EXTENSION = "json"

    /**
     * Fields that never go into an exported file.
     *
     * `envVars` is where an API key or a credentialed proxy URL ends up, and it is the whole reason this
     * configuration lives in the keychain instead of in `.idea/`. An export carrying it would turn
     * "encrypted by the operating system" into "a JSON in the Downloads folder". Provider keys and Git host
     * tokens need no entry here: they have never been in this document, they are keychain entries of their
     * own and there is nothing to strip.
     */
    val WITHHELD = setOf("envVars")

    /** What a scope-to-scope copy can be narrowed to. */
    enum class Part(val label: String) {
        GENERAL("General settings"),
        GUARD("Sensitive Guard settings and whitelists"),
        ALERT_LOG("Sensitive Guard alert history"),
    }

    /** Everything the security page owns; [Part.GENERAL] is defined as the rest, so a new field lands there. */
    private val GUARD_FIELDS = setOf(
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

    private val log = logger<SettingsTransfer>()

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

    /**
     * Reads an exported file, or `null` when it is not one.
     *
     * [WITHHELD] is dropped on the way in as well as on the way out: a file is now something that can arrive
     * from anywhere, so it must not be able to set the field the export refuses to write. The permission
     * mode gets the same refusal a legacy document gets, for the same reason.
     */
    fun import(body: String): ClaudeSettings.State? {
        val root = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val settings = root[KEY_SETTINGS] as? JsonObject ?: return null
        val clean = JsonObject(settings.filterKeys { it !in WITHHELD })
        val state = runCatching { JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), clean) }
            .getOrNull() ?: return null
        return withoutWeakenedSecurity(state)
    }

    /**
     * Copies [parts] of [from] onto [to], both of them keychain entries of this same user.
     *
     * The document is merged rather than replaced: asking for the guard's settings and getting somebody
     * else's model and executable paths as well would be a different feature. What is not asked for is left
     * exactly as it is.
     */
    fun copyScope(from: SettingsScope, to: SettingsScope, parts: Set<Part>): Boolean {
        var copied = false
        val documentParts = parts - Part.ALERT_LOG
        if (documentParts.isNotEmpty()) copied = copyDocument(from, to, documentParts)
        if (Part.ALERT_LOG in parts) {
            read(from.guardLogName)?.let {
                SecretStore.set(to.guardLogName, it)
                copied = true
            }
        }
        return copied
    }

    /** Whether [scope] has anything worth offering — what stops the dialog listing projects it cannot copy. */
    fun holdsSettings(scope: SettingsScope): Boolean = read(scope.secretName) != null

    private fun copyDocument(from: SettingsScope, to: SettingsScope, parts: Set<Part>): Boolean {
        val source = read(from.secretName)?.let { parse(it) } ?: return false
        val target = read(to.secretName)?.let { parse(it) } ?: encode(ClaudeSettings.State())
        val wanted = parts.flatMapTo(mutableSetOf()) { part ->
            when (part) {
                Part.GUARD -> GUARD_FIELDS
                Part.GENERAL -> source.keys - GUARD_FIELDS
                Part.ALERT_LOG -> emptySet()
            }
        }
        val merged = JsonObject(target + source.filterKeys { it in wanted })
        val document = JSON.encodeToString(JsonObject.serializer(), merged)
        return SecretStore.setVerified(to.secretName, document)
    }

    /**
     * A file is something that can arrive from anywhere, so it gets the refusal a project file gets.
     *
     * Its own message rather than [LegacySettingsNotice]'s: that one names `.idea/claude-code.xml` as the
     * source and tells the user what to do about a repository, which is a different situation with the same
     * mechanism.
     */
    private fun withoutWeakenedSecurity(state: ClaudeSettings.State): ClaudeSettings.State {
        if (!LegacyPermissionMode.weakensSecurity(state.permissionMode)) return state
        log.warn(
            "not importing the permission mode '${state.permissionMode}': an imported file does not get to " +
                "decide how much Claude Code asks — keeping '${LegacyPermissionMode.SAFE}'",
        )
        state.permissionMode = LegacyPermissionMode.SAFE
        return state
    }

    private fun read(name: String): String? = runCatching { SecretStore.get(name) }.getOrNull()

    private fun parse(body: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun encode(state: ClaudeSettings.State): JsonObject =
        JSON.encodeToJsonElement(ClaudeSettings.State.serializer(), state).jsonObject

    private const val KEY_FORMAT = "format"

    private const val KEY_SETTINGS = "settings"
}
