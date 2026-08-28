package dev.lain.claudejb.permission

object ReasonSecrecy {

    const val PLACEHOLDER = "<redacted>"

    private const val MIN_SECRET_LENGTH = 8

    private val SENSITIVE_NAME = Regex(
        """(KEY|TOKEN|SECRET|PASSWORD|PASSWD|PASS|CREDENTIAL|AUTH|SESSION|COOKIE|SIGNATURE|PRIVATE|SALT)""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String?, env: Map<String, String>): String? {
        if (text.isNullOrEmpty() || env.isEmpty()) return text
        var out: String = text
        for ((name, value) in env) {
            if (value.length < MIN_SECRET_LENGTH) continue
            if (!SENSITIVE_NAME.containsMatchIn(name)) continue
            if (!out.contains(value)) continue
            out = out.replace(value, PLACEHOLDER)
        }
        return out
    }
}
