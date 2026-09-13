package dev.lain.claudejb.model.session.turn

class TurnState {

    @Volatile var active: Boolean = false
        internal set

    @Volatile var interrupting: Boolean = false
        internal set

    @Volatile var liveThinkingTokens: Int = 0
        internal set

    internal fun reset() {
        active = false
        interrupting = false
        liveThinkingTokens = 0
    }
}
