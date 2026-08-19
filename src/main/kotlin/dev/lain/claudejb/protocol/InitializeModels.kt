package dev.lain.claudejb.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val apiProvider: String = "",
    val apiKeySource: String = "",
)

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
    val supportsEffort: Boolean = false,
    val supportedEffortLevels: List<String> = emptyList(),
    val supportsAdaptiveThinking: Boolean = false,
    val supportsFastMode: Boolean = false,
    val supportsAutoMode: Boolean = false,
)
