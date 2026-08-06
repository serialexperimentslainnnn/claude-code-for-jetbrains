package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The binary's non-interactive auth surface: `claude auth status --json` and `claude auth logout`.
 * Verified against the real CLI (2.1.222) — `claude auth` offers `login`, `logout` and `status`, and
 * `status` outputs JSON by default: `{loggedIn, authMethod, apiProvider, email, orgName,
 * subscriptionType, …}`.
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

    /** What `auth status --json` reports, reduced to the fields the UI consumes. Lenient: unknown keys ignored. */
    @Serializable
    data class AuthState(
        val loggedIn: Boolean = false,
        val authMethod: String? = null,
        val email: String? = null,
        val subscriptionType: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Null when the probe could not run or its output was not parseable — "unknown", never "logged out". */
    fun status(binary: File, env: Map<String, String>): AuthState? {
        val output = run(binary, env, "auth", "status", "--json") ?: return null
        // The CLI may prefix warnings (update notices) before the JSON object; parse from the first brace.
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
