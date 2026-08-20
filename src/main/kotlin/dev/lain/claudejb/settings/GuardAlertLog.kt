package dev.lain.claudejb.settings

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Future

/**
 * One thing the guard decided, written down.
 *
 * Everything is here on purpose, the command verbatim included: a security log that does not say what was
 * attempted can be counted but not audited. It lives encrypted in the IDE's PasswordSafe, which is the
 * distinction that makes recording it acceptable — `PluginAgentIndex`'s "structure, never content" rule is
 * about a plaintext file under the user's home, and this is not that.
 *
 * [toolUseId] is the anchor a restored conversation is rebuilt around: it is the one identifier that also
 * appears in the binary's own JSONL, so a row can be put back exactly where it was.
 */
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
        /** Refused outright. */
        const val DENIED = "DENIED"

        /** Put to the user as a card. Whether they then said yes is a separate entry. */
        const val ASKED = "ASKED"

        /** Ran. [via] says what let it. */
        const val ALLOWED = "ALLOWED"
    }
}

/**
 * Every alert the guard has raised, per IDE installation and project, in the IDE's PasswordSafe.
 *
 * Two jobs, and the second is the reason the first exists at all right now: it is the audit trail a later
 * feature will read, and it is what lets a restored conversation put its guard rows back — the binary's
 * transcript records a denial as an ordinary failed tool result and records a bypass as nothing whatsoever,
 * so without this there is nothing to restore from.
 *
 * A ring of [MAX_ENTRIES], oldest discarded. A keyring is not a database, and an entry that grows without
 * a bound eventually becomes the one the safe refuses to keep — quietly, which is what `SafeAlarm` exists
 * to shout about.
 */
object GuardAlertLog {

    const val MAX_ENTRIES = 500

    private val log = logger<GuardAlertLog>()

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /**
     * Appends one alert, off the calling thread.
     *
     * It has to be off it: the guard decides on the thread that reads the binary's entire stdout, and
     * nothing may block there — a synchronous keychain write froze the whole transcript once already. One
     * serial executor rather than a pooled thread per call, so the log stays in the order things happened
     * and two writes never read-modify-write over each other.
     */
    fun record(scope: SettingsScope, alert: GuardAlert): Future<*>? {
        if (SecretStore.inert()) return null
        return writes.submit {
            runCatching {
                val kept = (read(scope) + alert).takeLast(MAX_ENTRIES)
                SecretStore.set(scope.guardLogName, JSON.encodeToString(ListSerializer, kept))
            }.onFailure { log.warn("could not record a guard alert", it) }
        }
    }

    /** The alerts raised in one conversation, oldest first — what a restore rebuilds its rows from. */
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
                // A log that will not parse is a log, not a configuration: starting a fresh one loses
                // history and nothing else, which is a better failure than refusing to record anything.
                log.warn("the stored guard alert log did not decode; starting a new one", it)
                emptyList()
            }
    }

    private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(GuardAlert.serializer())

    private val writes =
        com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor("Claude Code guard log", 1)
}
