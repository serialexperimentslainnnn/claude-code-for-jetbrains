package dev.lain.claudejb.util

import java.util.concurrent.CopyOnWriteArraySet

object LogRedaction {

    const val MAX_TEXT = 2000

    private const val MASKED_KEY = "sk-ant-…"

    private val API_KEY = Regex("""sk-ant-[A-Za-z0-9_\-]{20,}""")

    private val secrets = CopyOnWriteArraySet<String>()

    @Volatile
    var home: String? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }

    fun remember(env: Map<String, String>) {
        env.forEach { (name, value) -> if (ReasonSecrecy.isSensitive(name, value)) secrets.add(value) }
    }

    fun apply(text: String): String {
        var out = secrets.fold(text) { carried, secret -> carried.replace(secret, ReasonSecrecy.PLACEHOLDER) }
        out = API_KEY.replace(out, MASKED_KEY)
        home?.let { root ->
            out = out.replace(root, "~")
            val flipped = root.replace('\\', '/')
            if (flipped != root) out = out.replace(flipped, "~")
        }
        return if (out.length > MAX_TEXT) out.take(MAX_TEXT) + "…" else out
    }
}
