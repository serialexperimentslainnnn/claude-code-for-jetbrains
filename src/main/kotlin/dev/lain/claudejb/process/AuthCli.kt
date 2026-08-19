package dev.lain.claudejb.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import dev.lain.claudejb.settings.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object AuthCli {

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

    fun status(binary: File, env: Map<String, String>): AuthState? {
        val output = run(binary, env, "auth", "status") ?: return null
        val state = parse(output) ?: return null
        if (state.email != null || state.orgName != null) {
            runCatching { SecretStore.set(SecretStore.AUTH_STATUS, output.substring(output.indexOf('{')).trim()) }
        }
        return state
    }

    fun stored(): AuthState? = SecretStore.get(SecretStore.AUTH_STATUS)?.let(::parse)

    private fun parse(output: String): AuthState? {
        val start = output.indexOf('{')
        if (start < 0) return null
        return runCatching { json.decodeFromString<AuthState>(output.substring(start)) }.getOrNull()
    }

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
            CapturingProcessHandler(cmd).runProcess(timeoutMs, true)
        }.getOrNull() ?: return null
        if (output.isTimeout || output.exitCode != 0) return null
        return output.stdout
    }

    fun refreshUsingOwnFiles(binary: File, env: Map<String, String>) {
        runCatching {
            val cmd = GeneralCommandLine(listOf(binary.absolutePath, "auth", "login"))
                .withEnvironment(env)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            CapturingProcessHandler(cmd).runProcess(REFRESH_TIMEOUT_MS, true)
        }
    }

    private const val TIMEOUT_MS = 15_000

    private const val REFRESH_TIMEOUT_MS = 20_000
}
