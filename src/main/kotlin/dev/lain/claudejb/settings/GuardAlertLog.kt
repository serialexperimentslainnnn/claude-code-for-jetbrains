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

    const val KEEP_UNTIL_FULL = 0

    fun record(scope: SettingsScope, alert: GuardAlert, retentionDays: Int = KEEP_UNTIL_FULL): Future<*>? {
        if (SecretStore.inert()) return null
        return writes.submit {
            runCatching {
                val kept = retained(read(scope) + alert, retentionDays, alert.at).takeLast(MAX_ENTRIES)
                SecretStore.set(scope.guardLogName, JSON.encodeToString(ListSerializer, kept))
            }.onFailure { log.warn("could not record a guard alert", it) }
        }
    }

    internal fun retained(alerts: List<GuardAlert>, retentionDays: Int, nowMillis: Long): List<GuardAlert> {
        if (retentionDays <= KEEP_UNTIL_FULL) return alerts
        val oldest = nowMillis - retentionDays.toLong() * MILLIS_PER_DAY
        return alerts.filter { it.at >= oldest }
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

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
