package dev.lain.claudejb.process

object LoginOutputParser {

    private val ANSI = Regex("(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^]*(?:|\\\\)?|[()][0-9A-Za-z]|[=>78])")

    private val AUTH_URL = Regex("https://[\\w.\\-]+/[\\w./\\-]*oauth/authorize\\?[\\w./?=&%+\\-~:]+")

    private val CODE_PROMPT_HINTS = listOf("pastecodehere", "pastethecode", "enterthecode", "enteryourcode")
    private val SUCCESS_HINTS = listOf("loginsuccessful", "loggedin", "successfully", "youreallset", "authenticated")
    private val FAILURE_HINTS =
        listOf("invalidcode", "loginfailed", "authenticationfailed", "oautherror", "didnotmatch", "expired", "error")

    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    private val NON_ALNUM = Regex("[^a-z0-9]")

    private fun normalize(text: String): String = stripAnsi(text).lowercase().replace(NON_ALNUM, "")

    fun extractAuthUrl(text: String): String? = AUTH_URL.find(stripAnsi(text))?.value

    fun isCodePrompt(text: String): Boolean {
        val t = normalize(text)
        return CODE_PROMPT_HINTS.any { it in t }
    }

    fun looksLikeFailure(text: String): Boolean {
        val t = normalize(text)
        return FAILURE_HINTS.any { it in t }
    }

    fun extractSetupToken(text: String): String? =
        SETUP_TOKEN.findAll(stripAnsi(text)).lastOrNull()?.value

    private val SETUP_TOKEN = Regex("sk-ant-[A-Za-z0-9_\\-]{20,}")

    fun redactSecrets(text: String): String = SETUP_TOKEN.replace(stripAnsi(text), "sk-ant-…")

    fun resultMessage(text: String, success: Boolean): String {
        val lines = redactSecrets(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (success) {
            return lines.lastMatching(SUCCESS_HINTS)?.let(::withoutKeyPrompt) ?: "You're signed in."
        }
        return lines.lastMatching(FAILURE_HINTS)?.let(::withoutKeyPrompt) ?: "Login failed. Please try again."
    }

    private fun List<String>.lastMatching(hints: List<String>): String? =
        lastOrNull { line -> normalize(line).let { n -> hints.any { it in n } } }

    private fun withoutKeyPrompt(line: String): String =
        line.replace(KEY_PROMPT, "").trim().trimEnd(',', ';', '·', '-').trim().ifEmpty { line }

    private val KEY_PROMPT = Regex("\\bPress\\s+(Enter|any other key|any key)\\b[^.]*\\.?", RegexOption.IGNORE_CASE)
}
