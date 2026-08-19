package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger
import dev.lain.claudejb.session.PluginAgentIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object SettingsStore {

    private val log = logger<SettingsStore>()
    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Synchronized
    fun load(): ClaudeSettings.State {
        if (SecretStore.inert()) {
            readFailed = false
            return ClaudeSettings.State()
        }
        val stored = runCatching { SecretStore.get(SecretStore.SETTINGS_JSON) }
        readFailed = stored.isFailure
        stored.onFailure { log.warn("could not read the settings from the password safe", it) }
        stored.getOrNull()?.let { body ->
            val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            if (obj != null) return decode(obj)
            log.warn("the stored settings are not readable JSON; using defaults")
            readFailed = true
            return ClaudeSettings.State()
        }
        if (stored.isFailure) return ClaudeSettings.State()
        return adoptFile()
    }

    private fun adoptFile(): ClaudeSettings.State {
        val file = file() ?: return ClaudeSettings.State()
        val body = runCatching { Files.readString(file) }.getOrNull()
        if (body.isNullOrBlank()) return ClaudeSettings.State()
        val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ClaudeSettings.State().also { keepUnreadable(file) }
        val state = decode(obj)
        state.envVars = runCatching { SecretStore.get(SecretStore.ENV_VARS) }.getOrNull().orEmpty()
        if (!save(state)) {
            log.warn("keeping $file: the password safe did not accept the settings")
            return state
        }
        runCatching { Files.delete(file) }
            .onSuccess { log.info("adopted the settings from $file into the password safe and removed the file") }
            .onFailure { log.warn("could not remove the migrated settings file $file", it) }
        return state
    }

    @Volatile
    private var readFailed = false

    private fun keepUnreadable(file: Path) {
        log.warn("settings file is not readable JSON; using defaults and keeping it as ${file.fileName}.unreadable")
        runCatching {
            Files.move(file, file.resolveSibling(file.fileName.toString() + ".unreadable"), REPLACE_EXISTING)
        }.onFailure { log.warn("could not set the unreadable settings file aside", it) }
    }

    @Synchronized
    fun save(state: ClaudeSettings.State): Boolean {
        if (SecretStore.inert()) {
            log.debug("not saving the settings: no credential store is installed in this JVM")
            return false
        }
        if (readFailed) {
            log.warn("not saving the settings: they could not be read this run, and defaults must not replace them")
            return false
        }
        val document = JSON.encodeToString(JsonObject.serializer(), encode(state))
        val stored = SecretStore.setVerified(SecretStore.SETTINGS_JSON, document)
        if (!stored) {
            SafeAlarm.storeFailed()
            return false
        }
        runCatching { SecretStore.clear(SecretStore.ENV_VARS) }
        return true
    }

    @Synchronized
    fun mutate(delta: (ClaudeSettings.State) -> Unit): Boolean {
        val stored = load()
        if (readFailed) {
            log.warn("not applying the settings change: the stored settings could not be read this run")
            return false
        }
        delta(stored)
        return save(stored)
    }

    @Synchronized
    fun loadOrNull(): ClaudeSettings.State? = load().takeUnless { readFailed }

    @Synchronized
    fun migrateFrom(legacy: ClaudeSettings.State): Boolean {
        if (exists()) return false
        val adoptable = copyOf(withoutWeakenedSecurity(legacy)).also { LegacySecurityToggles.adopt(it) }
        if (encode(adoptable) == encode(ClaudeSettings.State())) {
            log.info("no legacy settings to migrate (the project carries none)")
            return false
        }
        save(adoptable)
        log.info("migrated plugin settings from the project's claude-code.xml into the password safe")
        return exists()
    }

    private fun withoutWeakenedSecurity(legacy: ClaudeSettings.State): ClaudeSettings.State {
        if (!LegacyPermissionMode.weakensSecurity(legacy.permissionMode)) return legacy
        LegacySettingsNotice.permissionModeRefused(legacy.permissionMode)
        return copyOf(legacy).apply { permissionMode = LegacyPermissionMode.SAFE }
    }

    private fun copyOf(state: ClaudeSettings.State): ClaudeSettings.State =
        JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), encode(state))

    fun exists(): Boolean = runCatching { SecretStore.get(SecretStore.SETTINGS_JSON) != null }.getOrDefault(false)

    private fun file(): Path? = PluginAgentIndex.homeDir()?.let { Paths.get(it) }
        ?.resolve("ide")?.resolve("claude-code-native")?.resolve("settings.json")

    private fun encode(s: ClaudeSettings.State): JsonObject =
        JSON.encodeToJsonElement(ClaudeSettings.State.serializer(), s).jsonObject

    private fun decode(o: JsonObject): ClaudeSettings.State =
        runCatching { JSON.decodeFromJsonElement(ClaudeSettings.State.serializer(), o) }
            .getOrElse {
                log.warn("stored settings did not decode; using defaults", it)
                readFailed = true
                ClaudeSettings.State()
            }
            .also { LegacySecurityToggles.adopt(it) }
}
