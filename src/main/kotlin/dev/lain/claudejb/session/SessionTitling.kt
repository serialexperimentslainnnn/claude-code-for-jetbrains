package dev.lain.claudejb.session

/**
 * How a chat's tab title is decided, once there is a session id to ask about: the binary's own session title
 * first ([resolve], reading it back the same way `--resume` would), and — once, at the end of the first turn,
 * when nothing has authored one yet — asking the binary to generate one ([askForGeneratedTitle]).
 *
 * Extracted out of `ClaudeSession` (the title half of `recordOpenAndTitle`, plus `askForGeneratedTitle`,
 * [titleGenerationAsked] and [userRenamed]) because the order of authority it enforces — user rename → an
 * authored title → the first prompt — is one small piece of state that has nothing to do with running a turn.
 *
 * [currentTitle]/[setTitle]/[fireTitleChanged] are narrow closures rather than a `ClaudeSession` reference on
 * purpose: this class does not need to know what a session is, only what a title is. [fireTitleChanged] is
 * expected to already do whatever thread-hop the caller needs — `ClaudeSession` supplies `{ edt {
 * fireTitleChanged() } }` for both call sites here, which used to differ (one ran inline on an
 * already-EDT callback, the other explicitly hopped from a background executor); folding them into one
 * always-hops closure costs one harmless extra `invokeLater` tick on the path that was already on the EDT; the
 * outer title-changed EDT semantics are unaffected either way.
 */
class SessionTitling(
    private val currentTitle: () -> String,
    private val setTitle: (String) -> Unit,
    private val fireTitleChanged: () -> Unit,
    private val requestGeneratedTitle: (description: String, onResult: (String?) -> Unit) -> Unit,
) {

    /**
     * Whether this process has already asked the binary to name the conversation.
     *
     * Set BEFORE the request goes out, so a refusal, a timeout or a blank answer costs one attempt and not a
     * request per turn for the rest of the session. The binary persists the title it generates, so the normal
     * case never reaches here twice anyway — this bounds the abnormal one.
     */
    @Volatile private var titleGenerationAsked: Boolean = false

    /**
     * Whether the user has renamed this chat by hand.
     *
     * A generated title is asked for once and arrives whenever it arrives; a rename in that window must not
     * be overwritten by it. What the user typed is never replaced by what a model wrote — the ordering of the
     * two answers is not allowed to decide that.
     */
    @Volatile var userRenamed: Boolean = false
        private set

    /** A user rename (`ClaudeSession.renameSession`) is the top of the order of authority — see [userRenamed]. */
    fun markRenamed() {
        userRenamed = true
    }

    /**
     * Resolves the binary's real session title for [id] and relabels the tab if it changed, then asks for a
     * generated one when nothing has authored one yet. Called off-EDT, from `recordOpenAndTitle`.
     */
    fun resolve(id: String) {
        val resolved = SessionTitleReader.read(id)
        if (resolved != null && resolved.text != currentTitle()) {
            setTitle(resolved.text)
            fireTitleChanged()
        }
        // Nothing has NAMED this chat yet — it is showing its opening line. The binary can do better and it
        // is one request away.
        if (resolved?.authored != true) askForGeneratedTitle(resolved?.prompt)
    }

    /**
     * Asks the binary to name this conversation — once, off the critical path, and never at the cost of the
     * name it already has.
     *
     * **Why the binary and not us.** Naming a conversation is a model's job, and the model is already up: the
     * `generate_session_title` control request runs inside the live session, so there is no second process, no
     * credential and no prompt of our own. It persists the answer in its own session file, which is why this
     * costs one request per chat *ever* rather than one per start — the next launch reads it back as an
     * authored title (`SessionTitle.authored`) and never gets here.
     *
     * **When.** At the end of a turn, from [resolve]. Not earlier: before the first turn there is no session
     * id, no prompt on disk and nothing to summarise. Not later than the first turn either — a tab whose name
     * settles two turns in is a tab the user has already learned to find by position.
     *
     * **What it cannot do:** name a subagent. The request carries no agent id and acts on the session that
     * answers it, so a subtab's title stays what the parent model wrote for it in
     * `subagents/agent-<id>.meta.json` — already model-authored text, and the same kind of text this request
     * takes as input.
     */
    private fun askForGeneratedTitle(prompt: String?) {
        val description = prompt?.takeIf { it.isNotBlank() } ?: return
        if (titleGenerationAsked) return
        titleGenerationAsked = true
        requestGeneratedTitle(description) { generated ->
            // Cut to tab size by the same rule as the fallback: the length of a title is not the model's to decide.
            val named = generated?.let { SessionTitleReader.asTitle(it) } ?: return@requestGeneratedTitle
            // A rename that landed while this was in flight is the user's word on the matter, and it stands.
            if (userRenamed || named == currentTitle()) return@requestGeneratedTitle
            setTitle(named)
            fireTitleChanged()
        }
    }
}
