package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Future

@Serializable
data class GuardAlert(
    val at: Long,
    val rule: String,
    val category: String,
    val verdict: String,
    val sessionId: String? = null,
    val toolUseId: String? = null,
    val via: String? = null,
    val tool: String? = null,
    val detail: String? = null,
    val command: String? = null,
) {
    companion object {
        const val DENIED = "DENIED"

        const val ASKED = "ASKED"

        const val ALLOWED = "ALLOWED"
    }
}

object GuardAlertLog {

    const val MAX_ENTRIES = 500

    private val log = logger<GuardAlertLog>()

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun record(scope: SettingsScope, alert: GuardAlert): Future<*>? {
        if (SecretStore.inert()) return null
        return writes.submit {
            runCatching {
                val kept = (read(scope) + alert).takeLast(MAX_ENTRIES)
                SecretStore.set(scope.guardLogName, JSON.encodeToString(ListSerializer, kept))
            }.onFailure { log.warn("could not record a guard alert", it) }
        }
    }

    fun forSession(scope: SettingsScope, sessionId: String): List<GuardAlert> =
        read(scope).filter { it.sessionId == sessionId }

    fun clear(scope: SettingsScope) {
        runCatching { SecretStore.clear(scope.guardLogName) }
            .onFailure { log.warn("could not clear the guard alert log", it) }
    }

    private fun read(scope: SettingsScope): List<GuardAlert> {
        val stored = runCatching { SecretStore.get(scope.guardLogName) }.getOrNull() ?: return emptyList()
        return runCatching { JSON.decodeFromString(ListSerializer, stored) }
            .getOrElse {
                log.warn("the stored guard alert log did not decode; starting a new one", it)
                emptyList()
            }
    }

    private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(GuardAlert.serializer())

    private val writes =
        com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude Code guard log", 1)
}
