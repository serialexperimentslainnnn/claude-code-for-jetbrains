package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// initialize handshake response (binary -> host): rich command + model metadata.
// ---------------------------------------------------------------------------

@Serializable
data class InitializeResponse(
    val commands: List<SlashCommand> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    @SerialName("output_style") val outputStyle: String = "default",
    @SerialName("available_output_styles") val availableOutputStyles: List<String> = emptyList(),
    val account: AccountInfo = AccountInfo(),
)

@Serializable
data class AgentInfo(
    val name: String = "",
    val description: String = "",
)

@Serializable
data class AccountInfo(
    val email: String = "",
    val organization: String = "",
    val subscriptionType: String = "",
    /** Auth backend reported by the binary (firstParty/bedrock/vertex/foundry/anthropicAws/mantle/gateway). */
    val apiProvider: String = "",
    /** Where the API key (if any) came from (e.g. env var, helper script). */
    val apiKeySource: String = "",
)

/** A slash command as reported by the binary: name (no slash), description, argument hint, aliases. */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val aliases: List<String> = emptyList(),
)

@Serializable
data class ModelInfo(
    val value: String,
    val displayName: String = "",
    val description: String = "",
    /** Whether `--effort` is meaningful for this model (Opus 4.7+ supports it; Haiku does not). */
    val supportsEffort: Boolean = false,
    /** Effort levels the model accepts (e.g. ["low","medium","high","xhigh","max"]). */
    val supportedEffortLevels: List<String> = emptyList(),
    /** Whether adaptive extended thinking is supported (drives `--thinking adaptive`). */
    val supportsAdaptiveThinking: Boolean = false,
    /** Whether the model supports the binary's "fast mode" (no reasoning, lowest latency). */
    val supportsFastMode: Boolean = false,
    /** Whether the model supports "auto mode" (binary picks effort/thinking per turn). */
    val supportsAutoMode: Boolean = false,
)
