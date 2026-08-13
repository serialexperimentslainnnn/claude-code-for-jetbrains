package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The binary's non-interactive auth surface: `claude auth status`, plus the refresh-token `auth login`.
 *
 * **`auth logout` is deliberately not here** (it was, unused, until 5.5.0). The plugin's credential lives in the
 * IDE safe, so clearing the safe IS the logout — see `OnboardingController.logout`. Shelling out would clear the
 * BINARY's own store, signing the user's terminal CLI out of an identity the plugin never owned.
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

    /**
     * **Non-interactive** `claude auth login`, driven entirely by a refresh token in the environment — no
     * browser, no TTY, no user.
     *
     * This is a first-class path in the binary, not a trick: given `CLAUDE_CODE_OAUTH_REFRESH_TOKEN` the
     * command takes a dedicated branch (`tengu_login_from_refresh_token`), exchanges the token at
     * `platform.claude.com/v1/oauth/token` and stores the result in its own credential store, then exits 0.
     * `CLAUDE_CODE_OAUTH_SCOPES` accompanies it and is always sent: the binary carries an explicit refusal
     * for the case where it is missing ("required when using CLAUDE_CODE_OAUTH_REFRESH_TOKEN", naming the
     * space-separated scopes it wants), and the grant cannot be restated without it — so
     * [dev.lain.claudejb.process.CredentialsVault.renew] will not attempt a renewal from a blob that carries
     * no scopes. Verified against `claude` 2.1.223 that the branch is taken and is genuinely non-interactive:
     * with a deliberately invalid refresh token it fails on the HTTP round-trip and exits 1 without opening
     * a browser or waiting on a terminal.
     *
     * That is what makes the vaulted login survive a reboot: the access token lives hours, the refresh token
     * lives weeks, and this is the plugin's way of spending the second to mint the first WITHOUT holding an
     * OAuth client itself. Not "the host refreshes the token" — the binary does, exactly as it always has.
     *
     * Its own 30 s HTTP timeout sits under this one, hence the longer wait: a renewal killed at 15 s would be
     * reported as a failed login when it was merely a slow network.
     */
    fun loginFromRefreshToken(binary: File, env: Map<String, String>): Boolean =
        run(binary, env, "auth", "login", timeoutMs = LOGIN_TIMEOUT_MS) != null

    /** Runs the binary with [args] and the given env; null on spawn failure, timeout or non-zero exit. */
    private fun run(
        binary: File,
        env: Map<String, String>,
        vararg args: String,
        timeoutMs: Int = TIMEOUT_MS,
    ): String? {
        val output = runCatching {
            val cmd = GeneralCommandLine(listOf(binary.absolutePath) + args)
                .withEnvironment(env)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            // destroyOnTimeout: a binary that never answers must not outlive the question. Without it the
            // timeout only stops us WAITING — the process and its stream readers stay alive, which surfaced as
            // a leaked-thread failure attributed to whichever test ran next.
            CapturingProcessHandler(cmd).runProcess(timeoutMs, true)
        }.getOrNull() ?: return null
        if (output.isTimeout || output.exitCode != 0) return null
        return output.stdout
    }

    private const val TIMEOUT_MS = 15_000

    /** A renewal is a network round-trip with a 30 s timeout of its own; 15 s would cut it short. */
    private const val LOGIN_TIMEOUT_MS = 60_000
}
