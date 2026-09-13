package dev.lain.claudejb.model.settings

@kotlinx.serialization.Serializable
data class IdeMcpState(
    @JvmField var enabled: Boolean = true,
    @JvmField var approveClients: Boolean = false,
    @JvmField var mirror: Boolean = true,
    @JvmField var rules: String = LaunchDefaults.DEFAULT_IDE_RULES,
    @JvmField var catalogue: String = "",
)
