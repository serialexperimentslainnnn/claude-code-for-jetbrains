package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The binary's non-interactive auth surface: `claude auth status`, plus the on-disk-refresh `auth login`
 * ([refreshUsingOwnFiles]) that lets a planted credentials file be renewed without a browser or a TTY.
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

    // NB the environment-driven renewal that used to live here is gone, and this note is the only thing left
    // of it so nobody rebuilds it from the same reasoning. `CLAUDE_CODE_OAUTH_REFRESH_TOKEN` +
    // `CLAUDE_CODE_OAUTH_SCOPES` does take a branch in the binary, and that branch was verified only against a
    // deliberately INVALID token: it reached the endpoint and got a `400`, which proves the branch exists and
    // nothing about whether a valid token would be honoured. It shipped for a release and a login that expired
    // overnight still asked the user to sign in every morning. What is observed to work is giving the binary
    // its own file back — see `CredentialsVault.renewOnDisk` and `refreshUsingOwnFiles` below.

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

    /**
     * Runs `auth login` for a binary that has been given **its own credentials file back** (see
     * [dev.lain.claudejb.process.CredentialsVault.renewOnDisk]), for the sole purpose of letting it refresh
     * an expired access token from the refresh token beside it.
     *
     * **This deliberately ignores the exit code, and that is the whole reason it is a separate function.**
     * Measured against `claude` 2.1.226 with a real credential whose `expiresAt` had been put in the past: the
     * binary refreshes the token and rewrites the file **first**, and only then decides it also wants an
     * interactive login — printing `Opening browser to sign in…` and waiting at `Paste code here if
     * prompted >` until it is killed. So the process exits non-zero (or is destroyed on timeout) on the very
     * run that did exactly what was wanted, and `exitCode == 0` would report every success as a failure.
     *
     * The verdict therefore belongs to the CALLER, which reads the file back and asks whether the token it
     * now holds is usable. Nothing here interprets the outcome.
     *
     * `destroyOnTimeout` is what bounds it: the refresh happens in the first round trip, so whatever is still
     * running at the deadline is the interactive prompt nobody is going to answer. [REFRESH_TIMEOUT_MS] is
     * therefore sized for one HTTP exchange, not for the binary's own 30 s login timeout — waiting that out
     * would put a minute into the startup path of a session whose token merely aged.
     */
    fun refreshUsingOwnFiles(binary: File, env: Map<String, String>) {
        runCatching {
            val cmd = GeneralCommandLine(listOf(binary.absolutePath, "auth", "login"))
                .withEnvironment(env)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            CapturingProcessHandler(cmd).runProcess(REFRESH_TIMEOUT_MS, true)
        }
        // No logging here on purpose: this function has no verdict to report. Whether the refresh worked is
        // decided by the caller reading the file back, and that is where the outcome is logged.
    }

    private const val TIMEOUT_MS = 15_000

    /**
     * How long the on-disk refresh run is given before it is destroyed. One token exchange, not one login: the
     * process is expected to be killed here rather than to exit, because what it does after refreshing is wait
     * for a code at a prompt (see [refreshUsingOwnFiles]).
     */
    private const val REFRESH_TIMEOUT_MS = 20_000
}
