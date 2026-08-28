package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.PluginAgentIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap

internal object SettingsStore {

    private val log = logger<SettingsStore>()
    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val readFailed = ConcurrentHashMap<String, Boolean>()

    @Synchronized
    fun load(scope: SettingsScope): ClaudeSettings.State {
        readFailed[scope.id] = false
        if (SecretStore.inert()) return ClaudeSettings.State()
        read(scope.secretName, scope)?.let { return it }
        if (failed(scope)) return ClaudeSettings.State()
        read(SecretStore.SETTINGS_JSON, scope)?.let { return it }
        if (failed(scope)) return ClaudeSettings.State()
        return adoptFile(scope)
    }

    private fun read(name: String, scope: SettingsScope): ClaudeSettings.State? {
        val stored = runCatching { SecretStore.get(name) }
        if (stored.isFailure) {
            log.warn("could not read $name from the password safe", stored.exceptionOrNull())
            readFailed[scope.id] = true
            return null
        }
        val body = stored.getOrNull() ?: return null
        val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
        if (obj == null) {
            log.warn("the settings stored under $name are not readable JSON; using defaults")
            readFailed[scope.id] = true
            return null
        }
        return decode(obj, scope)
    }

    private fun adoptFile(scope: SettingsScope): ClaudeSettings.State {
        val file = file() ?: return ClaudeSettings.State()
        val body = runCatching { Files.readString(file) }.getOrNull()
        if (body.isNullOrBlank()) return ClaudeSettings.State()
        val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ClaudeSettings.State().also { keepUnreadable(file) }
        val state = decode(obj, scope)
        state.envVars = runCatching { SecretStore.get(SecretStore.ENV_VARS) }.getOrNull().orEmpty()
        if (!write(SecretStore.SETTINGS_JSON, state)) {
            log.warn("keeping $file: the password safe did not accept the settings")
            return state
        }
        runCatching { Files.delete(file) }
            .onSuccess { log.info("adopted the settings from $file into the password safe and removed the file") }
            .onFailure { log.warn("could not remove the migrated settings file $file", it) }
        return state
    }

    private fun failed(scope: SettingsScope): Boolean = readFailed[scope.id] == true

    private fun keepUnreadable(file: Path) {
        log.warn("settings file is not readable JSON; using defaults and keeping it as ${file.fileName}.unreadable")
        runCatching {
            Files.move(file, file.resolveSibling(file.fileName.toString() + ".unreadable"), REPLACE_EXISTING)
        }.onFailure { log.warn("could not set the unreadable settings file aside", it) }
    }

    @Synchronized
    fun save(scope: SettingsScope, state: ClaudeSettings.State): Boolean {
        if (SecretStore.inert()) {
            log.debug("not saving the settings: no credential store is installed in this JVM")
            return false
        }
        if (failed(scope)) {
            log.warn("not saving the settings: they could not be read this run, and defaults must not replace them")
            return false
        }
        return write(scope.secretName, state)
    }

    private fun write(name: String, state: ClaudeSettings.State): Boolean {
        val document = JSON.encodeToString(JsonObject.serializer(), encode(state))
        if (!SecretStore.setVerified(name, document)) {
            SafeAlarm.storeFailed()
            return false
        }
        runCatching { SecretStore.clear(SecretStore.ENV_VARS) }
        return true
    }

    @Synchronized
    fun mutate(scope: SettingsScope, delta: (ClaudeSettings.State) -> Unit): Boolean {
        val stored = load(scope)
        if (failed(scope)) {
            log.warn("not applying the settings change: the stored settings could not be read this run")
            return false
        }
        delta(stored)
        return save(scope, stored)
    }

    @Synchronized
    fun loadOrNull(scope: SettingsScope): ClaudeSettings.State? = load(scope).takeUnless { failed(scope) }

    @Synchronized
    fun wipe(scope: SettingsScope): Boolean {
        readFailed[scope.id] = false
        return save(scope, ClaudeSettings.State())
    }

    @Synchronized
    fun migrateFrom(scope: SettingsScope, legacy: ClaudeSettings.State): Boolean {
        if (exists(scope) || inheritedExists()) return false
        if (LegacyPermissionMode.weakensSecurity(legacy.permissionMode)) {
            LegacySettingsNotice.permissionModeRefused(legacy.permissionMode)
        }
        val adoptable = UntrustedState.fromProjectFile(copyOf(legacy)).also { LegacySecurityToggles.adopt(it) }
        if (encode(adoptable) == encode(ClaudeSettings.State())) {
            log.info("no legacy settings to migrate (the project carries none)")
            return false
        }
        save(scope, adoptable)
        log.info("migrated plugin settings from the project's claude-code.xml into the password safe")
        return exists(scope)
    }

    private fun copyOf(state: ClaudeSettings.State): ClaudeSettings.State =
        JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), encode(state))

    fun exists(scope: SettingsScope): Boolean =
        runCatching { SecretStore.get(scope.secretName) != null }.getOrDefault(false)

    fun storedAnywhere(scope: SettingsScope): Boolean = exists(scope) || inheritedExists()

    private fun inheritedExists(): Boolean =
        runCatching { SecretStore.get(SecretStore.SETTINGS_JSON) != null }.getOrDefault(false)

    private fun file(): Path? = PluginAgentIndex.homeDir()?.let { Paths.get(it) }
        ?.resolve("ide")?.resolve("claude-code-native")?.resolve("settings.json")

    private fun encode(s: ClaudeSettings.State): JsonObject =
        JSON.encodeToJsonElement(ClaudeSettings.State.serializer(), s).jsonObject

    private fun decode(o: JsonObject, scope: SettingsScope): ClaudeSettings.State =
        runCatching { JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), o) }
            .getOrElse {
                log.warn("stored settings did not decode; using defaults", it)
                readFailed[scope.id] = true
                ClaudeSettings.State()
            }
            .also { LegacySecurityToggles.adopt(it) }
            .also { adoptSignedOut(o) }

    private fun adoptSignedOut(o: JsonObject) {
        if (runCatching { SecretStore.get(SecretStore.SIGNED_OUT) }.getOrNull() != null) return
        val wasSignedOut = runCatching { o[LEGACY_SIGNED_OUT]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: return
        if (!wasSignedOut) return
        runCatching { SecretStore.set(SecretStore.SIGNED_OUT, true.toString()) }
            .onSuccess { log.info("adopted the legacy signedOut flag into its own entry") }
    }

    private const val LEGACY_SIGNED_OUT = "signedOut"
}
