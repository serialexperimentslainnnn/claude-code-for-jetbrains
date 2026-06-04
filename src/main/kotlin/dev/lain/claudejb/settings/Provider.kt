package dev.lain.claudejb.settings

/**
 * The LLM API provider the `claude` binary talks to.
 *
 * - [ANTHROPIC] is the first-party endpoint: no env override, so the binary uses its own native auth
 *   (subscription/OAuth login or `ANTHROPIC_API_KEY` from the user's own shell). This is the default.
 * - Every other provider is an **Anthropic-API-compatible** endpoint (e.g. DeepSeek's `/anthropic`) that
 *   requires its OWN issued key. We route to it by setting `ANTHROPIC_BASE_URL` **and** `ANTHROPIC_API_KEY`
 *   together. Crucially, because `ANTHROPIC_API_KEY` is present the binary's SDK does NOT load the stored
 *   OAuth `credentials.json` — so the user's Anthropic subscription can never be sent to a third party.
 *
 * Hard rules encoded here (see [launchEnv]): we never emit `ANTHROPIC_AUTH_TOKEN`, never emit a base URL
 * without its key (a lone base URL would make the SDK ship the Anthropic OAuth bearer to the third party),
 * and never reuse Anthropic credentials for another provider.
 */
enum class Provider(val id: String, val label: String, val baseUrl: String?) {
    ANTHROPIC("anthropic", "Anthropic", null),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/anthropic");

    /** True when this provider needs its own `ANTHROPIC_API_KEY` (everything except first-party Anthropic). */
    val requiresApiKey: Boolean get() = baseUrl != null

    companion object {
        val DEFAULT = ANTHROPIC

        /** Resolve a persisted [id] back to a [Provider], falling back to [DEFAULT] for unknown/blank values. */
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * The env overrides the binary needs for [provider], given its [apiKey].
         *
         * For a third-party provider we emit `ANTHROPIC_BASE_URL` + `ANTHROPIC_API_KEY` as an **atomic pair**,
         * and ONLY when [apiKey] is non-blank. Anthropic — or a missing key — emits nothing, so the binary
         * falls back to its own login. We never emit `ANTHROPIC_AUTH_TOKEN`. This is the load-bearing guard
         * that keeps the Anthropic subscription OAuth from ever leaking to a non-official endpoint.
         */
        fun launchEnv(provider: Provider, apiKey: String?): Map<String, String> {
            val base = provider.baseUrl ?: return emptyMap()
            val key = apiKey?.trim().orEmpty()
            if (key.isEmpty()) return emptyMap()
            return mapOf("ANTHROPIC_BASE_URL" to base, "ANTHROPIC_API_KEY" to key)
        }

        /**
         * True if [key] looks like an Anthropic-issued key (prefix `sk-ant-`). Such a key must NOT be used
         * for a third-party provider — the settings UI rejects it so a user can't paste their Anthropic key
         * into the DeepSeek slot.
         */
        fun looksLikeAnthropicKey(key: String): Boolean = key.trim().startsWith("sk-ant-")
    }
}
