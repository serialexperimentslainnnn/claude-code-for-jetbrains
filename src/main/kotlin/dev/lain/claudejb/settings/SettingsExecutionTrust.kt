package dev.lain.claudejb.settings

import com.intellij.ide.util.PropertiesComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// --- Trust gate (trust-on-open) -------------------------------------------------------------
// Lightweight, non-blocking consent flag for the two settings that execute code at session start:
// sourceScript and a custom stdio MCP server. These helpers only read and store the flag and classify the
// config; they NEVER show dialogs. The "ask the user" wiring lives elsewhere (e.g. ClaudeSession / a startup
// activity), which should call requiresTrustPrompt().
//
// NB since 5.5.0 the settings are GLOBAL — this is no longer "the project's claude-code.xml", and the config
// being judged may equally be one the user typed into Settings themselves. What keeps the gate meaningful is
// that such a config can still ORIGINATE in a repository: LegacyProjectSettings adopts a committed
// claude-code.xml when nothing has been stored yet. So the flag stays per PROJECT (PropertiesComponent) while
// the settings document is global — deliberately the one subject that does not live in the document at all —
// and consent is asked again in each project, which is the conservative side of that mismatch.
//
// Extension functions on ClaudeSettings rather than methods on it, for the same reason: the flag is not part
// of the document.

private const val TRUST_KEY = "claudejb.trustedExecOnOpen"

private val LENIENT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Per-project flag: the user has explicitly trusted this project to run sourceScript / custom MCP. */
fun ClaudeSettings.isExecutionTrusted(): Boolean =
    project?.let { PropertiesComponent.getInstance(it).getBoolean(TRUST_KEY, false) } ?: false

/** Persists the per-project trust flag. No-op without a project (unit tests). */
fun ClaudeSettings.setExecutionTrusted(trusted: Boolean) {
    project?.let { PropertiesComponent.getInstance(it).setValue(TRUST_KEY, trusted) }
}

/**
 * True when the persisted settings carry execution risk beyond what the UI already validates:
 * a non-blank [ClaudeSettings.State.sourceScript], or a custom MCP server of `stdio` type with a `command`.
 * The custom-server JSON is parsed leniently; if it does not parse, it is treated as adding no
 * extra risk here (the settings UI validates that JSON on save).
 *
 * **Not [ClaudeSettings.State.permissionMode], on purpose.** A weak permission mode is also a security
 * setting a repository could once supply, but it is not *execution* and this gate is the wrong shape for it:
 * the flag is per project, so putting the mode here would re-ask a user who chose `bypassPermissions`
 * deliberately, in every project, forever. A repository-supplied mode is refused at the only moment it can
 * arrive from a repository at all — the legacy migration — by [LegacyPermissionMode].
 */
fun ClaudeSettings.hasRiskyExecConfig(): Boolean =
    state.sourceScript.isNotBlank() || customMcpServersHaveStdioCommand()

/** Convenience: there is risky config and the user has not (yet) trusted it. */
fun ClaudeSettings.requiresTrustPrompt(): Boolean = hasRiskyExecConfig() && !isExecutionTrusted()

/** Lenient scan of the custom MCP servers JSON for any `stdio` server carrying a `command`. */
private fun ClaudeSettings.customMcpServersHaveStdioCommand(): Boolean {
    val raw = state.customMcpServers.trim()
    if (raw.isEmpty()) return false
    val root = runCatching { LENIENT_JSON.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
    return root.values.any { server ->
        val obj = server as? JsonObject ?: return@any false
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val command = obj["command"]?.jsonPrimitive?.contentOrNull
        // stdio is the default transport when unspecified; flag it whenever a command is present.
        !command.isNullOrBlank() && (type == null || type.equals("stdio", ignoreCase = true))
    }
}
