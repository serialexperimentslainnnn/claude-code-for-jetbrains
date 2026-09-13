package dev.lain.claudejb.util

object ReasonSecrecy {

    const val PLACEHOLDER = "<redacted>"

    private const val MIN_SECRET_LENGTH = 8

    private val SENSITIVE_NAME = Regex(
        """(KEY|TOKEN|SECRET|PASSWORD|PASSWD|PASS|CREDENTIAL|AUTH|SESSION|COOKIE|SIGNATURE|PRIVATE|SALT)""",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitive(name: String, value: String): Boolean =
        value.length >= MIN_SECRET_LENGTH && SENSITIVE_NAME.containsMatchIn(name)

    fun redact(text: String?, env: Map<String, String>): String? {
        if (text.isNullOrEmpty() || env.isEmpty()) return text
        val secrets = env.entries.filter { isSensitive(it.key, it.value) }.map { it.value }
        return secrets.fold(text) { carried, secret -> carried.replace(secret, PLACEHOLDER) }
    }
}
