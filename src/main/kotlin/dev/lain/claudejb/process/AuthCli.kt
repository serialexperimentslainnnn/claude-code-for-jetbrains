package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The binary's non-interactive auth surface: `claude auth status` and `claude auth logout`.
 *
 * **`auth status` takes NO `--json` flag — it already answers in JSON.** It was being invoked with one, and
 * an unrecognised flag is a non-zero exit, which [run] maps to null: "unknown". So the probe answered nothing
 * at all, which is both the login check and the only place the binary states the account identity — that is
 * why the dashboard's Email and Organization rows were empty while Plan and Provider (which have other
 * sources) filled in. Verified against 2.1.223, the plain command:
 *
 * ```json
 * {"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty",
 *  "email":"…","orgId":"…","orgName":"…","subscriptionType":"max"}
 * ```
 *
 * This is what makes login detection PROACTIVE: without it the plugin only learned about a missing login
 * when a turn had already failed on it. Both calls are BLOCKING (they run a process) — pooled thread only.
 *
 * The [env] parameter matters and is not decoration: with credentials held in the IDE's PasswordSafe the
 * binary is only authenticated when `CLAUDE_CODE_OAUTH_TOKEN`/`ANTHROPIC_API_KEY` are present in its
 * environment, so probing without the session's launch env would report logged-out for a session that is
 * perfectly signed in. Callers pass [dev.lain.claudejb.session.ClaudeSession.effectiveLaunchEnv].
 */
object AuthCli {

    /**
     * What `auth status --json` reports. **Every field it emits is taken**, not a chosen subset: this is the
     * only place the account identity is stated by the binary itself, and the dashboard's account card had
     * an empty Organization row for as long as `orgName` was missing from here. Verified against 2.1.223:
     *
     * ```json
     * {"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty",
     *  "email":"…","orgId":"…","orgName":"…'s Organization","subscriptionType":"max"}
     * ```
     *
     * Lenient: unknown keys ignored, so a new field is a non-event until it is wanted.
     */
    @Serializable
    data class AuthState(
        val loggedIn: Boolean = false,
        val authMethod: String? = null,
        val apiProvider: String? = null,
        val email: String? = null,
        val orgId: String? = null,
        val orgName: String? = null,
        val subscriptionType: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Null when the probe could not run or its output was not parseable — "unknown", never "logged out".
     *
     * A successful reply is filed in the IDE safe ([SecretStore.AUTH_STATUS]) exactly as the binary wrote it.
     * The probe spawns a process, so it cannot run on every dashboard push; [stored] is what the account card
     * reads between probes, and it is the binary's own answer rather than a guess assembled from elsewhere.
     */
    fun status(binary: File, env: Map<String, String>): AuthState? {
        // No `--json`: the command answers JSON on its own, and the flag it does not know is a non-zero exit.
        val output = run(binary, env, "auth", "status") ?: return null
        val state = parse(output) ?: return null
        // Filed ONLY when the reply actually names the account. Asked with our own credential in the
        // environment the binary answers `authMethod: oauth_token` and omits email/orgName — a perfectly
        // valid reply that carries no identity, and letting it overwrite the safe would erase the good one.
        // Verbatim, from the first brace: a re-serialization of our data class would silently drop any field
        // this version does not model yet.
        if (state.email != null || state.orgName != null) {
            runCatching { SecretStore.set(SecretStore.AUTH_STATUS, output.substring(output.indexOf('{')).trim()) }
        }
        return state
    }

    /** The last `auth status` reply the safe holds, or null. No process spawn — safe on any thread. */
    fun stored(): AuthState? = SecretStore.get(SecretStore.AUTH_STATUS)?.let(::parse)

    /** The CLI may prefix warnings (update notices) before the JSON object; parse from the first brace. */
    private fun parse(output: String): AuthState? {
        val start = output.indexOf('{')
        if (start < 0) return null
        return runCatching { json.decodeFromString<AuthState>(output.substring(start)) }.getOrNull()
    }

    /** True when the logout completed. Clears the BINARY's own credential store, not the IDE's. */
    fun logout(binary: File, env: Map<String, String>): Boolean =
        run(binary, env, "auth", "logout") != null

    /** Runs the binary with [args] and the given env; null on spawn failure, timeout or non-zero exit. */
    private fun run(binary: File, env: Map<String, String>, vararg args: String): String? {
        val output = runCatching {
            val cmd = GeneralCommandLine(listOf(binary.absolutePath) + args)
                .withEnvironment(env)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            CapturingProcessHandler(cmd).runProcess(TIMEOUT_MS)
        }.getOrNull() ?: return null
        if (output.isTimeout || output.exitCode != 0) return null
        return output.stdout
    }

    private const val TIMEOUT_MS = 15_000
}
