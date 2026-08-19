package dev.lain.claudejb.session

class SessionTitling(
    private val currentTitle: () -> String,
    private val setTitle: (String) -> Unit,
    private val fireTitleChanged: () -> Unit,
    private val requestGeneratedTitle: (description: String, onResult: (String?) -> Unit) -> Unit,
) {

    @Volatile private var titleGenerationAsked: Boolean = false

    @Volatile var userRenamed: Boolean = false
        private set

    fun markRenamed() {
        userRenamed = true
    }

    fun resolve(id: String) {
        val resolved = SessionTitleReader.read(id)
        if (resolved != null && resolved.text != currentTitle()) {
            setTitle(resolved.text)
            fireTitleChanged()
        }
        if (resolved?.authored != true) askForGeneratedTitle(resolved?.prompt)
    }

    private fun askForGeneratedTitle(prompt: String?) {
        val description = prompt?.takeIf { it.isNotBlank() } ?: return
        if (titleGenerationAsked) return
        titleGenerationAsked = true
        requestGeneratedTitle(description) { generated ->
            val named = generated?.let { SessionTitleReader.asTitle(it) } ?: return@requestGeneratedTitle
            if (userRenamed || named == currentTitle()) return@requestGeneratedTitle
            setTitle(named)
            fireTitleChanged()
        }
    }
}
