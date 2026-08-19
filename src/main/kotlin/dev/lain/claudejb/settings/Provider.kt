package dev.lain.claudejb.settings

enum class Provider(val id: String, val label: String, val baseUrl: String?) {
    ANTHROPIC("anthropic", "Anthropic", null),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/anthropic"),
    ;

    val requiresApiKey: Boolean get() = baseUrl != null

    companion object {
        val DEFAULT = ANTHROPIC

        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: DEFAULT

        fun launchEnv(provider: Provider, apiKey: String?): Map<String, String> {
            val base = provider.baseUrl ?: return emptyMap()
            val key = apiKey?.trim().orEmpty()
            if (key.isEmpty()) return emptyMap()
            return mapOf("ANTHROPIC_BASE_URL" to base, "ANTHROPIC_API_KEY" to key)
        }

        fun looksLikeAnthropicKey(key: String): Boolean = key.trim().startsWith("sk-ant-")
    }
}
