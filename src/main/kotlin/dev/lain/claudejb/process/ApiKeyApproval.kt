package dev.lain.claudejb.process

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.annotations.TestOnly
import java.io.File

/**
 * Records a user-supplied `ANTHROPIC_API_KEY` as approved, in the binary's own `~/.claude.json`.
 *
 * The binary does not accept an API key from the environment on trust: the first time it sees a new one it
 * ASKS, and remembers the answer in `customApiKeyResponses.approved` as the key's last
 * [SUFFIX_LENGTH] characters. Our sessions run under `--print`, where there is no one to ask — so an
 * unapproved key is refused and the turn fails with an invalid-key error, for a key that works perfectly in
 * a terminal where the question was once answered. That was the whole bug: the key was valid and correctly
 * delivered; it had simply never been approved for a non-interactive session.
 *
 * Writing the approval IS answering that question, and the user answered it by typing the key into the
 * sign-in card. Only the suffix is stored (it is what the CLI stores, and it is not a usable credential);
 * the key itself lives in the IDE's PasswordSafe and reaches the process through the environment.
 */
object ApiKeyApproval {

    private val log = thisLogger()

    /** What the CLI records per approved key — the last 20 characters, not the key. */
    private const val SUFFIX_LENGTH = 20

    private const val RESPONSES = "customApiKeyResponses"
    private const val APPROVED = "approved"
    private const val REJECTED = "rejected"

    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Json { prettyPrint = true }

    /** Test seam — see [CredentialsVault.homeOverride]; this file is the user's real CLI config. */
    @TestOnly
    @Volatile
    internal var homeOverride: File? = null

    /** `~/.claude.json` — the CLI's own config, which we amend rather than replace. */
    fun configFile(): File =
        File(homeOverride ?: File(System.getProperty("user.home").orEmpty()), ".claude.json")

    /** The identifier the CLI matches on. Short keys are returned whole rather than padded. */
    fun suffixOf(key: String): String = key.takeLast(SUFFIX_LENGTH)

    /**
     * Adds [key]'s suffix to the approved list, and removes it from the rejected one so a key that was
     * once declined can be re-supplied without editing JSON by hand.
     *
     * Every other field of the file is preserved verbatim: this is the CLI's config, shared with the user's
     * terminal, and clobbering it would be a far worse bug than the one being fixed. Missing or unparseable
     * file → we do NOT create or overwrite one; a corrupt read must not become a corrupt write.
     *
     * @return true when the file now records the approval.
     */
    fun approve(key: String): Boolean {
        if (inert()) return false
        val suffix = suffixOf(key).takeIf { it.isNotBlank() } ?: return false
        val file = configFile()
        val root = readConfig(file) ?: return false

        val responses = root[RESPONSES] as? JsonObject
        val approved = (responses?.get(APPROVED) as? JsonArray).orEmpty()
        if (approved.any { it is JsonPrimitive && it.content == suffix }) return true

        val updated = buildJsonObject {
            root.forEach { (k, v) -> if (k != RESPONSES) put(k, v) }
            put(RESPONSES, withApproval(responses, approved, suffix))
        }
        return runCatching {
            file.writeText(writer.encodeToString(JsonObject.serializer(), updated))
            true
        }.getOrElse {
            log.warn("could not record the API key approval", it)
            false
        }
    }

    /** `customApiKeyResponses` with [suffix] added to `approved` and removed from `rejected`. */
    private fun withApproval(responses: JsonObject?, approved: List<JsonElement>, suffix: String) =
        buildJsonObject {
            responses?.forEach { (k, v) -> if (k != APPROVED && k != REJECTED) put(k, v) }
            put(
                APPROVED,
                buildJsonArray {
                    approved.forEach { add(it) }
                    add(JsonPrimitive(suffix))
                },
            )
            put(
                REJECTED,
                buildJsonArray {
                    (responses?.get(REJECTED) as? JsonArray).orEmpty()
                        .filterNot { it is JsonPrimitive && it.content == suffix }
                        .forEach { add(it) }
                },
            )
        }

    /**
     * Refuses to touch the developer's real `~/.claude.json` from a test JVM — the same rule, and the same
     * hard-won reason, as [CredentialsVault.inertHere]: this file is the user's live CLI config.
     */
    internal fun inert(): Boolean =
        homeOverride == null && ApplicationManager.getApplication()?.isUnitTestMode != false

    /**
     * `~/.claude.json` parsed, or null when it is absent or not readable JSON. Shared with [ConsoleApiKey],
     * which amends the same file: a corrupt read must never become a corrupt write, and that rule is worth
     * having in exactly one place.
     */
    internal fun readConfig(): JsonObject? = readConfig(configFile())

    /** Writes [root] back over `~/.claude.json`, pretty-printed like the CLI does. */
    internal fun writeConfig(root: JsonObject): Boolean = runCatching {
        configFile().writeText(writer.encodeToString(JsonObject.serializer(), root))
        true
    }.getOrElse {
        log.warn("could not write ~/.claude.json", it)
        false
    }

    private fun readConfig(file: File): JsonObject? {
        if (!file.isFile) return null
        return runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrElse {
            log.warn("~/.claude.json is not readable JSON — leaving it untouched", it)
            null
        }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
