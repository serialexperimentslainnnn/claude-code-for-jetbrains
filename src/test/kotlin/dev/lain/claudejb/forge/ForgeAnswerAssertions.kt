package dev.lain.claudejb.forge

internal fun <T> known(answer: ForgeAnswer<T>): T = when (answer) {
    is ForgeAnswer.Known -> answer.value
    is ForgeAnswer.Silent -> error("expected a parsed answer, got Silent(${answer.reason})")
}
