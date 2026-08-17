package dev.lain.claudejb.forge

/**
 * Unwraps a [ForgeAnswer] that the test expects to be [ForgeAnswer.Known], failing with the actual reason
 * when it is not.
 *
 * Exhaustive `when` rather than a cast, deliberately: an `as ForgeAnswer.Known` is an unchecked cast whose
 * failure message is a `ClassCastException` naming two internal types, which tells whoever reads the CI log
 * nothing about WHICH silence was produced — and the reason is the entire diagnostic value of a failing
 * parse test.
 */
internal fun <T> known(answer: ForgeAnswer<T>): T = when (answer) {
    is ForgeAnswer.Known -> answer.value
    is ForgeAnswer.Silent -> error("expected a parsed answer, got Silent(${answer.reason})")
}
