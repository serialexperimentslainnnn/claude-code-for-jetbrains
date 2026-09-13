package dev.lain.claudejb.controller.mcp.tools.ops

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.remote.AuthType
import com.intellij.remote.RemoteCredentials
import com.intellij.remote.SshCredentialProvider
import dev.lain.claudejb.model.mcp.Param
import dev.lain.claudejb.model.mcp.Tool
import dev.lain.claudejb.model.mcp.ToolArgs
import dev.lain.claudejb.model.mcp.ToolDomain
import dev.lain.claudejb.model.mcp.ToolResult
import dev.lain.claudejb.model.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SshTools(private val project: Project) {

    fun available(): Boolean = SshCredentialProvider.EP_NAME.extensionList.isNotEmpty()

    fun domain(): ToolDomain = ToolDomain(
        "ssh",
        "The SSH hosts the IDE already knows through its remote interpreters, SDKs and deployment servers: host, port, user and " +
            "how each authenticates, never the secret itself",
        listOf(Tool(SSH_HOSTS, ::hosts)),
    )

    private suspend fun hosts(args: ToolArgs): ToolResult {
        val max = args.int("max", DEFAULT_MAX)
        val rows = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        for (provider in SshCredentialProvider.EP_NAME.extensionList) {
            try {
                provider.getCredentialsList(project).forEach { rows += row(it) }
            } catch (e: ExecutionException) {
                errors += e.message ?: provider.javaClass.simpleName
            }
        }
        return ToolResult.toon(
            buildJsonObject {
                put("count", rows.size)
                put("truncated", rows.size > max)
                put("hosts", buildJsonArray { rows.take(max).forEach { add(it) } })
                put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    private fun row(credentials: RemoteCredentials): JsonObject = buildJsonObject {
        put("host", credentials.host)
        put("port", credentials.port)
        put("user", credentials.userName ?: "")
        put("auth", auth(credentials.authType))
    }

    private fun auth(type: AuthType): String = when (type) {
        AuthType.PASSWORD -> "password"
        AuthType.KEY_PAIR -> "key"
        AuthType.OPEN_SSH -> "agent"
    }

    companion object {

        private const val DEFAULT_MAX = 100

        val SSH_HOSTS = ToolSpec(
            "ssh_hosts",
            "Lists the SSH hosts configured in the IDE (remote interpreters, SDKs, deployment servers) with host, port, user and " +
                "the authentication kind: password, key or agent. Passwords, passphrases and key files are never returned, and " +
                "nothing is connected to; run ssh yourself to reach a host.",
            listOf(Param("max", "Maximum hosts to return (default $DEFAULT_MAX)", type = "integer", required = false)),
        )
    }
}
